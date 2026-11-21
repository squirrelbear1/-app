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
 *   - submit：oneway 投递 480x360 RGBA 帧（691KB < Binder 1MB 限制）；
 *   - 结果通过 replyTo Messenger 异步回调；
 *   - 每 1s 拉取一次服务端统计（供调试页）；
 *   - 注册 DeathRecipient，服务被杀后自动重连（故障隔离实验）。
 */
class InferenceClient(
    private val context: Context,
    private val onResult: (InferenceResult) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {}
) : FrameSink {
    companion object {
        private const val TAG = "InferenceClient"
        private const val STATS_INTERVAL_MS = 1000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceMessenger: Messenger? = null
    private var binder: IBinder? = null
    private var isBound = false

    val connected = AtomicBoolean(false)
    val submitted = AtomicLong()
    val received = AtomicLong()
    val reconnects = AtomicLong()

    @Volatile
    var latestStats: StatsSnapshot? = null
        private set

    private val ipcSum = AtomicLong()
    private val ipcCount = AtomicLong()
    private val pending = ConcurrentHashMap<Long, Long>()
    private val requestId = AtomicInteger(1)
    private val pendingRegisters = ConcurrentHashMap<Int, (Boolean) -> Unit>()

    @Volatile
    private var pendingClear: ((Boolean) -> Unit)? = null

    val avgIpcMs: Long
        get() = if (ipcCount.get() == 0L) 0 else ipcSum.get() / ipcCount.get()

    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Msg.RESULT -> {
                    val r = msg.data?.getParcelable<InferenceResult>(Msg.K_RESULT) ?: return
                    received.incrementAndGet()
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

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "推理服务进程意外终止（binder died）")
        serviceMessenger = null
        connected.set(false)
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
    }

    override fun submit(rgba: ByteArray, width: Int, height: Int, rotation: Int, seq: Long) {
        val m = serviceMessenger ?: return
        submitted.incrementAndGet()
        pending[seq] = SystemClock.elapsedRealtime()
        val msg = Message.obtain(null, Msg.SUBMIT).apply {
            data = Bundle().apply {
                putByteArray(Msg.K_RGBA, rgba)
                putInt(Msg.K_W, width)
                putInt(Msg.K_H, height)
                putInt(Msg.K_ROT, rotation)
                putLong(Msg.K_SEQ, seq)
            }
            replyTo = clientMessenger
        }
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pending.remove(seq)
            Log.w(TAG, "submit failed: ${e.message}")
        }
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

    fun clearFaces(onDone: (Boolean) -> Unit) {
        pendingClear = onDone
        val m = serviceMessenger
        if (m == null) {
            pendingClear = null
            onDone(false)
            return
        }
        val msg = Message.obtain(null, Msg.CLEAR).apply { replyTo = clientMessenger }
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pendingClear = null
            onDone(false)
        }
    }

    fun galleryCount(): Int = latestStats?.galleryCount ?: 0

    fun setWorkers(n: Int) = sendArg(Msg.SET_WORKERS, n)

    fun setPermits(n: Int) = sendArg(Msg.SET_PERMITS, n)

    fun resetStats() = sendArg(Msg.RESET_STATS, 0)

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
