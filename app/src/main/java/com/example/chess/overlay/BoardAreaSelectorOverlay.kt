package com.example.chess.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chess.detection.BoardGeometry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BoardAreaSelectorOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val preferences = context.getSharedPreferences("chess_board_area", Context.MODE_PRIVATE)

    private var rootView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentSize = 0
    private var controlHeight = 0

    fun show(
        onConfirmed: (BoardGeometry) -> Unit,
        onCancelled: () -> Unit
    ) {
        hide()

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        controlHeight = dp(58)
        val minimumSize = dp(220)
        val maximumSize = min(screenWidth, screenHeight - controlHeight - dp(24)).coerceAtLeast(minimumSize)
        val defaultSize = (screenWidth * 0.82f).roundToInt().coerceIn(minimumSize, maximumSize)

        currentSize = preferences.getInt("size", defaultSize).coerceIn(minimumSize, maximumSize)
        val defaultX = ((screenWidth - currentSize) / 2).coerceAtLeast(0)
        val defaultY = ((screenHeight - currentSize - controlHeight) / 3).coerceAtLeast(0)
        val initialX = preferences.getInt("left", defaultX)
            .coerceIn(0, max(0, screenWidth - currentSize))
        val initialY = preferences.getInt("top", defaultY)
            .coerceIn(0, max(0, screenHeight - currentSize - controlHeight))

        val params = WindowManager.LayoutParams(
            currentSize,
            currentSize + controlHeight,
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
            x = initialX
            y = initialY
        }
        layoutParams = params

        val root = FrameLayout(context)
        rootView = root

        val boardView = BoardGridView(context).apply {
            contentDescription = "Area papan catur yang akan dibaca"
        }
        val boardParams = FrameLayout.LayoutParams(currentSize, currentSize)
        root.addView(boardView, boardParams)

        val hint = TextView(context).apply {
            text = "Geser kotak ke papan catur"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(Color.argb(210, 20, 20, 20), dp(8).toFloat())
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        )

        val handleSize = dp(52)
        val resizeHandle = TextView(context).apply {
            text = "↘"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.argb(235, 105, 55, 255), dp(12).toFloat())
            contentDescription = "Tarik untuk mengubah ukuran kotak"
        }
        val handleParams = FrameLayout.LayoutParams(handleSize, handleSize).apply {
            leftMargin = currentSize - handleSize
            topMargin = currentSize - handleSize
        }
        root.addView(resizeHandle, handleParams)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = roundedBackground(Color.argb(235, 18, 18, 20), dp(10).toFloat())
        }
        val controlsParams = FrameLayout.LayoutParams(currentSize, controlHeight).apply {
            topMargin = currentSize
        }
        root.addView(controls, controlsParams)

        val confirmButton = actionButton("Gunakan area", Color.rgb(98, 49, 255))
        val cancelButton = actionButton("Batal", Color.rgb(65, 65, 70))
        controls.addView(confirmButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dp(3)
        })
        controls.addView(cancelButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.55f).apply {
            marginStart = dp(3)
        })

        boardView.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val windowParams = layoutParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startX = windowParams.x
                        startY = windowParams.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val proposedX = startX + (event.rawX - startRawX).roundToInt()
                        val proposedY = startY + (event.rawY - startRawY).roundToInt()
                        windowParams.x = proposedX.coerceIn(0, max(0, screenWidth - currentSize))
                        windowParams.y = proposedY.coerceIn(
                            0,
                            max(0, screenHeight - currentSize - controlHeight)
                        )
                        updateLayout()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.performClick()
                        return true
                    }
                }
                return false
            }
        })

        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startSize = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startSize = currentSize
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = max(event.rawX - startRawX, event.rawY - startRawY).roundToInt()
                        val windowParams = layoutParams ?: return false
                        val maxForPosition = min(
                            screenWidth - windowParams.x,
                            screenHeight - windowParams.y - controlHeight
                        ).coerceAtLeast(minimumSize)
                        val newSize = (startSize + delta).coerceIn(
                            minimumSize,
                            min(maximumSize, maxForPosition)
                        )
                        resizeWindow(newSize, boardParams, controlsParams, handleParams, handleSize)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.performClick()
                        return true
                    }
                }
                return false
            }
        })

        confirmButton.setOnClickListener {
            val windowParams = layoutParams ?: return@setOnClickListener
            val geometry = BoardGeometry(
                left = windowParams.x,
                top = windowParams.y,
                size = currentSize,
                confidence = 1f
            )
            preferences.edit()
                .putInt("left", geometry.left)
                .putInt("top", geometry.top)
                .putInt("size", geometry.size)
                .apply()
            hide()
            onConfirmed(geometry)
        }

        cancelButton.setOnClickListener {
            hide()
            onCancelled()
        }

        windowManager.addView(root, params)
    }

    fun hide() {
        val root = rootView ?: return
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
            // The overlay may already have been removed by Android.
        } finally {
            rootView = null
            layoutParams = null
        }
    }

    private fun resizeWindow(
        newSize: Int,
        boardParams: FrameLayout.LayoutParams,
        controlsParams: FrameLayout.LayoutParams,
        handleParams: FrameLayout.LayoutParams,
        handleSize: Int
    ) {
        if (newSize == currentSize) return
        currentSize = newSize
        val params = layoutParams ?: return
        params.width = newSize
        params.height = newSize + controlHeight
        boardParams.width = newSize
        boardParams.height = newSize
        controlsParams.width = newSize
        controlsParams.topMargin = newSize
        handleParams.leftMargin = newSize - handleSize
        handleParams.topMargin = newSize - handleSize
        rootView?.requestLayout()
        updateLayout()
    }

    private fun updateLayout() {
        val root = rootView ?: return
        val params = layoutParams ?: return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
            // The service may be shutting down while a gesture is finishing.
        }
    }

    private fun actionButton(label: String, backgroundColor: Int): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        background = roundedBackground(backgroundColor, dp(10).toFloat())
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private class BoardGridView(context: Context) : View(context) {
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(118, 71, 255)
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 4f
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density
        }
        private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 98, 49, 255)
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
            val inset = borderPaint.strokeWidth / 2f
            canvas.drawRect(inset, inset, width - inset, height - inset, borderPaint)
            for (index in 1 until 8) {
                val position = width * index / 8f
                canvas.drawLine(position, 0f, position, height.toFloat(), gridPaint)
                canvas.drawLine(0f, position, width.toFloat(), position, gridPaint)
            }
        }
    }
}
