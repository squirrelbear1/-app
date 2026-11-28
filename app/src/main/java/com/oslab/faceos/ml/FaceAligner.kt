package com.oslab.faceos.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect

/**
 * 基于 5 个关键点的人脸对齐（相似变换：旋转 + 均匀缩放 + 平移）。
 *
 * 目标模板是 ArcFace 标准 112×112 五点模板，与 InsightFace / EdgeFace 官方一致，
 * 保证送进识别模型的人脸姿态、尺度、位置统一——这是同类模型能比对的前提。
 *
 * 求解方法：最小二乘相似变换（Umeyama 的 2D 闭式解，不需要 SVD 库）。
 */
object FaceAligner {

    const val TEMPLATE_SIZE = 112

    /** ArcFace 五点模板（左眼、右眼、鼻尖、左嘴角、右嘴角） */
    val ARCFACE_DST = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * 把 [src] 中由 [keypoints]（5 个点，SCRFD 顺序）标定的人脸，warp 成 112×112 的正脸。
     * @return 对齐后的小图；关键点不足或退化时返回 null
     */
    fun align(src: Bitmap, keypoints: List<PointF>, size: Int = TEMPLATE_SIZE): Bitmap? {
        if (keypoints.size < 5) return null

        var sx = 0f
        var sy = 0f
        var dx = 0f
        var dy = 0f
        for (i in 0 until 5) {
            sx += keypoints[i].x
            sy += keypoints[i].y
            dx += ARCFACE_DST[i * 2]
            dy += ARCFACE_DST[i * 2 + 1]
        }
        sx /= 5f; sy /= 5f; dx /= 5f; dy /= 5f

        var a = 0f
        var b = 0f
        var denom = 0f
        for (i in 0 until 5) {
            val px = keypoints[i].x - sx
            val py = keypoints[i].y - sy
            val qx = ARCFACE_DST[i * 2] - dx
            val qy = ARCFACE_DST[i * 2 + 1] - dy
            a += px * qx + py * qy
            b += px * qy - py * qx
            denom += px * px + py * py
        }
        if (denom < 1e-6f) return null

        val sc = a / denom      // 缩放 * cos(θ)
        val ss = b / denom      // 缩放 * sin(θ)
        val tx = dx - (sc * sx - ss * sy)
        val ty = dy - (ss * sx + sc * sy)

        val k = size / TEMPLATE_SIZE.toFloat()
        val matrix = Matrix()
        matrix.setValues(
            floatArrayOf(
                sc * k, -ss * k, tx * k,
                ss * k, sc * k, ty * k,
                0f, 0f, 1f
            )
        )

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * 活体检测用的裁剪：以 bbox 中心为中心、按 [margin] 倍**长边**裁剪成正方形
     * （MiniFASNet 官方推理实现：`scale = min(imgH/boxH, imgW/boxW, 2.7)`，保持 bbox 长边比例）。
     *
     * 贴边时不再把宽高截成不相等再拉伸，而是补黑边成正方形，避免活体输入变形。
     */
    fun cropSquare(src: Bitmap, det: FaceDetection, margin: Float): Bitmap? {
        val cx = det.centerX
        val cy = det.centerY
        var side = maxOf(det.width, det.height) * margin
        if (side < 1f) return null
        side = side.coerceAtMost(minOf(src.width, src.height).toFloat())
        val sideInt = side.toInt().coerceAtLeast(1)

        val left = (cx - sideInt / 2f).toInt()
            .coerceIn(0, (src.width - sideInt).coerceAtLeast(0))
        val top = (cy - sideInt / 2f).toInt()
            .coerceIn(0, (src.height - sideInt).coerceAtLeast(0))

        val out = Bitmap.createBitmap(sideInt, sideInt, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val sx1 = left.coerceAtLeast(0)
        val sy1 = top.coerceAtLeast(0)
        val sx2 = (left + sideInt).coerceAtMost(src.width)
        val sy2 = (top + sideInt).coerceAtMost(src.height)
        if (sx2 > sx1 && sy2 > sy1) {
            val srcRect = Rect(sx1, sy1, sx2, sy2)
            val dstRect = Rect(sx1 - left, sy1 - top, sx2 - left, sy2 - top)
            canvas.drawBitmap(src, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /**
     * 活体检测用的裁剪（旧版，非方形，保留以兼容对照实验）。
     */
    fun cropWithMargin(src: Bitmap, det: FaceDetection, margin: Float): Bitmap? {
        val cx = det.centerX
        val cy = det.centerY
        val halfW = det.width * margin / 2f
        val halfH = det.height * margin / 2f
        val left = (cx - halfW).toInt().coerceIn(0, src.width - 1)
        val top = (cy - halfH).toInt().coerceIn(0, src.height - 1)
        val right = (cx + halfW).toInt().coerceIn(left + 1, src.width)
        val bottom = (cy + halfH).toInt().coerceIn(top + 1, src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }
}
