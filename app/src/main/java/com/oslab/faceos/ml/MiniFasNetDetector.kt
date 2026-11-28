package com.oslab.faceos.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * MiniFASNetV2 静默活体检测（Presentation Attack Detection）。
 *
 * 模型：2.7_80x80_MiniFASNetV2（minivision-ai Silent-Face-Anti-Spoofing 官方权重转 ONNX）
 *   输入：[N, 3, 80, 80]，**BGR**，像素值保持 0~255（官方 test.py 不做 /255 归一化）
 *   输出：[N, 3] logits，softmax 后三类概率
 *
 * 裁剪方式：以检测框中心、按 2.7 倍框大小裁剪成正方形（与官方一致，不做对齐 warp）。
 * 活体分数 = probs[1]（等价于 1 - (p_print + p_replay)）。
 *
 * 类别顺序（上游 test.py 语义）：索引 1 = 真人，0/2 = 攻击；
 * 最终阈值与顺序用真机采集的真人/打印/屏幕样本标定。
 */
class MiniFasNetDetector(
    context: Context,
    intraOpThreads: Int = 2,
    private val assetName: String = DEFAULT_ASSET
) {
    companion object {
        const val DEFAULT_ASSET = "models/minifasnet_v2.onnx"
        private const val INPUT_SIZE = 80
        val CLASS_NAMES = arrayOf("PRINT", "LIVE", "REPLAY")
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String
    private var debugCount = 0

    val modelLoadTimeMs: Long

    init {
        val t0 = System.currentTimeMillis()
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
        outputName = session.outputNames.first()
        modelLoadTimeMs = System.currentTimeMillis() - t0
    }

    fun detect(src: Bitmap, det: FaceDetection): LivenessResult? {
        val crop = FaceAligner.cropSquare(src, det, Config.LIVENESS_CROP_MARGIN) ?: return null
        val debug = (++debugCount % 15 == 0)
        if (debug) {
            Log.i(
                "MiniFAS",
                "src=${src.width}x${src.height} det=(${det.x1.toInt()},${det.y1.toInt()})-(${det.x2.toInt()},${det.y2.toInt()})" +
                    " crop=${crop.width}x${crop.height}"
            )
        }
        val scaled = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        if (scaled !== crop) crop.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(plane)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        scaled.recycle()

        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            input[i] = (c and 0xFF).toFloat()              // B（保持 0~255，官方实现不做归一化）
            input[plane + i] = ((c shr 8) and 0xFF).toFloat()   // G
            input[2 * plane + i] = ((c shr 16) and 0xFF).toFloat()  // R
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val result = session.run(mapOf(inputName to tensor))
        tensor.close()
        try {
            val out = result.get(outputName).orElse(null) as? OnnxTensor ?: return null
            val logits = FloatArray(3)
            out.floatBuffer.get(logits)

            var maxLogit = logits[0]
            for (v in logits) if (v > maxLogit) maxLogit = v
            var sum = 0f
            val probs = FloatArray(3)
            for (i in 0..2) {
                probs[i] = Math.exp((logits[i] - maxLogit).toDouble()).toFloat()
                sum += probs[i]
            }
            for (i in 0..2) probs[i] /= sum

            // 上游 test.py 语义：argmax==1 才是真人（Real Face），其余为攻击
            var top = 0
            for (i in 0..2) if (probs[i] > probs[top]) top = i
            val score = probs[1].coerceIn(0f, 1f)
            val live = score >= Config.LIVENESS_THRESHOLD
            // 注意：不能直接沿用 CLASS_NAMES[top]，否则“argmax=1 但置信度不足”时会返回 LIVE
            val label = when {
                live -> "LIVE"
                top == 1 -> "UNSTABLE"
                else -> CLASS_NAMES[top]
            }
            if (debug) {
                Log.i(
                    "MiniFAS",
                    "probs=${probs.contentToString()} score=$score label=$label"
                )
            }
            return LivenessResult(
                probs = probs,
                score = score,
                live = live,
                label = label
            )
        } finally {
            result.close()
        }
    }

    fun close() {
        session.close()
    }
}
