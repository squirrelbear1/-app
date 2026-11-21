package com.oslab.faceos.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.oslab.faceos.ml.FaceDetection
import com.oslab.faceos.ml.ScrfdDetector

/**
 * CameraX 帧分析器：把每一帧转成 Bitmap -> 送 SCRFD 推理 -> 回调结果。
 *
 * 注意：本阶段（阶段 2）所有工作都在同一个分析线程里串行执行，
 * 目的是先把「相机 -> 检测 -> 绘制」链路跑通；
 * 阶段 4 会引入推理线程、阶段 5 会引入双进程 IPC 与 Semaphore 限流。
 */
class FaceAnalyzer(
    private val detector: ScrfdDetector,
    private val onResult: (FrameResult) -> Unit
) : ImageAnalysis.Analyzer {

    data class FrameResult(
        val detections: List<FaceDetection>,
        val imageWidth: Int,
        val imageHeight: Int,
        val inferenceMs: Long,
        val fps: Float,
        val threadName: String
    )

    private var frames = 0L
    private var lastReportTs = 0L
    private var lastFrameTs = 0L
    private var smoothFps = 0f

    override fun analyze(image: ImageProxy) {
        val t0 = System.nanoTime()
        try {
            val raw = image.toRgbaBitmap()
            val upright = rotate(raw, image.imageInfo.rotationDegrees)
            if (upright !== raw) raw.recycle()

            val (detections, inferenceMs) = detector.detect(upright)
            val w = upright.width
            val h = upright.height
            upright.recycle()

            frames++
            val now = System.nanoTime()
            if (lastFrameTs != 0L) {
                val inst = 1e9f / (now - lastFrameTs).toFloat()
                smoothFps = if (smoothFps == 0f) inst else smoothFps * 0.8f + inst * 0.2f
            }
            lastFrameTs = now
            lastReportTs = now

            onResult(
                FrameResult(
                    detections = detections,
                    imageWidth = w,
                    imageHeight = h,
                    inferenceMs = inferenceMs,
                    fps = smoothFps,
                    threadName = Thread.currentThread().name
                )
            )
        } catch (t: Throwable) {
            onResult(
                FrameResult(
                    detections = emptyList(),
                    imageWidth = image.width,
                    imageHeight = image.height,
                    inferenceMs = (System.nanoTime() - t0) / 1_000_000,
                    fps = 0f,
                    threadName = "error: ${t.javaClass.simpleName}"
                )
            )
        } finally {
            image.close()
        }
    }

    /** ImageProxy(RGBA_8888) -> Bitmap，注意 rowStride 可能大于 width*4 */
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
