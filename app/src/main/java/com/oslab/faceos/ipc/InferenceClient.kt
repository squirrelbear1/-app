package com.oslab.faceos.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.oslab.faceos.camera.FrameSink
import com.oslab.faceos.infer.InferenceService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 主进程侧的 Binder 客户端（Messenger 实现）：
 *   - bindService 连接 :infer 推理服务进程；
 *   - submit：oneway 投递 480x360 RGBA 帧（691KB < Binder 1MB 限制），并记录投递时间戳；
 *   - 结果通过 replyTo Messenger 异步回调；
 *   - 每 1s 拉取一次服务端统计（供调试页）；
 *   - 注册 DeathRecipient，服务被杀后自动重连（故障隔离实验）；
 *   - 看门狗：>3s 没有任何结果时回调 [onTimeout]，并累计超时次数。
 */
class InferenceClient(
    private val context: Context,
    private val onResult: (InferenceResult) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
    private val onTimeout: () -> Unit = {}
) : FrameSink {
    companion object {
        private const val TAG = "InferenceClient"
        private const val STATS_INTERVAL_MS = 1000L
        private const val TIMEOUT_MS = 3000L
        private const val PENDING_MAX = 512
        private const val PENDING_TTL_MS = 5000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceMessenger: Messenger? = null
    private var binder: IBinder? = null
    private var isBound = false

    val connected = AtomicBoolean(false)
    val submitted = AtomicLong()
    val received = AtomicLong()
    val reconnects = AtomicLong()
    val timeouts = AtomicLong()

    @Volatile
    var latestStats: StatsSnapshot? = null
        private set

    @Volatile
    var lastResultAt: Long = 0L
        private set

    @Volatile
    private var timeoutReported = false

    private val ipcSum = AtomicLong()
    private val ipcCount = AtomicLong()
    private val pending = ConcurrentHashMap<Long, Long>()
    private val requestId = AtomicInteger(1)
    private val pendingRegisters = ConcurrentHashMap<Int, (Boolean) -> Unit>()
    private val pendingRemoves = ConcurrentHashMap<Int, (Boolean) -> Unit>()
    private val pendingCaptures = ConcurrentHashMap<Int, (Boolean) -> Unit>()

    @Volatile
    private var pendingClear: ((Boolean) -> Unit)? = null

    val avgIpcMs: Long
        get() = if (ipcCount.get() == 0L) 0 else ipcSum.get() / ipcCount.get()

    /** 最近一次结果距今的毫秒数；-1 表示还没有结果 */
    val lastResultAgeMs: Long
        get() = if (lastResultAt == 0L) -1 else SystemClock.elapsedRealtime() - lastResultAt

    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            // 跨进程 Bundle 里的自有 Parcelable 必须显式指定 classLoader，
            // 否则反序列化时默认走 BootClassLoader，抛 ClassNotFoundException
            msg.data?.classLoader = StatsSnapshot::class.java.classLoader
            when (msg.what) {
                Msg.RESULT -> {
                    val r = msg.data?.getParcelable<InferenceResult>(Msg.K_RESULT) ?: return
                    received.incrementAndGet()
                    lastResultAt = SystemClock.elapsedRealtime()
                    timeoutReported = false
                    pending.remove(r.seq)?.let { start ->
                        ipcSum.addAndGet(SystemClock.elapsedRealtime() - start)
                        ipcCount.incrementAndGet()
                    }
                    onResult(r)
                }
                Msg.REGISTER_RESULT -> {
                    val ok = msg.data?.getBoolean(Msg.K_OK) ?: false
                    pendingRegisters.remove(msg.arg1)?.invoke(ok)
                }
                Msg.REMOVE_RESULT -> {
                    val ok = msg.data?.getBoolean(Msg.K_OK) ?: false
                    pendingRemoves.remove(msg.arg1)?.invoke(ok)
                }
                Msg.CAPTURE_RESULT -> {
                    val ok = msg.data?.getBoolean(Msg.K_OK) ?: false
                    pendingCaptures.remove(msg.arg1)?.invoke(ok)
                }
                Msg.CLEAR_RESULT -> {
                    val ok = msg.data?.getBoolean(Msg.K_OK) ?: false
                    val cb = pendingClear
                    pendingClear = null
                    cb?.invoke(ok)
                }
                Msg.STATS -> {
                    latestStats = msg.data?.getParcelable(Msg.K_STATS)
                }
            }
        }
    }

    private val clientMessenger = Messenger(incoming)

    private val statsPoller = object : Runnable {
        override fun run() {
            requestStats()
            if (connected.get()) mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    /** 看门狗：连接正常但 3s 没有结果时，说明服务端卡住/丢帧严重，给 UI 一个明确提示 */
    private val watchdog = object : Runnable {
        override fun run() {
            if (connected.get() && submitted.get() > 0 && lastResultAt != 0L) {
                val age = SystemClock.elapsedRealtime() - lastResultAt
                if (age > TIMEOUT_MS && !timeoutReported) {
                    timeoutReported = true
                    timeouts.incrementAndGet()
                    onTimeout()
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "推理服务进程意外终止（binder died）")
        serviceMessenger = null
        connected.set(false)
        pending.clear()
        mainHandler.post {
            onConnectionChanged(false)
            if (isBound) {
                reconnects.incrementAndGet()
                mainHandler.postDelayed({ if (!connected.get()) doBind() }, 500)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            if (b == null) return
            binder = b
            try {
                b.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                Log.e(TAG, "linkToDeath failed", e)
                return
            }
            serviceMessenger = Messenger(b)
            connected.set(true)
            lastResultAt = SystemClock.elapsedRealtime()
            timeoutReported = false
            // 把自己的 Messenger 交给服务端作为回调通道
            val hello = Message.obtain(null, Msg.SET_CALLBACK).apply { replyTo = clientMessenger }
            try {
                serviceMessenger?.send(hello)
            } catch (e: RemoteException) {
                Log.e(TAG, "set callback failed", e)
            }
            mainHandler.post {
                onConnectionChanged(true)
                mainHandler.removeCallbacks(statsPoller)
                mainHandler.post(statsPoller)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            connected.set(false)
            mainHandler.removeCallbacks(statsPoller)
            mainHandler.post { onConnectionChanged(false) }
        }
    }

    fun bind() {
        isBound = true
        doBind()
        mainHandler.removeCallbacks(watchdog)
        mainHandler.post(watchdog)
    }

    private fun doBind() {
        try {
            val intent = Intent(context, InferenceService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed", e)
        }
    }

    fun unbind() {
        isBound = false
        mainHandler.removeCallbacks(statsPoller)
        mainHandler.removeCallbacks(watchdog)
        try {
            binder?.unlinkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            // ignore
        }
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            // ignore
        }
        serviceMessenger = null
        connected.set(false)
        pending.clear()
    }

    override fun submit(rgba: ByteArray, width: Int, height: Int, rotation: Int, seq: Long, captureTsNs: Long) {
        val m = serviceMessenger ?: return
        submitted.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        pending[seq] = now
        prunePending(now)
        val submitTsNs = SystemClock.elapsedRealtimeNanos()
        val msg = Message.obtain(null, Msg.SUBMIT).apply {
            data = Bundle().apply {
                putByteArray(Msg.K_RGBA, rgba)
                putInt(Msg.K_W, width)
                putInt(Msg.K_H, height)
                putInt(Msg.K_ROT, rotation)
                putLong(Msg.K_SEQ, seq)
                putLong(Msg.K_CAP_TS, captureTsNs)
                putLong(Msg.K_SUB_TS, submitTsNs)
            }
            replyTo = clientMessenger
        }
        Trace.beginSection("FaceOS-ipc-submit")
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pending.remove(seq)
            Log.w(TAG, "submit failed: ${e.message}")
        } finally {
            Trace.endSection()
        }
    }

    /** 丢弃超过 5s 仍未返回的 pending 记录，防止丢帧场景下 map 无限增长 */
    private fun prunePending(nowMs: Long) {
        if (pending.size <= PENDING_MAX) return
        val cutoff = nowMs - PENDING_TTL_MS
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove()
        }
    }

    /**
     * 注册第一步：让服务端冻结 UI 看到的那一帧的特征/活体。
     * 之后 registerFace() 只使用这份快照。
     */
    fun captureRegistration(seq: Long, onDone: (Boolean) -> Unit) {
        val id = requestId.incrementAndGet()
        pendingCaptures[id] = onDone
        val m = serviceMessenger
        if (m == null) {
            pendingCaptures.remove(id)
            onDone(false)
            return
        }
        val msg = Message.obtain(null, Msg.CAPTURE).apply {
            arg1 = id
            data = Bundle().apply { putLong(Msg.K_SEQ, seq) }
            replyTo = clientMessenger
        }
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pendingCaptures.remove(id)
            onDone(false)
        }
    }

    /** 用户取消注册对话框：清掉服务端冻结的快照 */
    fun cancelCapture() {
        sendArg(Msg.CANCEL_CAPTURE, 0)
    }

    fun registerFace(name: String, onDone: (Boolean) -> Unit) {
        val id = requestId.incrementAndGet()
        pendingRegisters[id] = onDone
        val m = serviceMessenger
        if (m == null) {
            pendingRegisters.remove(id)
            onDone(false)
            return
        }
        val msg = Message.obtain(null, Msg.REGISTER).apply {
            arg1 = id
            data = Bundle().apply { putString(Msg.K_NAME, name) }
            replyTo = clientMessenger
        }
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pendingRegisters.remove(id)
            onDone(false)
        }
    }

    fun removeFace(name: String, onDone: (Boolean) -> Unit) {
        val id = requestId.incrementAndGet()
        pendingRemoves[id] = onDone
        val m = serviceMessenger
        if (m == null) {
            pendingRemoves.remove(id)
            onDone(false)
            return
        }
        val msg = Message.obtain(null, Msg.REMOVE).apply {
            arg1 = id
            data = Bundle().apply { putString(Msg.K_NAME, name) }
            replyTo = clientMessenger
        }
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pendingRemoves.remove(id)
            onDone(false)
        }
    }

    fun clearFaces(onDone: (Boolean) -> Unit) {
        pendingClear = onDone
        val m = serviceMessenger
        if (m == null) {
            pendingClear = null
            onDone(false)
            return
        }
        val msg = Message.obtain(null, Msg.CLEAR).apply {
            arg1 = requestId.incrementAndGet()
            replyTo = clientMessenger
        }
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pendingClear = null
            onDone(false)
        }
    }

    fun galleryCount(): Int = latestStats?.galleryCount ?: 0

    fun galleryNames(): List<String> = latestStats?.galleryNames?.toList() ?: emptyList()

    fun setWorkers(n: Int) = sendArg(Msg.SET_WORKERS, n)

    fun setPermits(n: Int) = sendArg(Msg.SET_PERMITS, n)

    fun setDetSize(size: Int) = sendArg(Msg.SET_DET_SIZE, size)

    fun killService() {
        val m = serviceMessenger ?: return
        try {
            m.send(Message.obtain(null, Msg.KILL))
        } catch (e: RemoteException) {
            // ignore
        }
    }

    /** 重置客户端 + 服务端统计（每组实验开始前调用，避免跨组污染） */
    fun resetStats() {
        submitted.set(0)
        received.set(0)
        timeouts.set(0)
        ipcSum.set(0)
        ipcCount.set(0)
        pending.clear()
        lastResultAt = SystemClock.elapsedRealtime()
        timeoutReported = false
        sendArg(Msg.RESET_STATS, 0)
    }

    fun setVariant(variant: String) {
        val m = serviceMessenger ?: return
        try {
            m.send(Message.obtain(null, Msg.SET_VARIANT).apply { data = Bundle().apply { putString(Msg.K_VARIANT, variant) } })
        } catch (e: RemoteException) {
            // ignore
        }
    }

    private fun sendArg(what: Int, value: Int) {
        val m = serviceMessenger ?: return
        try {
            m.send(Message.obtain(null, what).apply { arg1 = value })
        } catch (e: RemoteException) {
            // ignore
        }
    }

    private fun requestStats() {
        val m = serviceMessenger ?: return
        try {
            m.send(Message.obtain(null, Msg.GET_STATS).apply { replyTo = clientMessenger })
        } catch (e: RemoteException) {
            // ignore
        }
    }
}
