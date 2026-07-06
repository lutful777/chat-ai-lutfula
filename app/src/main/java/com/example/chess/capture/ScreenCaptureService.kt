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
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.chess.data.ChessSettingsRepository
import com.example.chess.detection.BoardGeometry
import com.example.chess.domain.ChessAssistantState
import com.example.chess.domain.ChessAssistantStatusBus
import com.example.chess.engine.ChessEngineFactory
import com.example.chess.engine.EngineSettings
import com.example.chess.overlay.BoardAreaSelectorOverlay
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var overlayManager: ChessOverlayManager? = null
    private var boardAreaSelector: BoardAreaSelectorOverlay? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processor: ScreenFrameProcessor? = null
    private var isCapturing = false
    private val captureRequested = AtomicBoolean(false)

    @Volatile
    private var selectedBoardArea: BoardGeometry? = null

    @Volatile
    private var rapidFramesRemaining = 0

    private var lastBoardFingerprint: IntArray? = null
    private var confirmationFramesRemaining = 0
    private var lastProcessedFrameAt = 0L

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RAPID_FRAME_DELAY_MS = 220L
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val SQUARE_CHANGE_THRESHOLD = 8
        private const val MIN_CHANGED_SQUARES = 2
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlayManager = ChessOverlayManager(this)
        boardAreaSelector = BoardAreaSelectorOverlay(this)
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

                withContext(Dispatchers.Main) {
                    selectedBoardArea = null
                    resetBoardFrameGate()
                    ChessAssistantStatusBus.update(ChessAssistantState.SelectingBoardArea)
                    boardAreaSelector?.show(
                        onConfirmed = { geometry ->
                            selectedBoardArea = geometry
                            resetBoardFrameGate()
                            rapidFramesRemaining = 2
                            ChessAssistantStatusBus.update(ChessAssistantState.RecognizingPosition)
                            captureRequested.set(true)
                        },
                        onCancelled = {
                            ChessAssistantStatusBus.update(
                                ChessAssistantState.Error("Pemilihan area papan dibatalkan")
                            )
                            stopSelf()
                        }
                    )
                }

                val frameDelayMs = 1000L / fps
                while (isActive && isCapturing) {
                    if (selectedBoardArea == null) {
                        delay(200)
                        continue
                    }

                    withContext(Dispatchers.Main) {
                        overlayManager?.setCaptureSuppressed(true)
                    }
                    delay(90)
                    captureRequested.set(true)

                    val rapid = rapidFramesRemaining > 0
                    if (rapid) rapidFramesRemaining--
                    delay(if (rapid) RAPID_FRAME_DELAY_MS else frameDelayMs)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.printStackTrace()
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "Gagal memulai pembacaan layar"
                ChessAssistantStatusBus.update(ChessAssistantState.Error(message))
                withContext(Dispatchers.Main) {
                    boardAreaSelector?.hide()
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
                        if (selectedBoardArea == null) {
                            ChessAssistantStatusBus.update(ChessAssistantState.SelectingBoardArea)
                        } else {
                            ChessAssistantStatusBus.update(ChessAssistantState.CapturingScreen)
                        }
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

                val fullBitmap = try {
                    imageToBitmap(image, width, height)
                } catch (error: Exception) {
                    error.printStackTrace()
                    null
                } finally {
                    image.close()
                }
                val selectedGeometry = selectedBoardArea

                serviceScope.launch {
                    withContext(Dispatchers.Main) {
                        overlayManager?.setCaptureSuppressed(false)
                    }

                    if (fullBitmap == null || selectedGeometry == null) {
                        fullBitmap?.recycle()
                        return@launch
                    }

                    val cropped = try {
                        cropBoardBitmap(fullBitmap, selectedGeometry)
                    } catch (error: Exception) {
                        error.printStackTrace()
                        null
                    }

                    if (cropped == null) {
                        if (!fullBitmap.isRecycled) fullBitmap.recycle()
                        ChessAssistantStatusBus.update(
                            ChessAssistantState.Error("Area papan berada di luar layar")
                        )
                        return@launch
                    }

                    if (cropped.bitmap !== fullBitmap && !fullBitmap.isRecycled) {
                        fullBitmap.recycle()
                    }

                    if (!shouldProcessBoard(cropped.bitmap)) {
                        cropped.bitmap.recycle()
                        return@launch
                    }

                    val localGeometry = BoardGeometry(
                        left = 0,
                        top = 0,
                        size = cropped.bitmap.width,
                        confidence = 1f
                    )
                    processor?.processFrame(
                        bitmap = cropped.bitmap,
                        recognitionGeometry = localGeometry,
                        displayGeometry = cropped.displayGeometry
                    )
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

    private fun cropBoardBitmap(bitmap: Bitmap, requested: BoardGeometry): CroppedBoard? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val left = requested.left.coerceIn(0, bitmap.width - 1)
        val top = requested.top.coerceIn(0, bitmap.height - 1)
        val availableWidth = bitmap.width - left
        val availableHeight = bitmap.height - top
        val size = min(requested.size, min(availableWidth, availableHeight))
        if (size < 160) return null

        val crop = Bitmap.createBitmap(bitmap, left, top, size, size)
        return CroppedBoard(
            bitmap = crop,
            displayGeometry = BoardGeometry(
                left = left,
                top = top,
                size = size,
                confidence = 1f
            )
        )
    }

    @Synchronized
    private fun shouldProcessBoard(bitmap: Bitmap): Boolean {
        val current = boardFingerprint(bitmap)
        val now = SystemClock.elapsedRealtime()
        val previous = lastBoardFingerprint

        if (previous == null) {
            lastBoardFingerprint = current
            confirmationFramesRemaining = 1
            lastProcessedFrameAt = now
            return true
        }

        var changedSquares = 0
        for (index in current.indices) {
            if (abs(current[index] - previous[index]) >= SQUARE_CHANGE_THRESHOLD) {
                changedSquares++
            }
        }

        val heartbeatDue = now - lastProcessedFrameAt >= HEARTBEAT_INTERVAL_MS
        if (changedSquares >= MIN_CHANGED_SQUARES || heartbeatDue) {
            lastBoardFingerprint = current
            confirmationFramesRemaining = 1
            lastProcessedFrameAt = now
            return true
        }

        if (confirmationFramesRemaining > 0) {
            confirmationFramesRemaining--
            lastBoardFingerprint = current
            lastProcessedFrameAt = now
            return true
        }

        return false
    }

    private fun boardFingerprint(bitmap: Bitmap): IntArray {
        val result = IntArray(64)
        val squareSize = bitmap.width / 8f
        val offsets = floatArrayOf(0.25f, 0.5f, 0.75f)
        var index = 0

        for (row in 0 until 8) {
            for (column in 0 until 8) {
                var lumaSum = 0f
                var sampleCount = 0
                for (offsetY in offsets) {
                    for (offsetX in offsets) {
                        val x = ((column + offsetX) * squareSize)
                            .roundToInt()
                            .coerceIn(0, bitmap.width - 1)
                        val y = ((row + offsetY) * squareSize)
                            .roundToInt()
                            .coerceIn(0, bitmap.height - 1)
                        val color = bitmap.getPixel(x, y)
                        val red = (color shr 16) and 0xff
                        val green = (color shr 8) and 0xff
                        val blue = color and 0xff
                        lumaSum += 0.2126f * red + 0.7152f * green + 0.0722f * blue
                        sampleCount++
                    }
                }
                result[index++] = (lumaSum / max(1, sampleCount)).roundToInt()
            }
        }
        return result
    }

    @Synchronized
    private fun resetBoardFrameGate() {
        lastBoardFingerprint = null
        confirmationFramesRemaining = 0
        lastProcessedFrameAt = 0L
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
            .setContentText("Membaca area papan dan menjalankan Stockfish")
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
        selectedBoardArea = null
        rapidFramesRemaining = 0
        resetBoardFrameGate()
        boardAreaSelector?.hide()
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

    private data class CroppedBoard(
        val bitmap: Bitmap,
        val displayGeometry: BoardGeometry
    )
}
