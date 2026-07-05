package com.example.chess.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class ArrowView(context: Context) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDrawing = false
    private var arrowSize = 40f

    private val paint = Paint().apply {
        color = Color.parseColor("#CC24C56E")
        strokeWidth = 15f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowPaint = Paint().apply {
        color = Color.parseColor("#CC24C56E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setArrow(sx: Float, sy: Float, ex: Float, ey: Float, squareSize: Float) {
        startX = sx
        startY = sy
        endX = ex
        endY = ey
        paint.strokeWidth = max(8f, squareSize * 0.16f)
        arrowSize = max(24f, squareSize * 0.42f)
        isDrawing = true
        visibility = VISIBLE
        invalidate()
    }

    fun clearArrow() {
        isDrawing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDrawing) return

        canvas.drawLine(startX, startY, endX, endY, paint)

        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val head = arrowSize.toDouble()
        val arrowAngle = Math.PI / 6.0
        val x1 = endX - head * cos(angle - arrowAngle)
        val y1 = endY - head * sin(angle - arrowAngle)
        val x2 = endX - head * cos(angle + arrowAngle)
        val y2 = endY - head * sin(angle + arrowAngle)

        val path = Path().apply {
            moveTo(endX, endY)
            lineTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }
}
