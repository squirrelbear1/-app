package com.oslab.faceos.ml

import android.graphics.PointF

/**
 * 一次推理得到的人脸检测结果（坐标基于「已旋转为正向」的分析帧位图）。
 *
 * @param score   检测置信度
 * @param keypoints SCRFD 输出的 5 个关键点（左眼/右眼/鼻尖/左嘴角/右嘴角）
 */
data class FaceDetection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val keypoints: List<PointF> = emptyList()
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f
}
