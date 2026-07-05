package com.example.chess.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chess.capture.ScreenCaptureService
import com.example.chess.data.ChessSettingsRepository
import com.example.chess.domain.ChessAssistantState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessAssistantScreen(
    viewModel: ChessAssistantViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val settingsRepository = remember(context.applicationContext) {
        ChessSettingsRepository(context.applicationContext)
    }
    val featureEnabled by settingsRepository.enabled.collectAsState(initial = true)
    val engineDepth by settingsRepository.depth.collectAsState(initial = 3)
    val framesPerSecond by settingsRepository.fps.collectAsState(initial = 1)
    val minimumConfidence by settingsRepository.minConfidence.collectAsState(initial = 0.15f)
    val showEvaluation by settingsRepository.showEval.collectAsState(initial = true)
    val showArrow by settingsRepository.showArrow.collectAsState(initial = true)

    var continueAfterOverlayPermission by remember { mutableStateOf(false) }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val capturePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_DEPTH, engineDepth)
                putExtra(ScreenCaptureService.EXTRA_FPS, framesPerSecond)
                putExtra(ScreenCaptureService.EXTRA_MIN_CONFIDENCE, minimumConfidence)
                putExtra(ScreenCaptureService.EXTRA_SHOW_EVALUATION, showEvaluation)
                putExtra(ScreenCaptureService.EXTRA_SHOW_ARROW, showArrow)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            viewModel.onPermissionDenied()
        }
    }

    val launchCapturePermission = {
        viewModel.startCapture()
        capturePermission.launch(projectionManager.createScreenCaptureIntent())
    }

    val overlayPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (continueAfterOverlayPermission) {
            continueAfterOverlayPermission = false
            if (AndroidSettings.canDrawOverlays(context)) {
                launchCapturePermission()
            } else {
                viewModel.onOverlayPermissionDenied()
            }
        }
    }

    fun startWithRequiredPermissions() {
        if (!featureEnabled) {
            viewModel.onFeatureDisabled()
            return
        }

        if (showArrow &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !AndroidSettings.canDrawOverlays(context)) {
            continueAfterOverlayPermission = true
            val intent = Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermission.launch(intent)
        } else {
            launchCapturePermission()
        }
    }

    fun stopService() {
        val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(stopIntent)
        viewModel.stopCapture()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chess Screen Assistant") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Pengaturan")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Pembacaan Layar dan Panah Langkah",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (showArrow) {
                    "Tekan Mulai, berikan izin tampil di atas aplikasi lain dan izin perekaman layar, " +
                        "lalu buka aplikasi catur. Panah akan menunjukkan petak asal dan tujuan."
                } else {
                    "Tekan Mulai, izinkan perekaman layar, lalu buka aplikasi catur. " +
                        "Petunjuk langkah akan tampil di notifikasi dan halaman ini."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (!featureEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Fitur sedang dinonaktifkan dari pengaturan.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            when (val currentState = state) {
                ChessAssistantState.Idle -> Text("Siap membaca layar")
                ChessAssistantState.RequestingPermission -> Text("Menunggu izin sistem…")
                ChessAssistantState.CapturingScreen -> Text("Pembacaan layar aktif")
                ChessAssistantState.SearchingBoard -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Mencari papan catur di layar…")
                }
                ChessAssistantState.RecognizingPosition -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Membaca posisi papan…")
                }
                ChessAssistantState.Analyzing -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Menghitung langkah terbaik…")
                }
                is ChessAssistantState.Waiting -> Text(currentState.message)
                is ChessAssistantState.Result -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Langkah terbaik: ${currentState.bestMove}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text("Petunjuk: ${formatMove(currentState.bestMove)}")
                            Text("Evaluasi: ${currentState.evaluation}")
                            if (currentState.depth > 0) {
                                Text("Kedalaman: ${currentState.depth}")
                            }
                            Text(
                                text = "FEN: ${currentState.fen}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                is ChessAssistantState.Error -> {
                    Text(
                        text = currentState.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = ::startWithRequiredPermissions,
                    enabled = featureEnabled &&
                        (state == ChessAssistantState.Idle || state is ChessAssistantState.Error)
                ) {
                    Text("Mulai Membaca Layar")
                }

                OutlinedButton(
                    onClick = ::stopService,
                    enabled = state != ChessAssistantState.Idle
                ) {
                    Text("Stop")
                }
            }
        }
    }
}

private fun formatMove(move: String): String {
    if (move.length < 4) return move
    return "${move.substring(0, 2).uppercase()} → ${move.substring(2, 4).uppercase()}"
}
