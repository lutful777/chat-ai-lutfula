package com.example.chess.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.chess.detection.BoardOrientation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ChessOverlayManager(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: ChessMoveOverlayView? = null

    fun showMove(move: String, boardBounds: Rect, orientation: BoardOrientation) {
        if (!Settings.canDrawOverlays(appContext)) return

        mainHandler.post {
            val view = overlayView ?: ChessMoveOverlayView(appContext).also {
                overlayView = it
                windowManager.addView(it, createLayoutParams())
            }
            view.updateMove(move, boardBounds, orientation)
        }
    }

    fun hide() {
        mainHandler.post {
            val view = overlayView ?: return@post
            runCatching { windowManager.removeViewImmediate(view) }
            overlayView = null
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}

private class ChessMoveOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 10f * density
        setShadowLayer(5f * density, 0f, 2f * density, Color.BLACK)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val destinationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 193, 7)
        style = Paint.Style.FILL
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 20, 20, 24)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var move: String = ""
    private var boardBounds = Rect()
    private var orientation = BoardOrientation.WHITE_BOTTOM

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun updateMove(move: String, boardBounds: Rect, orientation: BoardOrientation) {
        this.move = move.lowercase()
        this.boardBounds = Rect(boardBounds)
        this.orientation = orientation
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (move.length < 4 || boardBounds.width() <= 0 || boardBounds.height() <= 0) return

        val from = squareCenter(move.substring(0, 2)) ?: return
        val to = squareCenter(move.substring(2, 4)) ?: return

        val startX = from.first
        val startY = from.second
        val endX = to.first
        val endY = to.second
        val squareSize = boardBounds.width() / 8f

        canvas.drawCircle(startX, startY, squareSize * 0.25f, startPaint)
        canvas.drawCircle(endX, endY, squareSize * 0.24f, destinationPaint)
        canvas.drawLine(startX, startY, endX, endY, arrowPaint)
        drawArrowHead(canvas, startX, startY, endX, endY, squareSize)
        drawInstructionCard(canvas)
    }

    private fun squareCenter(square: String): Pair<Float, Float>? {
        if (square.length != 2) return null
        val file = square[0] - 'a'
        val rank = square[1].digitToIntOrNull() ?: return null
        if (file !in 0..7 || rank !in 1..8) return null

        val visualColumn: Int
        val visualRow: Int
        if (orientation == BoardOrientation.WHITE_BOTTOM) {
            visualColumn = file
            visualRow = 8 - rank
        } else {
            visualColumn = 7 - file
            visualRow = rank - 1
        }

        val cellWidth = boardBounds.width() / 8f
        val cellHeight = boardBounds.height() / 8f
        return Pair(
            boardBounds.left + (visualColumn + 0.5f) * cellWidth,
            boardBounds.top + (visualRow + 0.5f) * cellHeight
        )
    }

    private fun drawArrowHead(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        squareSize: Float
    ) {
        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val headLength = squareSize * 0.34f
        val spread = Math.toRadians(28.0)

        val leftX = endX - (headLength * cos(angle - spread)).toFloat()
        val leftY = endY - (headLength * sin(angle - spread)).toFloat()
        val rightX = endX - (headLength * cos(angle + spread)).toFloat()
        val rightY = endY - (headLength * sin(angle + spread)).toFloat()

        val path = Path().apply {
            moveTo(leftX, leftY)
            lineTo(endX, endY)
            lineTo(rightX, rightY)
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawInstructionCard(canvas: Canvas) {
        val label = "Saran: ${move.substring(0, 2).uppercase()}  →  ${move.substring(2, 4).uppercase()}"
        val horizontalPadding = 18f * density
        val verticalPadding = 12f * density
        val textWidth = textPaint.measureText(label)
        val cardWidth = textWidth + horizontalPadding * 2
        val cardHeight = textPaint.textSize + verticalPadding * 2

        val preferredTop = boardBounds.top - cardHeight - 12f * density
        val top = if (preferredTop >= 10f * density) {
            preferredTop
        } else {
            (boardBounds.bottom + 12f * density).coerceAtMost(height - cardHeight - 10f * density)
        }
        val left = ((width - cardWidth) / 2f).coerceAtLeast(10f * density)
        val rect = RectF(left, top, left + cardWidth, top + cardHeight)

        canvas.drawRoundRect(rect, 16f * density, 16f * density, cardPaint)
        canvas.drawText(
            label,
            rect.left + horizontalPadding,
            rect.bottom - verticalPadding,
            textPaint
        )
    }
}
