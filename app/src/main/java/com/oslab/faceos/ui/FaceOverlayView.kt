package com.oslab.faceos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.oslab.faceos.ml.FaceDetection
import kotlin.math.min

/**
 * 人脸框 / 关键点叠加层。
 *
 * 分析帧与预览用同样的 4:3 比例，所以这里用 FIT_CENTER 做等比映射：
 *   scale = min(viewW / imageW, viewH / imageH)
 * 前摄预览默认是镜像的，因此 x 方向需要翻转才能和画面里的人脸对齐。
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<FaceDetection> = emptyList()
    private var imageW = 0
    private var imageH = 0

    /** 前摄预览是否镜像显示 */
    var mirrored: Boolean = true

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00E676")
    }
    private val kpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEB3B")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun update(list: List<FaceDetection>, width: Int, height: Int) {
        detections = list
        imageW = width
        imageH = height
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageW <= 0 || imageH <= 0 || detections.isEmpty()) return

        val scale = min(width.toFloat() / imageW, height.toFloat() / imageH)
        val drawnW = imageW * scale
        val drawnH = imageH * scale
        val dx = (width - drawnW) / 2f
        val dy = (height - drawnH) / 2f

        fun mapX(x: Float): Float {
            val vx = dx + x * scale
            return if (mirrored) width - vx else vx
        }

        fun mapY(y: Float): Float = dy + y * scale

        for (det in detections) {
            val left = mapX(det.x1)
            val right = mapX(det.x2)
            val rect = RectF(
                min(left, right),
                mapY(det.y1),
                maxOf(left, right),
                mapY(det.y2)
            )
            canvas.drawRect(rect, boxPaint)

            // 置信度标签
            canvas.drawText(
                String.format(" %.2f ", det.score),
                rect.left,
                (rect.top - 8f).coerceAtLeast(34f),
                textPaint
            )

            // 5 个关键点
            for (kp in det.keypoints) {
                canvas.drawCircle(mapX(kp.x), mapY(kp.y), 7f, kpPaint)
            }
        }
    }
}
