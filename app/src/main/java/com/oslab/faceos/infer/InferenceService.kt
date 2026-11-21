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
 *   - 限流：Semaphore(permits) 限制在途推理任务，拿不到许可直接丢帧（背压）；
 *   - 异常路径（推理抛错 / 线程池拒绝）必须 release 许可，避免许可泄漏。
 *
 * 统计：队列长度、可用许可、在途任务、已处理/丢弃帧、各阶段平均耗时、PID、线程名。
 */
class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        private const val QUEUE_CAPACITY = 8
        private const val DEFAULT_WORKERS = 2
        private const val DEFAULT_PERMITS = 2
    }

    private lateinit var engine: InferenceEngine

    @Volatile
    private var workers = DEFAULT_WORKERS

    @Volatile
    private var permitsTotal = DEFAULT_PERMITS

    private lateinit var semaphore: Semaphore
    private lateinit var queue: ArrayBlockingQueue<FrameTask>
    private lateinit var pool: ThreadPoolExecutor
    private lateinit var dispatcher: Thread

    @Volatile
    private var running = true

    @Volatile
    private var clientCallback: Messenger? = null

    private val processed = AtomicLong()
    private val dropped = AtomicLong()
    private val sumDet = AtomicLong()
    private val sumLive = AtomicLong()
    private val sumRec = AtomicLong()
    private val sumTotal = AtomicLong()

    private data class FrameTask(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val seq: Long
    )

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Msg.SUBMIT -> onFrame(msg)
                Msg.SET_CALLBACK -> clientCallback = msg.replyTo
                Msg.REGISTER -> onRegister(msg)
                Msg.CLEAR -> onClear(msg)
                Msg.GET_STATS -> reply(msg.replyTo ?: clientCallback, Msg.STATS, statsBundle())
                Msg.SET_WORKERS -> setWorkers(msg.arg1)
                Msg.SET_PERMITS -> setPermits(msg.arg1)
                Msg.RESET_STATS -> resetStats()
                else -> super.handleMessage(msg)
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        engine = InferenceEngine(this)
        queue = ArrayBlockingQueue(QUEUE_CAPACITY)
        semaphore = Semaphore(permitsTotal)
        pool = newPool(workers)
        dispatcher = Thread({ dispatchLoop() }, "infer-dispatch").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "service created, pid=${Process.myPid()}, load=${engine.loadTimeMs}ms")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        running = false
        dispatcher.interrupt()
        pool.shutdownNow()
        engine.close()
        super.onDestroy()
    }

    // ---------------- 消息处理 ----------------

    private fun onFrame(msg: Message) {
        val data = msg.data ?: return
        val rgba = data.getByteArray(Msg.K_RGBA) ?: return
        val task = FrameTask(
            data = rgba,
            width = data.getInt(Msg.K_W),
            height = data.getInt(Msg.K_H),
            rotation = data.getInt(Msg.K_ROT),
            seq = data.getLong(Msg.K_SEQ)
        )
        if (!queue.offer(task)) {
            dropped.incrementAndGet()
        }
    }

    private fun onRegister(msg: Message) {
        val name = msg.data?.getString(Msg.K_NAME) ?: ""
        val ok = engine.register(name)
        val out = Bundle().apply {
            putBoolean(Msg.K_OK, ok)
            putString(Msg.K_NAME, name)
        }
        reply(msg.replyTo ?: clientCallback, Msg.REGISTER_RESULT, out)
    }

    private fun onClear(msg: Message) {
        val ok = engine.clearGallery()
        val out = Bundle().apply { putBoolean(Msg.K_OK, ok) }
        reply(msg.replyTo ?: clientCallback, Msg.CLEAR_RESULT, out)
    }

    private fun reply(target: Messenger?, what: Int, data: Bundle) {
        val t = target ?: return
        try {
            t.send(Message.obtain(null, what).apply { this.data = data })
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
                    try {
                        val result = engine.analyze(task.data, task.width, task.height, task.rotation, task.seq)
                        processed.incrementAndGet()
                        sumDet.addAndGet(result.detMs)
                        sumLive.addAndGet(result.liveMs)
                        sumRec.addAndGet(result.recMs)
                        sumTotal.addAndGet(result.totalMs)
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
                    } catch (t: Throwable) {
                        // 异常路径必须释放许可
                    } finally {
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

    private fun setPermits(n: Int) {
        permitsTotal = n.coerceIn(1, 4)
        semaphore = Semaphore(permitsTotal)
    }

    private fun resetStats() {
        processed.set(0)
        dropped.set(0)
        sumDet.set(0)
        sumLive.set(0)
        sumRec.set(0)
        sumTotal.set(0)
    }

    private fun snapshot(): StatsSnapshot {
        val cnt = processed.get().coerceAtLeast(1)
        val inferThreads = try {
            Thread.getAllStackTraces().keys
                .filter { it.isAlive && it.name.startsWith("infer-") }
                .map { it.name }
                .sorted()
                .toTypedArray()
        } catch (t: Throwable) {
            emptyArray()
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
            avgDetMs = sumDet.get() / cnt,
            avgLiveMs = sumLive.get() / cnt,
            avgRecMs = sumRec.get() / cnt,
            avgTotalMs = sumTotal.get() / cnt,
            activeThreads = Thread.activeCount(),
            galleryCount = engine.gallery.size(),
            threadNames = inferThreads
        )
    }

    private fun statsBundle(): Bundle = Bundle().apply {
        putParcelable(Msg.K_STATS, snapshot())
    }
}
