package com.oslab.faceos.infer

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.oslab.faceos.ipc.Msg
import com.oslab.faceos.ipc.StatsSnapshot
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 独立推理服务进程（android:process=":infer"），基于 Messenger（Binder）实现 IPC。
 *
 * 生产者—消费者模型：
 *   - 生产者：主进程相机线程通过 MSG_SUBMIT 投递 RGBA 帧；
 *   - 缓冲：有界队列 ArrayBlockingQueue(8)；
 *   - 消费者：推理线程池（1/2/4 可调）；
 *   - 限流：AdjustableSemaphore(permits) 限制在途推理任务，拿不到许可直接丢帧（背压）；
 *   - 异常路径（推理抛错 / 线程池拒绝）必须 release 许可，避免许可泄漏。
 *
 * 动态状态：队列长度、可用许可、在途任务、已处理/丢弃/异常帧、各阶段平均与 P50/P95 耗时。
 */
class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        private const val QUEUE_CAPACITY = 8
        private const val DEFAULT_WORKERS = 2
        private const val DEFAULT_PERMITS = 2

        /** P50/P95 的滑动窗口帧数 */
        private const val LATENCY_WINDOW = 256
    }

    private lateinit var engine: InferenceEngine

    @Volatile
    private var engineReady = false

    @Volatile
    private var loadError = ""

    @Volatile
    private var workers = DEFAULT_WORKERS

    @Volatile
    private var permitsTotal = DEFAULT_PERMITS

    private lateinit var semaphore: AdjustableSemaphore
    private lateinit var queue: ArrayBlockingQueue<FrameTask>
    private lateinit var pool: ThreadPoolExecutor
    private lateinit var dispatcher: Thread

    @Volatile
    private var running = true

    @Volatile
    private var clientCallback: Messenger? = null

    private val processed = AtomicLong()
    private val dropped = AtomicLong()
    private val errors = AtomicLong()
    private val sumDet = AtomicLong()
    private val sumLive = AtomicLong()
    private val sumRec = AtomicLong()
    private val sumTotal = AtomicLong()

    // ---- 最近 LATENCY_WINDOW 帧的耗时窗口，用于 P50/P95 ----
    private val latLock = Any()
    private val detWindow = java.util.ArrayDeque<Long>()
    private val totalWindow = java.util.ArrayDeque<Long>()

    // ---- 服务进程 CPU 占用（两次 snapshot 差分）----
    private var lastCpuTimeMs = -1L
    private var lastCpuAtMs = 0L

    @Volatile
    private var cpuPct = -1f

    // ---- 线程名缓存：Thread.getAllStackTraces 代价高，5 s 更新一次即可 ----
    @Volatile
    private var cachedInferThreads: Array<String> = emptyArray()
    @Volatile
    private var cachedThreadsAtMs = 0L

    private data class FrameTask(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val seq: Long,
        val captureTsNs: Long,
        val submitTsNs: Long,
        val recvTsNs: Long
    )

    /**
     * 可动态调整总量的 Semaphore。
     *
     * 不能在切换许可时直接 `semaphore = Semaphore(n)`：在途任务结束时会把许可
     * release 到新对象上，导致可用许可超过总量（越切换越多）。这里始终复用同一对象，
     * 用 release / reducePermits 只调整差额。
     */
    private class AdjustableSemaphore(permits: Int) : Semaphore(permits) {
        fun adjust(delta: Int) {
            if (delta > 0) {
                release(delta)
            } else if (delta < 0) {
                reducePermits(-delta)
            }
        }
    }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Msg.SUBMIT -> onFrame(msg)
                Msg.SET_CALLBACK -> clientCallback = msg.replyTo
                Msg.REGISTER -> onRegister(msg)
                Msg.REMOVE -> onRemove(msg)
                Msg.CLEAR -> onClear(msg)
                Msg.GET_STATS -> reply(msg.replyTo ?: clientCallback, Msg.STATS, statsBundle())
                Msg.SET_WORKERS -> setWorkers(msg.arg1)
                Msg.SET_PERMITS -> setPermits(msg.arg1)
                Msg.RESET_STATS -> resetStats()
                Msg.SET_DET_SIZE -> {
                    if (!engineReady) return
                    if (msg.arg1 == engine.detInputSize) return
                    val ok = engine.reloadDetSize(msg.arg1)
                    if (ok) {
                        resetStats()
                        Log.i(TAG, "det input size -> ${msg.arg1}")
                    }
                }
                Msg.SET_VARIANT -> {
                    if (!engineReady) return
                    val v = msg.data?.getString(Msg.K_VARIANT) ?: return
                    if (v == engine.variant) return
                    val ok = engine.reload(v)
                    if (ok) {
                        resetStats()
                        Log.i(TAG, "switched model variant -> $v")
                    } else {
                        Log.w(TAG, "switch variant failed: $v")
                    }
                }
                Msg.KILL -> {
                    // 故障隔离演示：让服务进程真的死掉，主进程的 DeathRecipient 会收到 binderDied
                    Log.w(TAG, "收到 MSG_KILL，主动终止服务进程 pid=${Process.myPid()}")
                    Process.killProcess(Process.myPid())
                }
                else -> super.handleMessage(msg)
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        queue = ArrayBlockingQueue(QUEUE_CAPACITY)
        semaphore = AdjustableSemaphore(permitsTotal)
        pool = newPool(workers)
        try {
            engine = InferenceEngine(this)
            engineReady = true
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}".take(180)
            Log.e(TAG, "模型加载失败，服务继续存活但拒绝推理：$loadError", t)
        }
        dispatcher = Thread({ dispatchLoop() }, "infer-dispatch").apply {
            isDaemon = true
            start()
        }
        Log.i(
            TAG,
            "service created, pid=${Process.myPid()}, ready=$engineReady, load=${if (engineReady) engine.loadTimeMs else -1}ms"
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        running = false
        dispatcher.interrupt()
        pool.shutdownNow()
        // close() 内部持写锁，会等待正在 analyze 的任务结束，避免关闭正在使用的 session
        if (engineReady) {
            try {
                engine.close()
            } catch (t: Throwable) {
                Log.w(TAG, "engine close failed: ${t.message}")
            }
        }
        super.onDestroy()
    }

    // ---------------- 消息处理 ----------------

    private fun onFrame(msg: Message) {
        if (!engineReady) {
            dropped.incrementAndGet()
            return
        }
        val data = msg.data ?: return
        val rgba = data.getByteArray(Msg.K_RGBA) ?: return
        val task = FrameTask(
            data = rgba,
            width = data.getInt(Msg.K_W),
            height = data.getInt(Msg.K_H),
            rotation = data.getInt(Msg.K_ROT),
            seq = data.getLong(Msg.K_SEQ),
            captureTsNs = data.getLong(Msg.K_CAP_TS, 0L),
            submitTsNs = data.getLong(Msg.K_SUB_TS, 0L),
            recvTsNs = SystemClock.elapsedRealtimeNanos()
        )
        if (!queue.offer(task)) {
            dropped.incrementAndGet()
        }
    }

    private fun onRegister(msg: Message) {
        val name = msg.data?.getString(Msg.K_NAME) ?: ""
        val ok = if (engineReady) engine.register(name) else false
        val out = Bundle().apply {
            putBoolean(Msg.K_OK, ok)
            putString(Msg.K_NAME, name)
        }
        // 必须回传 arg1（请求号），否则客户端无法把结果对应回 pendingRegisters
        reply(msg.replyTo ?: clientCallback, Msg.REGISTER_RESULT, out, msg.arg1)
    }

    private fun onRemove(msg: Message) {
        val name = msg.data?.getString(Msg.K_NAME) ?: ""
        val ok = if (engineReady) engine.remove(name) else false
        val out = Bundle().apply {
            putBoolean(Msg.K_OK, ok)
            putString(Msg.K_NAME, name)
        }
        reply(msg.replyTo ?: clientCallback, Msg.REMOVE_RESULT, out, msg.arg1)
    }

    private fun onClear(msg: Message) {
        val ok = if (engineReady) engine.clearGallery() else false
        val out = Bundle().apply { putBoolean(Msg.K_OK, ok) }
        reply(msg.replyTo ?: clientCallback, Msg.CLEAR_RESULT, out, msg.arg1)
    }

    private fun reply(target: Messenger?, what: Int, data: Bundle, arg1: Int = 0) {
        val t = target ?: return
        try {
            t.send(Message.obtain(null, what, arg1, 0).apply { this.data = data })
        } catch (e: RemoteException) {
            // 客户端已退出
        }
    }

    // ---------------- 调度 ----------------

    private fun newPool(size: Int): ThreadPoolExecutor {
        val counter = java.util.concurrent.atomic.AtomicInteger(1)
        return ThreadPoolExecutor(
            size, size, 30, TimeUnit.SECONDS, LinkedBlockingQueue(),
            { r -> Thread(r, "infer-worker-${counter.getAndIncrement()}").apply { isDaemon = true } }
        )
    }

    private fun dispatchLoop() {
        while (running) {
            val task = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }
            if (!semaphore.tryAcquire()) {
                // 在途任务已达许可上限：丢帧（背压），保证延迟不让队列无限堆积
                dropped.incrementAndGet()
                continue
            }
            try {
                pool.execute {
                    Trace.beginSection("FaceOS-infer")
                    val startNs = SystemClock.elapsedRealtimeNanos()
                    try {
                        if (!engineReady) {
                            dropped.incrementAndGet()
                        } else {
                            val result = engine.analyze(
                                task.data, task.width, task.height, task.rotation, task.seq,
                                captureTsNs = task.captureTsNs,
                                submitTsNs = task.submitTsNs,
                                serviceRecvTsNs = task.recvTsNs,
                                inferStartTsNs = startNs
                            )
                            processed.incrementAndGet()
                            sumDet.addAndGet(result.detMs)
                            sumLive.addAndGet(result.liveMs)
                            sumRec.addAndGet(result.recMs)
                            sumTotal.addAndGet(result.totalMs)
                            recordLatency(result.detMs, result.totalMs)
                            val cb = clientCallback
                            if (cb != null) {
                                try {
                                    cb.send(
                                        Message.obtain(null, Msg.RESULT).apply {
                                            data = Bundle().apply { putParcelable(Msg.K_RESULT, result) }
                                        }
                                    )
                                } catch (e: RemoteException) {
                                    // 客户端断开
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        errors.incrementAndGet()
                        Log.w(TAG, "inference failed: ${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        Trace.endSection()
                        semaphore.release()
                    }
                }
            } catch (e: Throwable) {
                semaphore.release()
                dropped.incrementAndGet()
            }
        }
    }

    // ---------------- 参数与统计 ----------------

    private fun setWorkers(n: Int) {
        val count = n.coerceIn(1, 4)
        if (count == workers) return
        workers = count
        val old = pool
        pool = newPool(count)
        old.shutdown()
    }

    @Synchronized
    private fun setPermits(n: Int) {
        val target = n.coerceIn(1, 4)
        val delta = target - permitsTotal
        if (delta == 0) return
        permitsTotal = target
        semaphore.adjust(delta)
    }

    private fun resetStats() {
        processed.set(0)
        dropped.set(0)
        errors.set(0)
        sumDet.set(0)
        sumLive.set(0)
        sumRec.set(0)
        sumTotal.set(0)
        synchronized(latLock) {
            detWindow.clear()
            totalWindow.clear()
        }
    }

    private fun recordLatency(detMs: Long, totalMs: Long) {
        synchronized(latLock) {
            detWindow.addLast(detMs)
            totalWindow.addLast(totalMs)
            while (detWindow.size > LATENCY_WINDOW) detWindow.removeFirst()
            while (totalWindow.size > LATENCY_WINDOW) totalWindow.removeFirst()
        }
    }

    private fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun snapshot(): StatsSnapshot {
        val cnt = processed.get().coerceAtLeast(1)
        val detList = synchronized(latLock) { detWindow.toList() }
        val totalList = synchronized(latLock) { totalWindow.toList() }

        // 服务进程自身 CPU 占用：用累计 CPU 时间做差分（/proc 被 SELinux 限制时也能工作）
        val cpuMs = Process.getElapsedCpuTime()
        val nowMs = SystemClock.elapsedRealtime()
        if (lastCpuTimeMs >= 0 && nowMs > lastCpuAtMs) {
            val dtSec = (nowMs - lastCpuAtMs) / 1000.0
            if (dtSec > 0.2) {
                // cpuMs 是毫秒：CPU% = (ΔcpuMs / 1000s) / Δt * 100 = ΔcpuMs / (10 * Δt)
                cpuPct = ((cpuMs - lastCpuTimeMs) / (dtSec * 10.0)).toFloat().coerceAtLeast(0f)
            }
        }
        lastCpuTimeMs = cpuMs
        lastCpuAtMs = nowMs

        val inferThreads = if (nowMs - cachedThreadsAtMs > 5000 || cachedInferThreads.isEmpty()) {
            cachedInferThreads = try {
                Thread.getAllStackTraces().keys
                    .filter { it.isAlive && it.name.startsWith("infer-") }
                    .map { it.name }
                    .sorted()
                    .toTypedArray()
            } catch (t: Throwable) {
                emptyArray()
            }
            cachedThreadsAtMs = nowMs
            cachedInferThreads
        } else {
            cachedInferThreads
        }
        return StatsSnapshot(
            servicePid = Process.myPid(),
            workers = workers,
            permitsTotal = permitsTotal,
            permitsAvailable = semaphore.availablePermits(),
            inFlight = (permitsTotal - semaphore.availablePermits()).coerceAtLeast(0),
            queueLength = queue.size,
            queueCapacity = QUEUE_CAPACITY,
            processed = processed.get(),
            dropped = dropped.get(),
            errors = errors.get(),
            avgDetMs = sumDet.get() / cnt,
            avgLiveMs = sumLive.get() / cnt,
            avgRecMs = sumRec.get() / cnt,
            avgTotalMs = sumTotal.get() / cnt,
            activeThreads = Thread.activeCount(),
            variant = if (engineReady) engine.variant else "load-failed",
            detInputSize = if (engineReady) engine.detInputSize else 0,
            galleryCount = if (engineReady) engine.gallery.size() else 0,
            threadNames = inferThreads,
            p50DetMs = percentile(detList, 0.50),
            p95DetMs = percentile(detList, 0.95),
            p50TotalMs = percentile(totalList, 0.50),
            p95TotalMs = percentile(totalList, 0.95),
            sampleCount = detList.size,
            galleryNames = if (engineReady) engine.gallery.names().take(32).toTypedArray() else emptyArray(),
            modelError = loadError,
            cpuTimeMs = cpuMs,
            cpuPct = cpuPct
        )
    }

    private fun statsBundle(): Bundle = Bundle().apply {
        putParcelable(Msg.K_STATS, snapshot())
    }
}
