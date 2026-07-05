import os

files = {
"app/src/main/java/com/example/chess/presentation/ChessSettingsScreen.kt": """package com.example.chess.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chess.data.ChessSettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessSettingsScreen(
    repository: ChessSettingsRepository,
    onNavigateBack: () -> Unit,
    onNavigateToAttribution: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    
    val enabled by repository.enabled.collectAsState(initial = true)
    val onlineEnabled by repository.onlineEnabled.collectAsState(initial = true)
    val endpointUrl by repository.endpointUrl.collectAsState(initial = "https://example.com/api/chess/analyze")
    val localFallback by repository.localFallback.collectAsState(initial = false)
    val fps by repository.fps.collectAsState(initial = 1)
    val showEval by repository.showEval.collectAsState(initial = true)
    val showArrow by repository.showArrow.collectAsState(initial = true)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chess Assistant Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Feature", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = enabled,
                    onCheckedChange = { coroutineScope.launch { repository.updateEnabled(it) } }
                )
            }
            
            HorizontalDivider()
            
            Text("Stockfish Online Settings", modifier = Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Server Stockfish online", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = onlineEnabled,
                    onCheckedChange = { coroutineScope.launch { repository.updateOnlineEnabled(it) } }
                )
            }
            
            OutlinedTextField(
                value = endpointUrl,
                onValueChange = { coroutineScope.launch { repository.updateEndpointUrl(it) } },
                label = { Text("URL Endpoint") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Fallback lokal", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = localFallback,
                    onCheckedChange = { coroutineScope.launch { repository.updateLocalFallback(it) } }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text("Capture FPS: \$fps", modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = fps.toFloat(),
                onValueChange = { coroutineScope.launch { repository.updateFps(it.toInt()) } },
                valueRange = 1f..5f,
                steps = 4
            )
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tampilkan evaluasi", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = showEval,
                    onCheckedChange = { coroutineScope.launch { repository.updateShowEval(it) } }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tampilkan panah", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = showArrow,
                    onCheckedChange = { coroutineScope.launch { repository.updateShowArrow(it) } }
                )
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            Button(
                onClick = onNavigateToAttribution,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Lisensi & Atribusi Stockfish")
            }
        }
    }
}
""",
"app/src/main/java/com/example/chess/capture/ScreenCaptureService.kt": """package com.example.chess.capture

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlayManager = ChessOverlayManager(this)
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

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
"""
}

for filepath, content in files.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, 'w') as f:
        f.write(content)
print("Files generated successfully part 3.")
