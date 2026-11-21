package com.oslab.faceos.ui

import com.oslab.faceos.infer.InferenceEngine
import com.oslab.faceos.ipc.InferenceResult
import com.oslab.faceos.ml.AnalyzedFace
import com.oslab.faceos.ml.Config
import com.oslab.faceos.ml.LivenessResult

/** 把跨进程的扁平结果还原成 UI 绘制用的数据模型 */
fun InferenceResult.toAnalyzedFaces(): List<AnalyzedFace> {
    val detections = InferenceEngine.toDetections(this)
    return detections.mapIndexed { i, det ->
        val hasLive = hasLiveness.getOrElse(i) { false }
        val live = if (hasLive) {
            LivenessResult(
                probs = floatArrayOf(),
                score = liveScores.getOrElse(i) { 0f },
                live = liveScores.getOrElse(i) { 0f } >= Config.LIVENESS_THRESHOLD,
                label = liveLabels.getOrNull(i) ?: "?"
            )
        } else {
            null
        }
        val name = names.getOrNull(i)?.takeIf { it.isNotEmpty() }
        AnalyzedFace(det, name, similarities.getOrElse(i) { 0f }, live)
    }
}
