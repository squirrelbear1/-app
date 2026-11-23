package com.oslab.faceos.ml

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * EdgeFace-XXS 人脸特征提取（512 维 embedding）。
 *
 * 模型：EdgeFace-XXS（IJCB 2023 轻量人脸识别冠军模型的 XXS 版本，4.9 MB）
 *   输入：[N, 3, 112, 112]，RGB，归一化 (x - 127.5) / 128
 *   输出：[N, 512] 特征向量（本类做 L2 归一化后返回）
 *
 * 相似度：两个归一化特征的余弦相似度 = 点积，阈值见 Config.RECOGNITION_THRESHOLD。
 */
class EdgeFaceRecognizer(
    context: Context,
    intraOpThreads: Int = 2,
    private val assetName: String = DEFAULT_ASSET
) {
    companion object {
        const val EMBEDDING_SIZE = 512
        const val DEFAULT_ASSET = "models/edgeface_xxs.onnx"
        private const val INPUT_SIZE = 112
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

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

    /**
     * @param aligned 已按 ArcFace 模板对齐的 112×112 人脸
     * @return L2 归一化后的 512 维特征；失败返回 null
     */
    fun embed(aligned: Bitmap): FloatArray? {
        if (aligned.width != INPUT_SIZE || aligned.height != INPUT_SIZE) return null
        val plane = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(plane)
        aligned.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            input[i] = (((c shr 16) and 0xFF) - 127.5f) / 128f
            input[plane + i] = (((c shr 8) and 0xFF) - 127.5f) / 128f
            input[2 * plane + i] = ((c and 0xFF) - 127.5f) / 128f
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
            val buf = out.floatBuffer
            val emb = FloatArray(EMBEDDING_SIZE)
            buf.get(emb)

            var norm = 0f
            for (v in emb) norm += v * v
            norm = sqrt(norm)
            if (norm < 1e-6f) return null
            for (i in emb.indices) emb[i] /= norm
            return emb
        } finally {
            result.close()
        }
    }

    fun close() {
        session.close()
    }
}
