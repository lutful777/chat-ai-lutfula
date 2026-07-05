package com.example.chess.capture

import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.example.chess.engine.ChessEngineFactory
import com.example.chess.engine.EngineSettings
import com.example.chess.overlay.ChessOverlayManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlayManager = ChessOverlayManager(this)
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != -1 && data != null && !isCapturing) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            startCapture()
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        isCapturing = true
        serviceScope.launch {
            val settingsRepository = ChessSettingsRepository(this@ScreenCaptureService)
            
            val enabled = settingsRepository.enabled.first()
            val onlineEnabled = settingsRepository.onlineEnabled.first()
            val endpointUrl = settingsRepository.endpointUrl.first()
            val localFallback = settingsRepository.localFallback.first()
            val showEval = settingsRepository.showEval.first()
            val showArrow = settingsRepository.showArrow.first()
            
            val engineSettings = EngineSettings(onlineEnabled, endpointUrl, localFallback, showEval, showArrow)
            val engine = ChessEngineFactory.createEngine(this@ScreenCaptureService, engineSettings)
            processor = ScreenFrameProcessor(engine)
            processor?.updateSettings(engineSettings)
            
            launch {
                processor?.resultFlow?.collect { state ->
                    withContext(Dispatchers.Main) {
                        when (state) {
                            is ProcessorState.Result -> {
                                overlayManager?.showOverlay(
                                    state.result.bestMove,
                                    if (engineSettings.showEval) state.result.evaluation else "",
                                    state.result.depth,
                                    state.result.ponderMove ?: "",
                                    state.lockedBottomSide,
                                    state.result.isLocalFallback
                                )
                            }
                            is ProcessorState.WaitingForOpponent -> {
                                overlayManager?.showWaiting()
                            }
                            is ProcessorState.Analyzing -> {
                                overlayManager?.showAnalyzing()
                            }
                            is ProcessorState.NetworkError -> {
                                overlayManager?.showNetworkError()
                            }
                            else -> overlayManager?.hideOverlay()
                        }
                    }
                }
            }

            var toggle = true
            while (isActive && isCapturing) {
                if (settingsRepository.enabled.first()) {
                    val fen = if (toggle) "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" else "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
                    processor?.processFrame(fen)
                    toggle = !toggle
                } else {
                    overlayManager?.hideOverlay()
                }
                delay(2000)
            }
        }
    }

    private fun startForegroundService() {
        val channelId = "screen_capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Chess Assistant")
            .setContentText("Capturing screen...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        processor?.stop()
        serviceScope.cancel()
        overlayManager?.hideOverlay()
        mediaProjection?.stop()
    }
}
