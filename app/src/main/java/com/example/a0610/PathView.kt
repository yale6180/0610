package com.example.a0610

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class PathView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pathPaint = Paint().apply {
        color = Color.parseColor("#2196F3") // 藍色路徑
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val pointerPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val points = mutableListOf<MainActivity.PointD>()
    private var isClosed = false
    private var currentHeading = 0.0
    private val drawPath = Path()

    fun setPoints(newPoints: List<MainActivity.PointD>) {
        points.clear()
        points.addAll(newPoints)
        invalidate()
    }

    // 更新目前方向，讓箭頭可以即時旋轉
    fun updateHeading(headingRad: Double) {
        currentHeading = headingRad
        invalidate()
    }

    fun clearPath() {
        points.clear()
        isClosed = false
        invalidate()
    }

    fun closeLoop(closed: Boolean) {
        isClosed = closed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        // 1. 計算路徑邊界，用於自動縮放
        var minX = points.minOf { it.x }
        var maxX = points.maxOf { it.x }
        var minY = points.minOf { it.y }
        var maxY = points.maxOf { it.y }

        // 加上一點邊距
        val padding = 2.0 
        minX -= padding; maxX += padding
        minY -= padding; maxY += padding

        val pathW = (maxX - minX).coerceAtLeast(5.0)
        val pathH = (maxY - minY).coerceAtLeast(5.0)

        // 2. 計算縮放比例，確保路徑永遠在螢幕內
        val scaleX = width / pathW
        val scaleY = height / pathH
        val scale = min(scaleX, scaleY).toFloat()

        // 3. 繪製路徑
        drawPath.reset()
        
        // 座標轉換函式：將真實世界公尺轉為螢幕像素
        fun getCanvasX(x: Double) = (width / 2f + (x - (minX + maxX) / 2) * scale).toFloat()
        fun getCanvasY(y: Double) = (height / 2f - (y - (minY + maxY) / 2) * scale).toFloat()

        drawPath.moveTo(getCanvasX(points[0].x), getCanvasY(points[0].y))
        for (i in 1 until points.size) {
            drawPath.lineTo(getCanvasX(points[i].x), getCanvasY(points[i].y))
        }

        if (isClosed) drawPath.close()
        canvas.drawPath(drawPath, pathPaint)

        // 4. 繪製目前位置的紅色方向箭頭
        val lastP = points.last()
        val px = getCanvasX(lastP.x)
        val py = getCanvasY(lastP.y)
        
        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(Math.toDegrees(currentHeading).toFloat())
        
        // 畫一個小三角形箭頭
        val arrow = Path().apply {
            moveTo(0f, -25f)
            lineTo(-15f, 15f)
            lineTo(15f, 15f)
            close()
        }
        canvas.drawPath(arrow, pointerPaint)
        canvas.restore()
    }
}
