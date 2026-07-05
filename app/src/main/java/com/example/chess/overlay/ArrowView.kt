package com.example.chess.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ArrowView(context: Context) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDrawing = false

    private val paint = Paint().apply {
        color = Color.parseColor("#80FF0000") // Semi-transparent red
        strokeWidth = 15f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowPaint = Paint().apply {
        color = Color.parseColor("#80FF0000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setArrow(sx: Float, sy: Float, ex: Float, ey: Float) {
        startX = sx
        startY = sy
        endX = ex
        endY = ey
        isDrawing = true
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
        val arrowSize = 40.0
        val arrowAngle = Math.PI / 6.0

        val x1 = endX - arrowSize * cos(angle - arrowAngle)
        val y1 = endY - arrowSize * sin(angle - arrowAngle)
        val x2 = endX - arrowSize * cos(angle + arrowAngle)
        val y2 = endY - arrowSize * sin(angle + arrowAngle)

        val path = Path()
        path.moveTo(endX, endY)
        path.lineTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        path.close()

        canvas.drawPath(path, arrowPaint)
    }
}
