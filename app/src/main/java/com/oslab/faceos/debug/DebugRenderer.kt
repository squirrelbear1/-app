package com.oslab.faceos.debug

/** 系统状态页/调试面板的文本渲染（DebugActivity 与主界面内的实时面板共用） */
object DebugRenderer {

    fun render(s: DebugHub.Snapshot?): String {
        if (s == null) return "调试数据未就绪：请从主界面进入。"

        val svc = s.service
        fun fmtKb(kb: Long): String = if (kb <= 0) "—" else "%.1f MB".format(kb / 1024.0)
        fun fmtPct(p: Float): String = if (p <= 0f) "—" else "%.0f%%".format(p)

        val sb = StringBuilder()
        sb.appendLine("════ 进程 ════")
        sb.appendLine("模式        : ${s.mode}")
        sb.appendLine("主进程 PID  : ${s.mainPid}")
        sb.appendLine("服务进程 PID: ${if (svc != null && svc.servicePid != 0) svc.servicePid else "—（未连接）"}")
        sb.appendLine("服务连接状态: ${if (s.connected) "已连接" else "断开/重连中"}")
        sb.appendLine("IPC 重连次数: ${s.reconnects}   IPC 超时次数: ${s.timeouts}")
        sb.appendLine()
        sb.appendLine("════ IPC ════")
        sb.appendLine("投递帧数    : ${s.submitted}")
        sb.appendLine("收到结果数  : ${s.received}")
        sb.appendLine("平均 IPC 时延: ${s.avgIpcMs} ms  (submit → 结果回调)")
        sb.appendLine("最近结果    : ${if (s.lastResultAgeMs >= 0) "${s.lastResultAgeMs} ms 前" else "—"}")
        sb.appendLine()
        sb.appendLine("════ 服务进程调度 ════")
        if (svc != null) {
            if (svc.modelError.isNotEmpty()) {
                sb.appendLine("模型状态    : ❌ 加载失败（${svc.modelError}）")
            } else {
                sb.appendLine("模型变体    : ${svc.variant}   (fp32/int8/fp16 可切换)")
            }
            sb.appendLine("检测输入    : ${svc.detInputSize}×${svc.detInputSize}")
            sb.appendLine("工作线程数  : ${svc.workers}   (线程池: ${svc.threadNames.joinToString()})")
            sb.appendLine("信号量许可  : 可用 ${svc.permitsAvailable}/${svc.permitsTotal}   在途 ${svc.inFlight}")
            sb.appendLine("队列        : ${svc.queueLength}/${svc.queueCapacity}")
            sb.appendLine("已处理帧数  : ${svc.processed}   异常帧数: ${svc.errors}")
            sb.appendLine("被丢弃帧数  : ${svc.dropped}")
            sb.appendLine()
            sb.appendLine("════ 各阶段耗时（平均 / P50 / P95）════")
            sb.appendLine("检测 SCRFD      : ${svc.avgDetMs} ms   P50 ${svc.p50DetMs}   P95 ${svc.p95DetMs}")
            sb.appendLine("活体 MiniFASNet : ${svc.avgLiveMs} ms")
            sb.appendLine("识别 EdgeFace   : ${svc.avgRecMs} ms")
            sb.appendLine("单帧总耗时      : ${svc.avgTotalMs} ms   P50 ${svc.p50TotalMs}   P95 ${svc.p95TotalMs}")
            sb.appendLine("统计窗口样本数  : ${svc.sampleCount} 帧")
            sb.appendLine("服务进程线程数  : ${svc.activeThreads}")
        } else {
            sb.appendLine("（单进程模式或服务未连接）")
        }
        sb.appendLine()
        sb.appendLine("════ 资源 ════")
        sb.appendLine("主进程 PSS  : ${fmtKb(s.mainPssKb)}   CPU ${fmtPct(s.mainCpuPct)}")
        sb.appendLine("服务进程 PSS: ${fmtKb(s.servicePssKb)}   CPU ${fmtPct(s.serviceCpuPct)}")
        sb.appendLine()
        sb.appendLine("════ 应用侧 ════")
        sb.appendLine("相机 FPS    : ${"%.1f".format(s.fps)}")
        sb.appendLine("采集线程    : ${s.cameraThread}")
        sb.appendLine("人脸库人数  : ${s.galleryCount}")
        return sb.toString()
    }
}
