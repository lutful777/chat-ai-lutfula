package com.example.chess.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.chess.data.ChessSettingsRepository
import com.example.chess.domain.ChessAssistantState
import com.example.chess.domain.ChessAssistantStatusBus
import com.example.chess.engine.ChessEngineFactory
import com.example.chess.engine.EngineSettings
import com.example.chess.overlay.ChessOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var overlayManager: ChessOverlayManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processor: ScreenFrameProcessor? = null
    private var isCapturing = false

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

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != -1 && resultData != null && !isCapturing) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ChessAssistantStatusBus.update(ChessAssistantState.CapturingScreen)
            startCapture()
        } else if (!isCapturing) {
            ChessAssistantStatusBus.update(ChessAssistantState.Error("Data izin perekaman layar tidak valid"))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        isCapturing = true
        serviceScope.launch {
            val settingsRepository = ChessSettingsRepository(this@ScreenCaptureService)
            val onlineEnabled = settingsRepository.onlineEnabled.first()
            val endpointUrl = settingsRepository.endpointUrl.first()
            val localFallback = settingsRepository.localFallback.first()
            val showEval = settingsRepository.showEval.first()
            val showArrow = settingsRepository.showArrow.first()
            val fps = settingsRepository.fps.first().coerceIn(1, 5)

            val engineSettings = EngineSettings(
                onlineEnabled = onlineEnabled,
                endpointUrl = endpointUrl,
                localFallback = localFallback,
                showEval = showEval,
                showArrow = showArrow
            )
            val engine = ChessEngineFactory.createEngine(this@ScreenCaptureService, engineSettings)
            processor = ScreenFrameProcessor(engine).also { it.updateSettings(engineSettings) }

            launch {
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
                                    evaluation = if (engineSettings.showEval) state.result.evaluation else "",
                                    depth = state.result.depth,
                                    ponder = state.result.ponderMove.orEmpty(),
                                    playerSide = state.lockedBottomSide,
                                    isLocalFallback = state.result.isLocalFallback,
                                    showArrow = engineSettings.showArrow
                                )
                            }
                            is ProcessorState.WaitingForOpponent -> {
                                ChessAssistantStatusBus.update(ChessAssistantState.CapturingScreen)
                                overlayManager?.showWaiting()
                            }
                            is ProcessorState.Analyzing -> {
                                ChessAssistantStatusBus.update(ChessAssistantState.Analyzing)
                                overlayManager?.showAnalyzing()
                            }
                            is ProcessorState.NetworkError -> {
                                ChessAssistantStatusBus.update(
                                    ChessAssistantState.Error("Stockfish online tidak dapat dihubungi")
                                )
                                overlayManager?.showNetworkError()
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

            var toggle = true
            val frameDelayMs = 1000L / fps
            while (isActive && isCapturing) {
                if (settingsRepository.enabled.first()) {
                    val fen = if (toggle) {
                        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                    } else {
                        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
                    }
                    processor?.processFrame(fen)
                    toggle = !toggle
                } else {
                    ChessAssistantStatusBus.update(ChessAssistantState.Idle)
                    overlayManager?.hideOverlay()
                }
                delay(frameDelayMs)
            }
        }
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
            .setContentText("Analisis layar aktif")
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
        processor?.stop()
        processor = null
        serviceScope.cancel()
        overlayManager?.hideOverlay()
        mediaProjection?.stop()
        mediaProjection = null
        ChessAssistantStatusBus.update(ChessAssistantState.Idle)
        super.onDestroy()
    }
}
