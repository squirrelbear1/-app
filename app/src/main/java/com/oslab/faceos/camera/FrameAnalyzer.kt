package com.oslab.faceos.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.oslab.faceos.infer.InferenceEngine
import com.oslab.faceos.ipc.InferenceResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

/** 帧的消费者：双进程模式下是 Binder 客户端，单进程模式下是本地推理引擎 */
interface FrameSink {
    fun submit(rgba: ByteArray, width: Int, height: Int, rotation: Int, seq: Long)
}

/**
 * CameraX 分析器（生产者端）：
 *   ImageProxy(RGBA_8888) -> 紧凑 byte[] -> 交给 [FrameSink]
 *
 * 注意：CameraX 的 RGBA 输出可能有 rowStride 填充，必须按行压紧后再跨进程传输，
 * 否则服务端按 width*4 解析会花屏。
 */
class FrameAnalyzer(
    private val sink: FrameSink
) : ImageAnalysis.Analyzer {

    private val seq = AtomicLong()
    private var lastTs = 0L
    private var smoothFps = 0f

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val w = image.width
            val h = image.height
            val packed = pack(bytes, w, h, plane.rowStride, plane.pixelStride)

            // Binder 单次事务上限 1MB：640x480 RGBA=1.23MB 会直接抛 TransactionTooLargeException。
            // 因此发送前统一缩放到 480x360（691,200 字节），并记录给服务端的实际帧尺寸。
            val scaled = scaleToFit(packed, w, h, MAX_SEND_W, MAX_SEND_H)

            val now = System.nanoTime()
            if (lastTs != 0L) {
                val inst = 1e9f / (now - lastTs).toFloat()
                smoothFps = if (smoothFps == 0f) inst else smoothFps * 0.8f + inst * 0.2f
            }
            lastTs = now

            sink.submit(scaled.data, scaled.width, scaled.height, image.imageInfo.rotationDegrees, seq.incrementAndGet())
        } catch (t: Throwable) {
            Log.w("FrameAnalyzer", "frame dropped: ${t.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    val fps: Float get() = smoothFps

    private class ScaledFrame(val data: ByteArray, val width: Int, val height: Int)

    /** 最近邻缩放，保证宽高不超过 maxW/maxH（RGBA 4 字节/像素） */
    private fun scaleToFit(src: ByteArray, w: Int, h: Int, maxW: Int, maxH: Int): ScaledFrame {
        if (w <= maxW && h <= maxH) return ScaledFrame(src, w, h)
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val out = ByteArray(nw * nh * 4)
        var dst = 0
        for (y in 0 until nh) {
            val sy = (y / scale).toInt().coerceIn(0, h - 1)
            val row = sy * w
            for (x in 0 until nw) {
                val sx = (x / scale).toInt().coerceIn(0, w - 1)
                val s = (row + sx) * 4
                out[dst++] = src[s]
                out[dst++] = src[s + 1]
                out[dst++] = src[s + 2]
                out[dst++] = src[s + 3]
            }
        }
        return ScaledFrame(out, nw, nh)
    }

    companion object {
        /** 跨进程传帧的最大尺寸：480*360*4 = 691,200 B < Binder 1 MB */
        private const val MAX_SEND_W = 480
        private const val MAX_SEND_H = 360
    }

    private fun pack(src: ByteArray, w: Int, h: Int, rowStride: Int, pixelStride: Int): ByteArray {
        if (rowStride == w * 4 && pixelStride == 4) return src
        val out = ByteArray(w * h * 4)
        var dst = 0
        for (y in 0 until h) {
            var s = y * rowStride
            for (x in 0 until w) {
                out[dst++] = src[s]
                out[dst++] = src[s + 1]
                out[dst++] = src[s + 2]
                out[dst++] = src[s + 3]
                s += pixelStride
            }
        }
        return out
    }
}

/** 单进程基线模式：直接在主进程的推理线程上跑 [InferenceEngine] */
class LocalFrameSink(
    private val engine: InferenceEngine,
    private val executor: ExecutorService,
    private val onResult: (InferenceResult) -> Unit
) : FrameSink {
    override fun submit(rgba: ByteArray, width: Int, height: Int, rotation: Int, seq: Long) {
        executor.execute {
            try {
                onResult(engine.analyze(rgba, width, height, rotation, seq))
            } catch (t: Throwable) {
                Log.w("LocalFrameSink", "inference failed: ${t.javaClass.simpleName}")
            }
        }
    }
}
