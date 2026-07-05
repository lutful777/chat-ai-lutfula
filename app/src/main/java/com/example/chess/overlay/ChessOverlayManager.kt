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

    fun showMove(
        move: String,
        boardBounds: Rect,
        orientation: BoardOrientation,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (!Settings.canDrawOverlays(appContext)) return
        if (!move.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?$"))) {
            hide()
            return
        }

        mainHandler.post {
            val view = overlayView ?: ChessMoveOverlayView(appContext).also { candidate ->
                val added = runCatching {
                    windowManager.addView(candidate, createLayoutParams())
                }.isSuccess
                if (!added) return@post
                overlayView = candidate
            }
            view.updateMove(
                move = move,
                boardBounds = boardBounds,
                orientation = orientation,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        }
    }

    fun hide() {
        mainHandler.post {
            val view = overlayView ?: return@post
            runCatching { windowManager.removeViewImmediate(view) }
            overlayView = null
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
}

internal class ChessMoveOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 9f * density
        setShadowLayer(5f * density, 0f, 2f * density, Color.BLACK)
    }

    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }

    private val destinationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 193, 7)
        style = Paint.Style.FILL
    }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 20, 20, 24)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var move: String = ""
    private var sourceBoardBounds = Rect()
    private var orientation = BoardOrientation.WHITE_BOTTOM
    private var sourceFrameWidth = 1
    private var sourceFrameHeight = 1

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun updateMove(
        move: String,
        boardBounds: Rect,
        orientation: BoardOrientation,
        frameWidth: Int,
        frameHeight: Int
    ) {
        this.move = move.lowercase()
        sourceBoardBounds = Rect(boardBounds)
        this.orientation = orientation
        sourceFrameWidth = frameWidth.coerceAtLeast(1)
        sourceFrameHeight = frameHeight.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (move.length < 4 || width <= 0 || height <= 0) return

        val boardBounds = scaledBoardBounds()
        if (boardBounds.width() <= 0f || boardBounds.height() <= 0f) return

        val from = squareCenter(move.substring(0, 2), boardBounds) ?: return
        val to = squareCenter(move.substring(2, 4), boardBounds) ?: return
        val squareSize = minOf(boardBounds.width(), boardBounds.height()) / 8f

        canvas.drawCircle(from.first, from.second, squareSize * 0.25f, startPaint)
        canvas.drawCircle(to.first, to.second, squareSize * 0.23f, destinationPaint)
        canvas.drawLine(from.first, from.second, to.first, to.second, arrowPaint)
        drawArrowHead(canvas, from.first, from.second, to.first, to.second, squareSize)
        drawInstructionCard(canvas, boardBounds)
    }

    private fun scaledBoardBounds(): RectF {
        val scaleX = width.toFloat() / sourceFrameWidth.toFloat()
        val scaleY = height.toFloat() / sourceFrameHeight.toFloat()
        return RectF(
            sourceBoardBounds.left * scaleX,
            sourceBoardBounds.top * scaleY,
            sourceBoardBounds.right * scaleX,
            sourceBoardBounds.bottom * scaleY
        )
    }

    private fun squareCenter(square: String, boardBounds: RectF): Pair<Float, Float>? {
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
        val headLength = (squareSize * 0.34f).toDouble()
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

    private fun drawInstructionCard(canvas: Canvas, boardBounds: RectF) {
        val label = "Saran sisi bawah: ${move.substring(0, 2).uppercase()} → " +
            move.substring(2, 4).uppercase()
        val horizontalPadding = 18f * density
        val verticalPadding = 12f * density
        val textWidth = textPaint.measureText(label)
        val maxCardWidth = width - 20f * density
        val cardWidth = (textWidth + horizontalPadding * 2).coerceAtMost(maxCardWidth)
        val cardHeight = textPaint.textSize + verticalPadding * 2

        val preferredTop = boardBounds.top - cardHeight - 12f * density
        val top = if (preferredTop >= 10f * density) {
            preferredTop
        } else {
            (boardBounds.bottom + 12f * density)
                .coerceAtMost(height - cardHeight - 10f * density)
                .coerceAtLeast(10f * density)
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
