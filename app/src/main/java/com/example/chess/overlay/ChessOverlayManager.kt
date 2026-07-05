package com.example.chess.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class ChessOverlayManager(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun showOverlay(bestMove: String, evaluation: String) {
        if (overlayView == null) {
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            }

            overlayView = FrameLayout(context).apply {
                val tv = TextView(context).apply {
                    text = "Langkah: \$bestMove | Eval: \$evaluation"
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
                    setPadding(16, 16, 16, 16)
                }
                addView(tv)
            }

            try {
                windowManager?.addView(overlayView, layoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            (overlayView as? FrameLayout)?.getChildAt(0)?.let {
                (it as? TextView)?.text = "Langkah: \$bestMove | Eval: \$evaluation"
            }
        }
    }

    fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
    }
}
