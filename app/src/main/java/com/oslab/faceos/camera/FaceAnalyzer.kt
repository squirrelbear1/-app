package com.oslab.faceos.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.oslab.faceos.ml.AnalyzedFace
import com.oslab.faceos.ml.Config
import com.oslab.faceos.ml.EdgeFaceRecognizer
import com.oslab.faceos.ml.FaceAligner
import com.oslab.faceos.ml.FaceGallery
import com.oslab.faceos.ml.MiniFasNetDetector
import com.oslab.faceos.ml.ScrfdDetector

/**
 * 阶段 3/4：CameraX 帧分析 -> 检测 -> 对齐 -> 活体 -> 识别（完整链路）。
 *
 * 处理策略：
 *   - 检测：对画面中所有人脸做 SCRFD，全部画框；
 *   - 活体 + 识别：只对面积最大的那张脸做（实验要求允许“合理限制仅处理单个人脸”），
 *     避免多条流水线抢 CPU，也便于统计单帧各阶段耗时。
 *
 * 说明：当前仍是「一条分析线程串行」的基线版本。
 * 阶段 5 会改成 采集线程 + 推理线程池 + Semaphore 有界限流，用同一套指标做对照实验。
 */
class FaceAnalyzer(
    private val detector: ScrfdDetector,
    private val recognizer: EdgeFaceRecognizer,
    private val livenessDetector: MiniFasNetDetector,
    private val gallery: FaceGallery,
    private val onResult: (FrameResult) -> Unit
) : ImageAnalysis.Analyzer {

    data class FrameResult(
        val faces: List<AnalyzedFace>,
        val imageWidth: Int,
        val imageHeight: Int,
        val detMs: Long,
        val recMs: Long,
        val liveMs: Long,
        val fps: Float,
        val threadName: String,
        /** 最大人脸的对齐小图（注册时用） */
        val alignedBitmap: Bitmap?,
        /** 最大人脸的特征向量（注册时用） */
        val alignedEmbedding: FloatArray?
    )

    private var lastFrameTs = 0L
    private var smoothFps = 0f

    override fun analyze(image: ImageProxy) {
        try {
            val raw = image.toRgbaBitmap()
            val upright = rotate(raw, image.imageInfo.rotationDegrees)
            if (upright !== raw) raw.recycle()

            // ---------- 1. 检测 ----------
            val (detections, detMs) = detector.detect(upright)

            // ---------- 2. 只对最大人脸做活体 + 识别 ----------
            var recMs = 0L
            var liveMs = 0L
            var alignedBitmap: Bitmap? = null
            var alignedEmbedding: FloatArray? = null
            val largest = detections.maxByOrNull { it.width * it.height }

            val analyzed = ArrayList<AnalyzedFace>(detections.size)
            for (det in detections) {
                var identity: String? = null
                var similarity = 0f
                var live = null as com.oslab.faceos.ml.LivenessResult?

                if (largest != null && det === largest) {
                    val tLive = System.currentTimeMillis()
                    live = livenessDetector.detect(upright, det)
                    liveMs = System.currentTimeMillis() - tLive

                    val tRec = System.currentTimeMillis()
                    val aligned = FaceAligner.align(upright, det.keypoints)
                    if (aligned != null) {
                        alignedBitmap = aligned
                        val emb = recognizer.embed(aligned)
                        if (emb != null) {
                            alignedEmbedding = emb
                            val match = gallery.match(emb, Config.RECOGNITION_THRESHOLD)
                            if (match != null) {
                                identity = match.name
                                similarity = match.similarity
                            }
                        }
                    }
                    recMs = System.currentTimeMillis() - tRec
                }
                analyzed.add(AnalyzedFace(det, identity, similarity, live))
            }

            val w = upright.width
            val h = upright.height
            upright.recycle()

            // ---------- 3. FPS 统计 ----------
            val now = System.nanoTime()
            if (lastFrameTs != 0L) {
                val inst = 1e9f / (now - lastFrameTs).toFloat()
                smoothFps = if (smoothFps == 0f) inst else smoothFps * 0.8f + inst * 0.2f
            }
            lastFrameTs = now

            onResult(
                FrameResult(
                    faces = analyzed,
                    imageWidth = w,
                    imageHeight = h,
                    detMs = detMs,
                    recMs = recMs,
                    liveMs = liveMs,
                    fps = smoothFps,
                    threadName = Thread.currentThread().name,
                    alignedBitmap = alignedBitmap,
                    alignedEmbedding = alignedEmbedding
                )
            )
        } catch (t: Throwable) {
            onResult(
                FrameResult(
                    faces = emptyList(),
                    imageWidth = image.width,
                    imageHeight = image.height,
                    detMs = 0,
                    recMs = 0,
                    liveMs = 0,
                    fps = 0f,
                    threadName = "error: ${t.javaClass.simpleName}",
                    alignedBitmap = null,
                    alignedEmbedding = null
                )
            )
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toRgbaBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = width
        val h = height
        val bytes = ByteArray(buffer.remaining())
        buffer.rewind()
        buffer.get(bytes)

        val pixels = IntArray(w * h)
        var out = 0
        for (y in 0 until h) {
            var idx = y * rowStride
            for (x in 0 until w) {
                val r = bytes[idx].toInt() and 0xFF
                val g = bytes[idx + 1].toInt() and 0xFF
                val b = bytes[idx + 2].toInt() and 0xFF
                val a = bytes[idx + 3].toInt() and 0xFF
                pixels[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                idx += pixelStride
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val matrix = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
