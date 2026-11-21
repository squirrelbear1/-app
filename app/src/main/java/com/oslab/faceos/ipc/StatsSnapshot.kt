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
    val avgDetMs: Long = 0,
    val avgLiveMs: Long = 0,
    val avgRecMs: Long = 0,
    val avgTotalMs: Long = 0,
    val activeThreads: Int = 0,
    val galleryCount: Int = 0,
    val threadNames: Array<String> = emptyArray()
) : Parcelable
