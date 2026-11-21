package com.oslab.faceos

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.oslab.faceos.databinding.ActivityDebugBinding
import com.oslab.faceos.debug.DebugHub

/**
 * 系统运行状态 / 调试页（实验要求 2.5）：
 *   展示进程、线程、IPC 请求与时延、信号量许可、在途任务、队列、丢帧、各阶段耗时。
 *   并提供线程数 / 许可数切换按钮，用于 CPU 调度与背压对照实验。
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            render(DebugHub.provider?.invoke())
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnWorkers1.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(1) }
        binding.btnWorkers2.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(2) }
        binding.btnWorkers4.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(4) }
        binding.btnPermits1.setOnClickListener { DebugHub.actions?.setPermits?.invoke(1) }
        binding.btnPermits2.setOnClickListener { DebugHub.actions?.setPermits?.invoke(2) }
        binding.btnPermits4.setOnClickListener { DebugHub.actions?.setPermits?.invoke(4) }
        binding.btnReset.setOnClickListener { DebugHub.actions?.resetStats?.invoke() }
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun render(s: DebugHub.Snapshot?) {
        if (s == null) {
            binding.tvDebug.text = "调试数据未就绪：请从主界面进入。"
            return
        }
        val svc = s.service
        val sb = StringBuilder()
        sb.appendLine("════ 进程 ════")
        sb.appendLine("模式        : ${s.mode}")
        sb.appendLine("主进程 PID  : ${s.mainPid}")
        sb.appendLine("服务进程 PID: ${if (svc != null && svc.servicePid != 0) svc.servicePid else "—（未连接）"}")
        sb.appendLine("服务连接状态: ${if (s.connected) "已连接" else "断开/重连中"}")
        sb.appendLine("IPC 重连次数: ${s.reconnects}")
        sb.appendLine()
        sb.appendLine("════ IPC ════")
        sb.appendLine("投递帧数    : ${s.submitted}")
        sb.appendLine("收到结果数  : ${s.received}")
        sb.appendLine("平均 IPC 时延: ${s.avgIpcMs} ms  (submit → 结果回调)")
        sb.appendLine()
        sb.appendLine("════ 服务进程调度 ════")
        if (svc != null) {
            sb.appendLine("工作线程数  : ${svc.workers}   (线程池: ${svc.threadNames.joinToString()})")
            sb.appendLine("信号量许可  : 可用 ${svc.permitsAvailable}/${svc.permitsTotal}   在途 ${svc.inFlight}")
            sb.appendLine("队列        : ${svc.queueLength}/${svc.queueCapacity}")
            sb.appendLine("已处理帧数  : ${svc.processed}")
            sb.appendLine("被丢弃帧数  : ${svc.dropped}")
            sb.appendLine()
            sb.appendLine("════ 各阶段耗时（平均）════")
            sb.appendLine("检测 SCRFD      : ${svc.avgDetMs} ms")
            sb.appendLine("活体 MiniFASNet : ${svc.avgLiveMs} ms")
            sb.appendLine("识别 EdgeFace   : ${svc.avgRecMs} ms")
            sb.appendLine("单帧总耗时      : ${svc.avgTotalMs} ms")
            sb.appendLine("服务进程线程数  : ${svc.activeThreads}")
        } else {
            sb.appendLine("（单进程模式或服务未连接）")
        }
        sb.appendLine()
        sb.appendLine("════ 应用侧 ════")
        sb.appendLine("相机 FPS    : ${"%.1f".format(s.fps)}")
        sb.appendLine("采集线程    : ${s.cameraThread}")
        sb.appendLine("人脸库人数  : ${s.galleryCount}")
        binding.tvDebug.text = sb.toString()
    }
}
