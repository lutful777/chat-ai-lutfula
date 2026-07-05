package com.example.chess.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class ChessOverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: FrameLayout? = null
    private var textView: TextView? = null
    private var arrowView: ArrowView? = null

    private fun ensureOverlayView(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (overlayView != null) return true

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        textView = TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
            setPadding(32, 16, 32, 16)
            textSize = 14f
        }

        arrowView = ArrowView(context)
        overlayView = FrameLayout(context).apply {
            addView(
                arrowView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val textParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = 100
                leftMargin = 100
            }
            addView(textView, textParams)
        }

        return try {
            windowManager.addView(overlayView, layoutParams)
            true
        } catch (error: Exception) {
            error.printStackTrace()
            overlayView = null
            textView = null
            arrowView = null
            false
        }
    }

    fun showOverlay(
        bestMove: String,
        evaluation: String,
        depth: Int,
        ponder: String,
        playerSide: String,
        isLocalFallback: Boolean = false,
        showArrow: Boolean = true
    ) {
        if (!ensureOverlayView()) return

        val text = buildString {
            append(if (isLocalFallback) "Mode lokal aktif\n" else "Stockfish online aktif\n")
            append("Saran sisi bawah: $bestMove\n")
            if (evaluation.isNotEmpty()) append("Eval: $evaluation ")
            if (depth > 0) append("(d$depth)\n") else append("\n")
            if (ponder.isNotEmpty()) append("Ponder: $ponder")
        }
        textView?.text = text

        if (showArrow && bestMove.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?$"))) {
            val fromCol = bestMove[0] - 'a'
            val fromRow = bestMove[1] - '1'
            val toCol = bestMove[2] - 'a'
            val toRow = bestMove[3] - '1'

            val squareSize = 100f
            val startX = if (playerSide == "w") {
                fromCol * squareSize + 50f
            } else {
                (7 - fromCol) * squareSize + 50f
            }
            val startY = if (playerSide == "w") {
                (7 - fromRow) * squareSize + 250f
            } else {
                fromRow * squareSize + 250f
            }

            val endX = if (playerSide == "w") {
                toCol * squareSize + 50f
            } else {
                (7 - toCol) * squareSize + 50f
            }
            val endY = if (playerSide == "w") {
                (7 - toRow) * squareSize + 250f
            } else {
                toRow * squareSize + 250f
            }

            arrowView?.setArrow(startX, startY, endX, endY)
        } else {
            arrowView?.clearArrow()
        }
    }

    fun showWaiting() {
        if (!ensureOverlayView()) return
        textView?.text = "Menunggu langkah lawan..."
        arrowView?.clearArrow()
    }

    fun showAnalyzing() {
        if (!ensureOverlayView()) return
        textView?.text = "Stockfish sedang menganalisis..."
        arrowView?.clearArrow()
    }

    fun showNetworkError() {
        if (!ensureOverlayView()) return
        textView?.text = "Stockfish online tidak dapat dihubungi"
        arrowView?.clearArrow()
    }

    fun showError(message: String) {
        if (!ensureOverlayView()) return
        textView?.text = message
        arrowView?.clearArrow()
    }

    fun hideOverlay() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (error: Exception) {
            error.printStackTrace()
        } finally {
            overlayView = null
            textView = null
            arrowView = null
        }
    }
}
