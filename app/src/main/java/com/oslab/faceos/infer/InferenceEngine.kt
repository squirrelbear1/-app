package com.oslab.faceos.infer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Log
import com.oslab.faceos.ipc.InferenceResult
import com.oslab.faceos.ml.Config
import com.oslab.faceos.ml.EdgeFaceRecognizer
import com.oslab.faceos.ml.FaceAligner
import com.oslab.faceos.ml.FaceDetection
import com.oslab.faceos.ml.FaceGallery
import com.oslab.faceos.ml.LivenessResult
import com.oslab.faceos.ml.MiniFasNetDetector
import com.oslab.faceos.ml.ScrfdDetector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 推理引擎：把三个模型的完整流水线封装成一个可复用组件。
 *
 * 两种运行方式共用本类，保证"单进程 vs 双进程"对比时算法完全一致：
 *   - 双进程：由 :infer 进程的 [InferenceService] 持有；
 *   - 单进程：主进程直接持有（基线对照）。
 *
 * 线程安全：session.run 可并发；人脸库内部加锁；最近一次特征/活体状态用 Atomic 保存。
 */
class InferenceEngine(context: Context, initialVariant: String = VARIANT_FP32) {

    private val appContext = context.applicationContext

    /** 当前模型变体：fp32 / int8 / fp16（模型侧优化对照实验用） */
    @Volatile
    var variant: String = initialVariant
        private set

    @Volatile
    var detector: ScrfdDetector = ScrfdDetector(appContext, assetName = detAsset(initialVariant))
        private set

    @Volatile
    var recognizer: EdgeFaceRecognizer = EdgeFaceRecognizer(appContext, assetName = recAsset(initialVariant))
        private set

    @Volatile
    var livenessDetector: MiniFasNetDetector = MiniFasNetDetector(appContext, assetName = liveAsset(initialVariant))
        private set

    val gallery = FaceGallery(context)

    @Volatile
    var loadTimeMs: Long = detector.modelLoadTimeMs + recognizer.modelLoadTimeMs + livenessDetector.modelLoadTimeMs
        private set

    /**
     * 运行时切换模型变体（fp32 / int8 / fp16）。
     * 用于"优化前后对照实验"：同一 App、同一测试条件，只换模型文件。
     *
     * 采用"先建新、再替换、最后关旧"的事务式切换：
     * 若新变体加载失败（例如 int8 版本算子不被当前 ORT 支持），旧模型仍然可用，不会把服务搞挂。
     */
    @Synchronized
    fun reload(newVariant: String): Boolean {
        if (newVariant == variant) return true
        return try {
            val newDet = ScrfdDetector(appContext, inputSize = detInputSize, assetName = detAsset(newVariant))
            val newRec = EdgeFaceRecognizer(appContext, assetName = recAsset(newVariant))
            val newLive = MiniFasNetDetector(appContext, assetName = liveAsset(newVariant))

            val oldDet = detector
            val oldRec = recognizer
            val oldLive = livenessDetector
            detector = newDet
            recognizer = newRec
            livenessDetector = newLive
            variant = newVariant
            loadTimeMs = newDet.modelLoadTimeMs + newRec.modelLoadTimeMs + newLive.modelLoadTimeMs

            try {
                oldDet.close()
                oldRec.close()
                oldLive.close()
            } catch (e: Throwable) {
                // 忽略旧实例关闭异常
            }
            true
        } catch (t: Throwable) {
            Log.w(
                "InferenceEngine",
                "reload variant failed: $newVariant, keep current=$variant, reason=${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
    }

    val pid: Int = android.os.Process.myPid()

    /** 检测网络输入尺寸（模型侧优化：640 → 480 → 320 可显著降低检测耗时） */
    @Volatile
    var detInputSize: Int = 640
        private set

    /** 运行时切换检测输入分辨率（不换模型文件，只改输入尺寸） */
    @Synchronized
    fun reloadDetSize(size: Int): Boolean {
        val target = size.coerceIn(320, 640)
        if (target == detInputSize) return true
        return try {
            val newDet = ScrfdDetector(appContext, inputSize = target, assetName = detAsset(variant))
            val old = detector
            detector = newDet
            detInputSize = target
            try {
                old.close()
            } catch (e: Throwable) {
            }
            true
        } catch (t: Throwable) {
            Log.w("InferenceEngine", "reload det size failed: $size, reason=${t.message}")
            false
        }
    }

    private val lastEmbedding = AtomicReference<FloatArray?>(null)
    private val lastLive = AtomicBoolean(false)

    /**
     * @param rgba 紧凑排列的 RGBA_8888 像素（长度 = width*height*4）
     * @param rotationDegrees 相机帧需要旋转的角度（0/90/180/270）
     */
    fun analyze(
        rgba: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        seq: Long
    ): InferenceResult {
        val t0 = System.currentTimeMillis()
        val raw = rgbaToBitmap(rgba, width, height)
        val upright = rotate(raw, rotationDegrees)
        if (upright !== raw) raw.recycle()

        val w = upright.width
        val h = upright.height
        val (detections, detMs) = detector.detect(upright)

        var liveMs = 0L
        var recMs = 0L
        var largestLive: LivenessResult? = null
        var embedding: FloatArray? = null

        val n = detections.size
        val names = Array(n) { "" }
        val similarities = FloatArray(n)
        val liveScores = FloatArray(n) { -1f }
        val liveLabels = Array(n) { "未测" }
        val hasLiveness = BooleanArray(n)

        // 只对面积最大的脸做人脸对齐 + 活体 + 识别（实验允许合理限制单人脸）
        val largest = detections.maxByOrNull { it.width * it.height }
        if (largest != null) {
            val idx = detections.indexOfFirst { it === largest }

            val tLive = System.currentTimeMillis()
            val live = livenessDetector.detect(upright, largest)
            liveMs = System.currentTimeMillis() - tLive
            if (live != null) {
                largestLive = live
                liveScores[idx] = live.score
                liveLabels[idx] = live.label
                hasLiveness[idx] = true
            }

            val tRec = System.currentTimeMillis()
            val aligned = FaceAligner.align(upright, largest.keypoints)
            if (aligned != null) {
                val emb = recognizer.embed(aligned)
                aligned.recycle()
                if (emb != null) {
                    embedding = emb
                    val match = gallery.match(emb, Config.RECOGNITION_THRESHOLD)
                    if (match != null) {
                        names[idx] = match.name
                        similarities[idx] = match.similarity
                    }
                }
            }
            recMs = System.currentTimeMillis() - tRec
        }

        lastEmbedding.set(embedding)
        lastLive.set(largestLive?.live == true)
        upright.recycle()

        // 展平结果，减少 Binder 序列化开销
        val boxes = FloatArray(n * 4)
        val scores = FloatArray(n)
        val kps = FloatArray(n * 10)
        for (i in 0 until n) {
            val d = detections[i]
            boxes[i * 4] = d.x1
            boxes[i * 4 + 1] = d.y1
            boxes[i * 4 + 2] = d.x2
            boxes[i * 4 + 3] = d.y2
            scores[i] = d.score
            for (p in 0 until 5) {
                kps[i * 10 + p * 2] = d.keypoints.getOrNull(p)?.x ?: 0f
                kps[i * 10 + p * 2 + 1] = d.keypoints.getOrNull(p)?.y ?: 0f
            }
        }

        return InferenceResult(
            seq = seq,
            width = w,
            height = h,
            faceCount = n,
            boxes = boxes,
            scores = scores,
            kps = kps,
            names = names,
            similarities = similarities,
            liveScores = liveScores,
            liveLabels = liveLabels,
            hasLiveness = hasLiveness,
            detMs = detMs,
            liveMs = liveMs,
            recMs = recMs,
            totalMs = System.currentTimeMillis() - t0,
            servicePid = pid,
            threadName = Thread.currentThread().name
        )
    }

    /** 用最近一帧最大人脸的特征注册（活体未通过时拒绝） */
    fun register(name: String): Boolean {
        if (name.isBlank()) return false
        val emb = lastEmbedding.get() ?: return false
        if (!lastLive.get()) return false
        gallery.register(name, emb)
        return true
    }

    fun clearGallery(): Boolean {
        gallery.clear()
        return true
    }

    fun close() {
        detector.close()
        recognizer.close()
        livenessDetector.close()
    }

    // ---------------- 内部工具 ----------------

    private fun rgbaToBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        var i = 0
        var o = 0
        while (i < rgba.size && o < pixels.size) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val a = rgba[i + 3].toInt() and 0xFF
            pixels[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            i += 4
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val matrix = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** 供 UI 使用：把跨进程结果还原成绘制用的数据 */
    companion object {
        const val VARIANT_FP32 = "fp32"
        const val VARIANT_INT8 = "int8"
        const val VARIANT_FP16 = "fp16"

        private fun variantAsset(base: String, variant: String): String =
            if (variant == VARIANT_FP32) base else base.replace(".onnx", "_$variant.onnx")

        fun detAsset(variant: String): String =
            variantAsset("models/det_500m.onnx", variant)

        fun recAsset(variant: String): String =
            variantAsset("models/edgeface_xxs.onnx", variant)

        fun liveAsset(variant: String): String =
            variantAsset("models/minifasnet_v2.onnx", variant)

        fun toDetections(result: InferenceResult): List<FaceDetection> {
            return (0 until result.faceCount).map { i ->
                val kp = (0 until 5).map { p ->
                    PointF(result.kps[i * 10 + p * 2], result.kps[i * 10 + p * 2 + 1])
                }
                FaceDetection(
                    x1 = result.boxes[i * 4],
                    y1 = result.boxes[i * 4 + 1],
                    x2 = result.boxes[i * 4 + 2],
                    y2 = result.boxes[i * 4 + 3],
                    score = result.scores[i],
                    keypoints = kp
                )
            }
        }
    }
}
