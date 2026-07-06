package com.example.chess.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
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
import com.example.chess.domain.ChessAssistantState
import com.example.chess.domain.ChessAssistantStatusBus
import com.example.chess.engine.ChessEngineFactory
import com.example.chess.engine.EngineSettings
import com.example.chess.overlay.ChessOverlayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var overlayManager: ChessOverlayManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processor: ScreenFrameProcessor? = null
    private var isCapturing = false
    private val captureRequested = AtomicBoolean(false)

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlayManager = ChessOverlayManager(this)
        startCaptureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ChessAssistantStatusBus.update(ChessAssistantState.Idle)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START) {
            ChessAssistantStatusBus.update(ChessAssistantState.Error("Perintah Chess Assistant tidak valid"))
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            ChessAssistantStatusBus.update(
                ChessAssistantState.Error("Izin tampil di atas aplikasi belum aktif")
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null && !isCapturing) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                ChessAssistantStatusBus.update(
                    ChessAssistantState.Error("Izin perekaman layar tidak tersedia")
                )
                stopSelf()
                return START_NOT_STICKY
            }
            ChessAssistantStatusBus.update(ChessAssistantState.CapturingScreen)
            startCapture()
        } else if (!isCapturing) {
            ChessAssistantStatusBus.update(
                ChessAssistantState.Error("Data izin perekaman layar tidak valid")
            )
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        isCapturing = true
        serviceScope.launch {
            try {
                val settingsRepository = ChessSettingsRepository(this@ScreenCaptureService)
                if (!settingsRepository.enabled.first()) {
                    ChessAssistantStatusBus.update(
                        ChessAssistantState.Error("Fitur Chess Assistant dinonaktifkan di pengaturan")
                    )
                    stopSelf()
                    return@launch
                }

                val engineSettings = EngineSettings(
                    onlineEnabled = settingsRepository.onlineEnabled.first(),
                    endpointUrl = settingsRepository.endpointUrl.first(),
                    localFallback = settingsRepository.localFallback.first(),
                    showEval = settingsRepository.showEval.first(),
                    showArrow = settingsRepository.showArrow.first()
                )
                val fps = settingsRepository.fps.first().coerceIn(1, 5)
                val engine = ChessEngineFactory.createEngine(this@ScreenCaptureService, engineSettings)
                processor = ScreenFrameProcessor(engine).also { it.updateSettings(engineSettings) }

                launch { collectProcessorState(engineSettings) }
                val displayCreated = withContext(Dispatchers.Main) { createVirtualDisplay() }
                if (!displayCreated) {
                    throw IllegalStateException("Virtual display gagal dibuat")
                }

                val frameDelayMs = 1000L / fps
                while (isActive && isCapturing) {
                    withContext(Dispatchers.Main) { overlayManager?.setCaptureSuppressed(true) }
                    delay(90)
                    captureRequested.set(true)
                    delay(frameDelayMs)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.printStackTrace()
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "Gagal memulai pembacaan layar"
                ChessAssistantStatusBus.update(ChessAssistantState.Error(message))
                withContext(Dispatchers.Main) {
                    overlayManager?.showError(message)
                }
                stopSelf()
            }
        }
    }

    private suspend fun collectProcessorState(settings: EngineSettings) {
        processor?.resultFlow?.collect { state ->
            withContext(Dispatchers.Main) {
                when (state) {
                    is ProcessorState.Result -> {
                        ChessAssistantStatusBus.update(
                            ChessAssistantState.Result(
                                fen = state.fen,
                                bestMove = state.result.bestMove,
                                evaluation = state.result.evaluation
                            )
                        )
                        overlayManager?.showOverlay(
                            bestMove = state.result.bestMove,
                            evaluation = if (settings.showEval) state.result.evaluation else "",
                            depth = state.result.depth,
                            ponder = state.result.ponderMove.orEmpty(),
                            isLocalFallback = state.result.isLocalFallback,
                            showArrow = settings.showArrow,
                            geometry = state.geometry,
                            whiteAtBottom = state.whiteAtBottom
                        )
                    }
                    is ProcessorState.WaitingForOpponent -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.CapturingScreen)
                        overlayManager?.showWaiting("Menunggu langkah lawan...", state.geometry)
                    }
                    is ProcessorState.WaitingForPosition -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.RecognizingPosition)
                        overlayManager?.showWaiting(state.message, state.geometry)
                    }
                    is ProcessorState.SearchingBoard -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.SearchingBoard)
                    }
                    is ProcessorState.BoardNotFound -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.SearchingBoard)
                        overlayManager?.showBoardNotFound()
                    }
                    is ProcessorState.RecognizingPosition -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.RecognizingPosition)
                        overlayManager?.showWaiting("Membaca posisi bidak...", state.geometry)
                    }
                    is ProcessorState.Analyzing -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.Analyzing)
                        overlayManager?.showAnalyzing(state.geometry)
                    }
                    is ProcessorState.NetworkError -> {
                        ChessAssistantStatusBus.update(
                            ChessAssistantState.Error("Stockfish online tidak dapat dihubungi")
                        )
                        overlayManager?.showNetworkError(state.geometry)
                    }
                    is ProcessorState.Error -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.Error(state.message))
                        overlayManager?.showError(state.message)
                    }
                    is ProcessorState.Idle -> {
                        ChessAssistantStatusBus.update(ChessAssistantState.CapturingScreen)
                        overlayManager?.hideOverlay()
                    }
                }
            }
        }
    }

    private fun createVirtualDisplay(): Boolean {
        val projection = mediaProjection ?: return false
        return try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            captureThread = HandlerThread("ChessScreenCapture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    ChessAssistantStatusBus.update(
                        ChessAssistantState.Error("Perekaman layar dihentikan")
                    )
                    stopSelf()
                }
            }.also { projection.registerCallback(it, Handler(mainLooper)) }

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!captureRequested.compareAndSet(true, false)) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                val bitmap = try {
                    imageToBitmap(image, width, height)
                } catch (error: Exception) {
                    error.printStackTrace()
                    null
                } finally {
                    image.close()
                }

                serviceScope.launch {
                    withContext(Dispatchers.Main) { overlayManager?.setCaptureSuppressed(false) }
                    if (bitmap != null) processor?.processFrame(bitmap)
                }
            }, captureHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "ChessScreenAssistant",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )
            virtualDisplay != null
        } catch (error: Throwable) {
            error.printStackTrace()
            false
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun startCaptureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chess Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Screen Assistant")
            .setContentText("Membaca papan dan menjalankan Stockfish")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()

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

    override fun onDestroy() {
        isCapturing = false
        captureRequested.set(false)
        processor?.stop()
        processor = null
        serviceScope.cancel()
        overlayManager?.hideOverlay()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjectionCallback?.let { callback ->
            try {
                mediaProjection?.unregisterCallback(callback)
            } catch (_: Exception) {
                // Projection may already be stopped.
            }
        }
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        if (ChessAssistantStatusBus.state.value !is ChessAssistantState.Error) {
            ChessAssistantStatusBus.update(ChessAssistantState.Idle)
        }
        super.onDestroy()
    }
}
