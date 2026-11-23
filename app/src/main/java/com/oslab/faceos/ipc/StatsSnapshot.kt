package com.oslab.faceos.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 服务进程的运行状态快照（给系统状态调试页用） */
@Parcelize
data class StatsSnapshot(
    val servicePid: Int = 0,
    val workers: Int = 0,
    val permitsTotal: Int = 0,
    val permitsAvailable: Int = 0,
    val inFlight: Int = 0,
    val queueLength: Int = 0,
    val queueCapacity: Int = 0,
    val processed: Long = 0,
    val dropped: Long = 0,
    val errors: Long = 0,
    val avgDetMs: Long = 0,
    val avgLiveMs: Long = 0,
    val avgRecMs: Long = 0,
    val avgTotalMs: Long = 0,
    val activeThreads: Int = 0,
    val variant: String = "fp32",
    val detInputSize: Int = 640,
    val galleryCount: Int = 0,
    val threadNames: Array<String> = emptyArray(),
    // ---- P50/P95（最近 256 帧窗口）----
    val p50DetMs: Long = 0,
    val p95DetMs: Long = 0,
    val p50TotalMs: Long = 0,
    val p95TotalMs: Long = 0,
    val sampleCount: Int = 0,
    // ---- 人脸库姓名（供“按姓名删除模板”使用，最多 32 个）----
    val galleryNames: Array<String> = emptyArray(),
    // ---- 模型加载失败时的错误描述（空串 = 正常）----
    val modelError: String = "",
    // ---- 服务进程累计 CPU 时间（Process.getElapsedCpuTime，毫秒）----
    val cpuTimeMs: Long = -1,
    // ---- 服务进程 CPU 占用（两次 snapshot 之间的差分，-1 表示暂不可用）----
    val cpuPct: Float = -1f
) : Parcelable
