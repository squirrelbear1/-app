package com.oslab.faceos.debug

import com.oslab.faceos.ipc.StatsSnapshot

/**
 * 主进程与调试页之间的轻量共享状态。
 *
 * MainActivity 在启动时注册 [provider] 与 [actions]；
 * DebugActivity 每 500ms 调用一次 provider 刷新系统运行状态。
 */
object DebugHub {

    data class Snapshot(
        val mode: String = "-",
        val mainPid: Int = 0,
        val cameraThread: String = "camera-analysis",
        val connected: Boolean = false,
        val submitted: Long = 0,
        val received: Long = 0,
        val reconnects: Long = 0,
        val avgIpcMs: Long = 0,
        val fps: Float = 0f,
        val galleryCount: Int = 0,
        val service: StatsSnapshot? = null
    )

    class Actions(
        val setWorkers: (Int) -> Unit,
        val setPermits: (Int) -> Unit,
        val resetStats: () -> Unit,
        val setVariant: (String) -> Unit,
        val setDetSize: (Int) -> Unit
    )

    @Volatile
    var provider: (() -> Snapshot)? = null

    @Volatile
    var actions: Actions? = null
}
