package com.oslab.faceos.ml

import android.graphics.PointF

/** 全局阈值与实验参数（阈值调优是实验报告的重要部分，集中在这里方便对照实验） */
object Config {
    /** SCRFD 检测置信度阈值 */
    const val DETECTION_THRESHOLD = 0.5f

    /** NMS IoU 阈值 */
    const val NMS_THRESHOLD = 0.4f

    /** 人脸识别余弦相似度阈值（待真机注册样本标定） */
    const val RECOGNITION_THRESHOLD = 0.40f

    /** 活体判定阈值：livenessScore = 1 - (p_print + p_replay) */
    const val LIVENESS_THRESHOLD = 0.50f

    /** MiniFASNet 裁剪倍数（官方 2.7_80x80 模型对应 2.7 倍） */
    const val LIVENESS_CROP_MARGIN = 2.7f
}

/** 活体检测结果 */
class LivenessResult(
    val probs: FloatArray,
    val score: Float,
    val live: Boolean,
    val label: String
)

/** 单张人脸的完整分析结果（检测 + 活体 + 识别） */
data class AnalyzedFace(
    val detection: FaceDetection,
    val identity: String? = null,
    val similarity: Float = 0f,
    val liveness: LivenessResult? = null
) {
    val keypoints: List<PointF> get() = detection.keypoints
}
