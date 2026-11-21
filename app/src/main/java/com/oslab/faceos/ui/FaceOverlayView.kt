package com.oslab.faceos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.oslab.faceos.ml.AnalyzedFace
import kotlin.math.min

/**
 * 人脸框 / 关键点 / 身份 / 活体结果叠加层。
 *
 * 颜色约定：
 *   绿色 = 活体通过；红色 = 判定为攻击；黄色 = 有检测但未做活体（多人脸时非最大脸）
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var faces: List<AnalyzedFace> = emptyList()
    private var imageW = 0
    private var imageH = 0

    var mirrored: Boolean = true

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val kpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEB3B")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = true
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 24f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun update(list: List<AnalyzedFace>, width: Int, height: Int) {
        faces = list
        imageW = width
        imageH = height
        invalidate()
    }

    fun clear() {
        faces = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageW <= 0 || imageH <= 0 || faces.isEmpty()) return

        val scale = min(width.toFloat() / imageW, height.toFloat() / imageH)
        val dx = (width - imageW * scale) / 2f
        val dy = (height - imageH * scale) / 2f

        fun mapX(x: Float): Float {
            val vx = dx + x * scale
            return if (mirrored) width - vx else vx
        }

        fun mapY(y: Float): Float = dy + y * scale

        for (face in faces) {
            val det = face.detection
            val left = mapX(det.x1)
            val right = mapX(det.x2)
            val rect = RectF(min(left, right), mapY(det.y1), maxOf(left, right), mapY(det.y2))

            boxPaint.color = when {
                face.liveness == null -> Color.parseColor("#FFC107")
                face.liveness.live -> Color.parseColor("#00E676")
                else -> Color.parseColor("#FF5252")
            }
            canvas.drawRect(rect, boxPaint)

            for (kp in face.keypoints) {
                canvas.drawCircle(mapX(kp.x), mapY(kp.y), 6f, kpPaint)
            }

            val title = buildString {
                append(face.identity ?: "未知")
                if (face.identity != null) append("  ${"%.2f".format(face.similarity)}")
            }
            canvas.drawText(" $title ", rect.left, (rect.top - 10f).coerceAtLeast(34f), textPaint)

            val sub = buildString {
                append("det ${"%.2f".format(det.score)}")
                face.liveness?.let {
                    append("  ${it.label} ${"%.2f".format(it.score)}")
                }
            }
            canvas.drawText(" $sub ", rect.left, (rect.bottom + 28f).coerceAtMost(height - 8f), subTextPaint)
        }
    }
}
