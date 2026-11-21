package com.oslab.faceos.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SCRFD-500M-KPS 人脸检测器（ONNX Runtime，设备端 CPU 推理）。
 *
 * 模型来源：InsightFace 官方模型库 buffalo_sc -> det_500m.onnx
 *   - 输入：  [1, 3, 640, 640] RGB，归一化 (x - 127.5) / 128
 *   - 输出：  9 个张量（stride 8/16/32 各 3 个）
 *        scores [N, 1]、bbox [N, 4]、kps [N, 10]，每个网格 2 个 anchor
 *
 * 解码流程对应 InsightFace 官方 scrfd.py：
 *   anchor_center = 网格中心 * stride
 *   bbox = anchor_center ± bbox_pred * stride
 *   kps  = anchor_center + kps_pred * stride
 */
class ScrfdDetector(
    context: Context,
    private val inputSize: Int = 640,
    private val scoreThreshold: Float = 0.5f,
    private val nmsThreshold: Float = 0.4f,
    intraOpThreads: Int = 2
) {
    companion object {
        private const val MODEL_ASSET = "models/det_500m.onnx"
        private val FEAT_STRIDE = intArrayOf(8, 16, 32)
        private const val NUM_ANCHORS = 2
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputNames: List<String>

    /** 最近一次预处理使用的缩放/填充，用于把模型坐标还原到原图坐标 */
    private var scale = 1f
    private var padX = 0f
    private var padY = 0f

    val modelLoadTimeMs: Long

    init {
        val t0 = System.currentTimeMillis()
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
        outputNames = session.outputNames.toList()
        modelLoadTimeMs = System.currentTimeMillis() - t0
    }

    /**
     * @param bitmap 已旋转为正向的 ARGB_8888 分析帧
     * @return 检测结果（坐标已还原到 bitmap 坐标系），以及本次推理耗时（毫秒）
     */
    fun detect(bitmap: Bitmap): Pair<List<FaceDetection>, Long> {
        val t0 = System.currentTimeMillis()

        // ---------- 1. 预处理：letterbox 缩放到 640x640，归一化为 NCHW ----------
        val srcW = bitmap.width
        val srcH = bitmap.height
        scale = min(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        padX = (inputSize - newW) / 2f
        padY = (inputSize - newH) / 2f

        val pixels = IntArray(srcW * srcH)
        bitmap.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH)

        val plane = inputSize * inputSize
        val input = FloatArray(3 * plane)
        val planeLen = plane
        // 先填 0（黑色 padding），再把缩放后的图像写入对应区域
        java.util.Arrays.fill(input, 0f)
        val x0 = padX.toInt()
        val y0 = padY.toInt()
        for (y in 0 until newH) {
            val sy = (y / scale).toInt().coerceIn(0, srcH - 1)
            val srcRow = sy * srcW
            val dstRow = (y0 + y) * inputSize + x0
            for (x in 0 until newW) {
                val sx = (x / scale).toInt().coerceIn(0, srcW - 1)
                val c = pixels[srcRow + sx]
                val idx = dstRow + x
                input[idx] = (((c shr 16) and 0xFF) - 127.5f) / 128f      // R
                input[planeLen + idx] = (((c shr 8) and 0xFF) - 127.5f) / 128f  // G
                input[2 * planeLen + idx] = ((c and 0xFF) - 127.5f) / 128f      // B
            }
        }

        // ---------- 2. 推理 ----------
        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )
        val outputs: OrtSession.Result = session.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        // ---------- 3. 按输出形状归类：scores[N,1] / bbox[N,4] / kps[N,10] ----------
        val scores = HashMap<Int, FloatArray>()
        val bboxes = HashMap<Int, FloatArray>()
        val kpss = HashMap<Int, FloatArray>()
        try {
            for (name in outputNames) {
                val tensor = outputs.get(name).orElse(null) as? OnnxTensor ?: continue
                val dims = tensor.info.shape
                if (dims.size != 2) continue
                val n = dims[0].toInt()
                val ch = dims[1].toInt()
                val buf = tensor.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                when (ch) {
                    1 -> scores[n] = arr
                    4 -> bboxes[n] = arr
                    10 -> kpss[n] = arr
                }
            }
        } finally {
            outputs.close()
        }

        // ---------- 4. 解码 + NMS ----------
        val candidates = ArrayList<FaceDetection>(64)
        for (stride in FEAT_STRIDE) {
            val fmW = inputSize / stride
            val fmH = inputSize / stride
            val anchorCount = fmW * fmH * NUM_ANCHORS
            val s = scores[anchorCount] ?: continue
            val b = bboxes[anchorCount] ?: continue
            val k = kpss[anchorCount]

            for (i in 0 until anchorCount) {
                val score = s[i]
                if (score < scoreThreshold) continue

                val cell = i / NUM_ANCHORS
                val cx = (cell % fmW + 0.5f) * stride
                val cy = (cell / fmW + 0.5f) * stride

                val dx1 = b[i * 4] * stride
                val dy1 = b[i * 4 + 1] * stride
                val dx2 = b[i * 4 + 2] * stride
                val dy2 = b[i * 4 + 3] * stride
                var x1 = cx - dx1
                var y1 = cy - dy1
                var x2 = cx + dx2
                var y2 = cy + dy2

                // 还原到原图坐标
                x1 = (x1 - padX) / scale
                y1 = (y1 - padY) / scale
                x2 = (x2 - padX) / scale
                y2 = (y2 - padY) / scale

                val kps = ArrayList<PointF>(5)
                if (k != null) {
                    for (p in 0 until 5) {
                        val kx = (cx + k[i * 10 + p * 2] * stride - padX) / scale
                        val ky = (cy + k[i * 10 + p * 2 + 1] * stride - padY) / scale
                        kps.add(PointF(kx, ky))
                    }
                }
                candidates.add(
                    FaceDetection(
                        x1 = x1.coerceIn(0f, srcW.toFloat()),
                        y1 = y1.coerceIn(0f, srcH.toFloat()),
                        x2 = x2.coerceIn(0f, srcW.toFloat()),
                        y2 = y2.coerceIn(0f, srcH.toFloat()),
                        score = score,
                        keypoints = kps
                    )
                )
            }
        }

        val elapsed = System.currentTimeMillis() - t0
        return Pair(nms(candidates), elapsed)
    }

    private fun nms(input: List<FaceDetection>): List<FaceDetection> {
        if (input.size <= 1) return input
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<FaceDetection>(sorted.size)
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(best, it.next()) > nmsThreshold) it.remove()
            }
        }
        return kept
    }

    private fun iou(a: FaceDetection, b: FaceDetection): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        session.close()
    }

    /** 计算模型输入为 640 时各 stride 的 anchor 数（供文档/调试展示） */
    fun anchorCountForStride(stride: Int): Int {
        val f = inputSize / stride
        return f * f * NUM_ANCHORS
    }

    fun strideOf(anchorCount: Int): Int {
        val f = sqrt(anchorCount.toDouble() / NUM_ANCHORS)
        return (inputSize / f).toInt()
    }
}
