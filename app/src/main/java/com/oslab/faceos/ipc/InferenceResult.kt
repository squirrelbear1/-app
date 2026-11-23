package com.oslab.faceos.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 一帧的推理结果（跨进程传输的 Parcelable）。
 *
 * 为了减少 Binder 序列化开销，多张人脸的字段扁平化成数组：
 *   - boxes：       4 个数/人脸（x1,y1,x2,y2）
 *   - scores：      1 个数/人脸
 *   - kps：         10 个数/人脸（5 关键点 × 2）
 *   - names：       识别姓名（空串 = 未匹配）
 *   - similarities：余弦相似度
 *   - liveScores：  活体分数（-1 表示未计算）
 *   - liveLabels：  活体标签（LIVE/PRINT/REPLAY/未测）
 *   - hasLiveness： 是否做了活体
 */
@Parcelize
data class InferenceResult(
    val seq: Long,
    val width: Int,
    val height: Int,
    val faceCount: Int,
    val boxes: FloatArray = FloatArray(0),
    val scores: FloatArray = FloatArray(0),
    val kps: FloatArray = FloatArray(0),
    val names: Array<String> = emptyArray(),
    val similarities: FloatArray = FloatArray(0),
    val liveScores: FloatArray = FloatArray(0),
    val liveLabels: Array<String> = emptyArray(),
    val hasLiveness: BooleanArray = BooleanArray(0),
    val detMs: Long = 0,
    val liveMs: Long = 0,
    val recMs: Long = 0,
    val totalMs: Long = 0,
    val servicePid: Int = 0,
    val threadName: String = "",
    // ---- I/O 事件链时间戳（SystemClock.elapsedRealtimeNanos，跨进程同一时基）----
    /** 相机回调进入分析器的时刻 */
    val captureTsNs: Long = 0,
    /** 主进程把这个帧投递给 Binder 的时刻 */
    val submitTsNs: Long = 0,
    /** 服务进程收到帧的时刻（Messenger handler 入队前） */
    val serviceRecvTsNs: Long = 0,
    /** 推理线程开始执行三模型流水线的时刻 */
    val inferStartTsNs: Long = 0,
    /** 三模型流水线结束的时刻 */
    val inferEndTsNs: Long = 0
) : Parcelable
