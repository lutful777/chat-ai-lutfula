package com.example.chess.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.chess.domain.ChessAssistantState
import com.example.chess.overlay.ChessOverlayManager
import com.example.chess.presentation.ChessAssistantController

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_DEPTH = "EXTRA_DEPTH"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_MIN_CONFIDENCE = "EXTRA_MIN_CONFIDENCE"
        const val EXTRA_SHOW_EVALUATION = "EXTRA_SHOW_EVALUATION"
        const val EXTRA_SHOW_ARROW = "EXTRA_SHOW_ARROW"

        private const val CHANNEL_ID = "chess_screen_capture"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var frameProcessor: ScreenFrameProcessor? = null
    private var overlayManager: ChessOverlayManager? = null
    private var cleaningUp = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture(resetUi = true, stopService = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayManager = ChessOverlayManager(this)
        captureThread = HandlerThread("ChessScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = readResultData(intent)
                if (resultCode != 0 && resultData != null) {
                    startCapture(
                        resultCode = resultCode,
                        resultData = resultData,
                        settings = CaptureSettings(
                            depth = intent.getIntExtra(EXTRA_DEPTH, 3).coerceIn(1, 3),
                            fps = intent.getIntExtra(EXTRA_FPS, 1).coerceIn(1, 3),
                            minimumConfidence = intent.getFloatExtra(
                                EXTRA_MIN_CONFIDENCE,
                                0.15f
                            ).coerceIn(0f, 1f),
                            showEvaluation = intent.getBooleanExtra(
                                EXTRA_SHOW_EVALUATION,
                                true
                            ),
                            showArrow = intent.getBooleanExtra(EXTRA_SHOW_ARROW, true)
                        )
                    )
                } else {
                    ChessAssistantController.update(
                        ChessAssistantState.Error("Izin membaca layar tidak valid.")
                    )
                    stopSelf()
                }
            }

            ACTION_STOP -> stopCapture(resetUi = true, stopService = true)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        settings: CaptureSettings
    ) {
        if (isRunning) return

        startForegroundCompat("Mempersiapkan pembacaan layar…")

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            ChessAssistantController.update(
                ChessAssistantState.Error("MediaProjection gagal dimulai.")
            )
            stopCapture(resetUi = false, stopService = true)
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, captureHandler)
        frameProcessor = ScreenFrameProcessor(
            framesPerSecond = settings.fps,
            engineDepth = settings.depth,
            minimumConfidence = settings.minimumConfidence,
            showEvaluation = settings.showEvaluation,
            onBestMove = { move, bounds, orientation ->
                updateNotification("Langkah terbaik: $move")
                if (settings.showArrow) {
                    overlayManager?.showMove(move, bounds, orientation)
                } else {
                    overlayManager?.hide()
                }
            },
            onBoardLost = {
                overlayManager?.hide()
                updateNotification("Mencari papan catur…")
            }
        )

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                frameProcessor?.submit(image) ?: image.close()
            }, captureHandler)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "ChessScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )

        if (virtualDisplay == null) {
            ChessAssistantController.update(
                ChessAssistantState.Error("Virtual display gagal dibuat.")
            )
            stopCapture(resetUi = false, stopService = true)
            return
        }

        isRunning = true
        ChessAssistantController.update(ChessAssistantState.SearchingBoard)
        updateNotification("Membaca papan catur dari layar")
    }

    private fun readResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun stopCapture(resetUi: Boolean, stopService: Boolean) {
        if (cleaningUp) return
        cleaningUp = true
        isRunning = false

        imageReader?.setOnImageAvailableListener(null, null)
        frameProcessor?.close()
        frameProcessor = null
        overlayManager?.hide()

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (resetUi) ChessAssistantController.reset()

        cleaningUp = false
        if (stopService) stopSelf()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setPackage(packageName)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopAction = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chess Screen Assistant")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopAction)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chess Screen Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture(resetUi = true, stopService = false)
        overlayManager?.hide()
        overlayManager = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class CaptureSettings(
        val depth: Int,
        val fps: Int,
        val minimumConfidence: Float,
        val showEvaluation: Boolean,
        val showArrow: Boolean
    )
}
