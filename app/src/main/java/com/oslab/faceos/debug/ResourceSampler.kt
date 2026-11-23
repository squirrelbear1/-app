package com.oslab.faceos.debug

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * 系统状态页的资源采样器：
 *   - PSS 内存（主进程 / 推理服务进程）；
 *   - CPU 占用（读取 /proc/<pid>/stat 的 utime+stime，按两次采样的差值计算百分比）。
 *
 * 说明：/proc/<pid>/stat 在部分 Android 版本上可能被 SELinux 限制，读不到时返回 0，
 * 状态页会显示 “—”，不影响其他指标；内存通过 ActivityManager 读取。
 */
class ResourceSampler(context: Context) {

    data class Sample(
        val mainPssKb: Long = 0,
        val servicePssKb: Long = 0,
        val mainCpuPct: Float = 0f,
        val serviceCpuPct: Float = 0f
    )

    private val appContext = context.applicationContext
    private val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private var lastNs = 0L
    private var lastMainPid = 0
    private var lastServicePid = 0
    private var lastMainTicks = -1L
    private var lastServiceTicks = -1L

    @Synchronized
    fun sample(mainPid: Int, servicePid: Int): Sample {
        val now = SystemClock.elapsedRealtimeNanos()
        val mainTicks = ticks(mainPid)
        val serviceTicks = ticks(servicePid)

        if (mainPid != lastMainPid) {
            lastMainPid = mainPid
            lastMainTicks = -1L
        }
        if (servicePid != lastServicePid) {
            lastServicePid = servicePid
            lastServiceTicks = -1L
        }

        var mainCpu = 0f
        var serviceCpu = 0f
        if (lastNs != 0L && now > lastNs) {
            val dtSec = (now - lastNs) / 1_000_000_000.0
            if (dtSec > 0.05) {
                if (mainTicks >= 0 && lastMainTicks >= 0) {
                    mainCpu = ((mainTicks - lastMainTicks) / dtSec).toFloat()
                }
                if (serviceTicks >= 0 && lastServiceTicks >= 0) {
                    serviceCpu = ((serviceTicks - lastServiceTicks) / dtSec).toFloat()
                }
            }
        }
        lastNs = now
        lastMainTicks = mainTicks
        lastServiceTicks = serviceTicks

        return Sample(
            mainPssKb = pssKb(mainPid),
            servicePssKb = pssKb(servicePid),
            mainCpuPct = mainCpu.coerceAtLeast(0f),
            serviceCpuPct = serviceCpu.coerceAtLeast(0f)
        )
    }

    /** utime + stime，单位是内核时钟 tick（Android 通常 100 Hz） */
    private fun ticks(pid: Int): Long {
        if (pid <= 0) return -1
        return try {
            val text = File("/proc/$pid/stat").readText().trim()
            val close = text.lastIndexOf(')')
            if (close < 0) return -1
            val parts = text.substring(close + 2).trim().split(Regex("\\s+"))
            if (parts.size < 13) return -1
            parts[11].toLong() + parts[12].toLong()
        } catch (t: Throwable) {
            -1L
        }
    }

    private fun pssKb(pid: Int): Long {
        if (pid <= 0) return 0
        return try {
            val infos = am.getProcessMemoryInfo(intArrayOf(pid))
            infos.firstOrNull()?.totalPss?.toLong() ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }
}
