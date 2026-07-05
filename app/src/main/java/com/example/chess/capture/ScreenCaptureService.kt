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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.chess.data.ChessSettingsRepository
import com.example.chess.engine.ChessEngineFactory
import com.example.chess.engine.EngineSettings
import com.example.chess.overlay.ChessOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        private const val CHANNEL_ID = "chess_screen_capture"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var processor: ScreenFrameProcessor? = null
    private var stateJob: Job? = null
    private var overlayManager: ChessOverlayManager? = null
    private var isCapturing = false
    private var cleaningUp = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture(stopService = true)
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
            ACTION_STOP -> stopCapture(stopService = true)

            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = readResultData(intent)
                if (resultCode == 0 || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!isCapturing) {
                    startForegroundCompat("Mempersiapkan Stockfish dan pembacaan papan…")
                    startCapture(resultCode, resultData)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (isCapturing) return
        isCapturing = true
        updateNotification("Mempersiapkan Stockfish dan pembacaan papan…")

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            updateNotification("MediaProjection gagal dimulai")
            stopCapture(stopService = true)
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, captureHandler)

        serviceScope.launch {
            try {
                val repository = ChessSettingsRepository(this@ScreenCaptureService)
                if (!repository.enabled.first()) {
                    withContext(Dispatchers.Main) {
                        updateNotification("Chess Assistant sedang dinonaktifkan")
                        stopCapture(stopService = true)
                    }
                    return@launch
                }

                val settings = EngineSettings(
                    onlineEnabled = repository.onlineEnabled.first(),
                    endpointUrl = repository.endpointUrl.first(),
                    localFallback = repository.localFallback.first(),
                    showEval = repository.showEval.first(),
                    showArrow = repository.showArrow.first()
                )
                val fps = repository.fps.first().coerceIn(1, 3)
                val engine = ChessEngineFactory.createEngine(this@ScreenCaptureService, settings)
                val frameProcessor = ScreenFrameProcessor(
                    engine = engine,
                    framesPerSecond = fps,
                    minimumConfidence = 0.15f
                )
                frameProcessor.updateSettings(settings)
                processor = frameProcessor

                stateJob?.cancel()
                stateJob = launch {
                    frameProcessor.resultFlow.collect { state ->
                        withContext(Dispatchers.Main) {
                            handleProcessorState(state, settings)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    createVirtualDisplay(projection, frameProcessor)
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    overlayManager?.hide()
                    updateNotification(error.message ?: "Gagal memulai pembacaan papan")
                    stopCapture(stopService = true)
                }
            }
        }
    }

    private fun createVirtualDisplay(
        projection: MediaProjection,
        frameProcessor: ScreenFrameProcessor
    ) {
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
                frameProcessor.submit(image)
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
            throw IllegalStateException("Virtual display gagal dibuat")
        }
        updateNotification("Mencari papan catur…")
    }

    private fun handleProcessorState(state: ProcessorState, settings: EngineSettings) {
        when (state) {
            ProcessorState.Idle,
            ProcessorState.SearchingBoard -> {
                overlayManager?.hide()
                updateNotification("Mencari papan catur…")
            }

            ProcessorState.RecognizingPosition -> {
                overlayManager?.hide()
                updateNotification("Mengenali posisi bidak…")
            }

            ProcessorState.Analyzing -> {
                overlayManager?.hide()
                updateNotification("Stockfish berpikir 3000 ms…")
            }

            ProcessorState.WaitingForOpponent -> {
                overlayManager?.hide()
                updateNotification("Menunggu langkah sisi atas…")
            }

            is ProcessorState.Waiting -> {
                overlayManager?.hide()
                updateNotification(state.message)
            }

            is ProcessorState.NetworkError -> {
                overlayManager?.hide()
                updateNotification("Stockfish gagal: ${state.message}")
            }

            is ProcessorState.Error -> {
                overlayManager?.hide()
                updateNotification(state.message)
            }

            is ProcessorState.Result -> {
                if (settings.showArrow) {
                    overlayManager?.showMove(
                        move = state.result.bestMove,
                        boardBounds = state.boardBounds,
                        orientation = state.orientation,
                        frameWidth = state.frameWidth,
                        frameHeight = state.frameHeight
                    )
                } else {
                    overlayManager?.hide()
                }
                val source = state.result.bestMove.take(2).uppercase()
                val destination = state.result.bestMove.drop(2).take(2).uppercase()
                val mode = if (state.result.isLocalFallback) "lokal" else "online"
                updateNotification("Saran $mode: $source → $destination")
            }
        }
    }

    private fun stopCapture(stopService: Boolean) {
        if (cleaningUp) return
        cleaningUp = true
        isCapturing = false

        stateJob?.cancel()
        stateJob = null
        imageReader?.setOnImageAvailableListener(null, null)
        processor?.close()
        processor = null
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
        cleaningUp = false
        if (stopService) stopSelf()
    }

    private fun readResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture(stopService = false)
        overlayManager?.hide()
        overlayManager = null
        serviceScope.cancel()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
