package com.oslab.faceos.camera

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.oslab.faceos.infer.InferenceEngine
import com.oslab.faceos.ipc.InferenceResult
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 帧的消费者：双进程模式下是 Binder 客户端，单进程模式下是本地推理引擎 */
interface FrameSink {
    /**
     * @param captureTsNs 相机回调进入分析器的时刻（elapsedRealtimeNanos）
     */
    fun submit(rgba: ByteArray, width: Int, height: Int, rotation: Int, seq: Long, captureTsNs: Long)
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
    @Volatile
    private var smoothFps = 0f

    override fun analyze(image: ImageProxy) {
        Trace.beginSection("FaceOS-camera-callback")
        val captureTsNs = SystemClock.elapsedRealtimeNanos()
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val w = image.width
            val h = image.height
            val scaled = try {
                Trace.beginSection("FaceOS-pack-scale")
                val packed = pack(bytes, w, h, plane.rowStride, plane.pixelStride)
                // Binder 单次事务上限 1MB：640x480 RGBA=1.23MB 会直接抛 TransactionTooLargeException。
                // 因此发送前统一缩放到 480x360（691,200 字节），并记录给服务端的实际帧尺寸。
                scaleToFit(packed, w, h, MAX_SEND_W, MAX_SEND_H)
            } finally {
                Trace.endSection()
            }

            val now = System.nanoTime()
            if (lastTs != 0L) {
                val inst = 1e9f / (now - lastTs).toFloat()
                smoothFps = if (smoothFps == 0f) inst else smoothFps * 0.8f + inst * 0.2f
            }
            lastTs = now

            sink.submit(
                scaled.data, scaled.width, scaled.height,
                image.imageInfo.rotationDegrees, seq.incrementAndGet(), captureTsNs
            )
        } catch (t: Throwable) {
            Log.w("FrameAnalyzer", "frame dropped: ${t.javaClass.simpleName}")
        } finally {
            Trace.endSection()
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

/**
 * 单进程基线模式：在主进程的推理线程上跑 [InferenceEngine]。
 *
 * 注意：必须使用**有界队列 + 丢弃策略**。最初用无界线程池排队，
 * 相机 25fps 而推理仅 ~10fps，每帧 691KB，队列无限增长，
 * 真机运行几分钟后主进程直接 OOM（实测：target footprint 268MB / 256MB 上限）。
 * 这本身也是"系统侧优化：限制队列深度/合理跳帧"的实证。
 */
class LocalFrameSink(
    private val engine: InferenceEngine,
    private val onResult: (InferenceResult) -> Unit
) : FrameSink {

    private val processed = AtomicLong()
    private val dropped = AtomicLong()

    private val pool = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        { r -> Thread(r, "infer-local").apply { isDaemon = true } },
        RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() }
    )

    val processedCount: Long get() = processed.get()
    val droppedCount: Long get() = dropped.get()

    fun reset() {
        processed.set(0)
        dropped.set(0)
    }

    override fun submit(rgba: ByteArray, width: Int, height: Int, rotation: Int, seq: Long, captureTsNs: Long) {
        try {
            val submitTsNs = SystemClock.elapsedRealtimeNanos()
            pool.execute {
                val startNs = SystemClock.elapsedRealtimeNanos()
                Trace.beginSection("FaceOS-infer-local")
                try {
                    onResult(
                        engine.analyze(
                            rgba, width, height, rotation, seq,
                            captureTsNs = captureTsNs,
                            submitTsNs = submitTsNs,
                            serviceRecvTsNs = submitTsNs,
                            inferStartTsNs = startNs
                        )
                    )
                    processed.incrementAndGet()
                } catch (t: Throwable) {
                    Log.w("LocalFrameSink", "inference failed: ${t.javaClass.simpleName}")
                } finally {
                    Trace.endSection()
                }
            }
        } catch (t: Throwable) {
            dropped.incrementAndGet()
        }
    }

    fun shutdown() {
        pool.shutdownNow()
    }
}
