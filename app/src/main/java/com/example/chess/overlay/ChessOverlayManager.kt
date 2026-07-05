package com.example.chess.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.chess.detection.BoardGeometry

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
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        textView = TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#CC111111"))
            setPadding(24, 14, 24, 14)
            textSize = 14f
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.9f).toInt()
            elevation = 8f
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
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = 24
                    leftMargin = 24
                }
            )
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
        isLocalFallback: Boolean = false,
        showArrow: Boolean = true,
        geometry: BoardGeometry,
        whiteAtBottom: Boolean
    ) {
        if (!ensureOverlayView()) return
        overlayView?.visibility = View.VISIBLE
        positionTextOutsideBoard(geometry)

        textView?.text = buildString {
            append(if (isLocalFallback) "Stockfish lokal\n" else "Stockfish online\n")
            append("Langkah: $bestMove")
            if (evaluation.isNotEmpty()) append("  Eval: $evaluation")
            if (depth > 0) append("  d$depth")
            if (ponder.isNotEmpty()) append("\nBalasan: $ponder")
        }

        if (showArrow && bestMove.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?$"))) {
            val fromFile = bestMove[0] - 'a'
            val fromRank = bestMove[1] - '1'
            val toFile = bestMove[2] - 'a'
            val toRank = bestMove[3] - '1'
            val start = geometry.centerForUciSquare(fromFile, fromRank, whiteAtBottom)
            val end = geometry.centerForUciSquare(toFile, toRank, whiteAtBottom)
            arrowView?.setArrow(start.x, start.y, end.x, end.y, geometry.squareSize)
        } else {
            arrowView?.clearArrow()
        }
    }

    fun showWaiting(message: String = "Menunggu langkah lawan...", geometry: BoardGeometry? = null) {
        if (!ensureOverlayView()) return
        overlayView?.visibility = View.VISIBLE
        if (geometry != null) positionTextOutsideBoard(geometry)
        textView?.text = message
        arrowView?.clearArrow()
    }

    fun showAnalyzing(geometry: BoardGeometry? = null) {
        showWaiting("Stockfish sedang menganalisis...", geometry)
    }

    fun showBoardNotFound() {
        showWaiting("Papan belum ditemukan. Pastikan seluruh papan terlihat.")
    }

    fun showNetworkError(geometry: BoardGeometry? = null) {
        showWaiting("Stockfish online tidak dapat dihubungi", geometry)
    }

    fun showError(message: String, geometry: BoardGeometry? = null) {
        showWaiting(message, geometry)
    }

    fun setCaptureSuppressed(suppressed: Boolean) {
        overlayView?.visibility = if (suppressed) View.INVISIBLE else View.VISIBLE
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

    private fun positionTextOutsideBoard(geometry: BoardGeometry) {
        val text = textView ?: return
        val params = text.layoutParams as? FrameLayout.LayoutParams ?: return
        val screenHeight = context.resources.displayMetrics.heightPixels
        params.leftMargin = geometry.left.coerceAtLeast(16)
        params.topMargin = if (geometry.top >= 150) {
            (geometry.top - 130).coerceAtLeast(16)
        } else {
            (geometry.top + geometry.size + 16).coerceAtMost(screenHeight - 100)
        }
        text.layoutParams = params
    }
}
