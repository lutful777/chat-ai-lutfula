package com.example.chess.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chess.capture.ScreenCaptureService
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
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied("Izin membaca layar ditolak")
        }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            viewModel.onPermissionDenied("Izin tampil di atas aplikasi lain diperlukan agar panah terlihat")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chess Screen Assistant") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
            Text(text = "Status:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            when (val currentState = state) {
                ChessAssistantState.Idle -> Text("Menunggu dimulai")
                ChessAssistantState.RequestingPermission -> Text("Meminta izin overlay dan layar…")
                ChessAssistantState.CapturingScreen -> Text("Pembacaan layar aktif. Buka permainan dari posisi awal.")
                ChessAssistantState.SearchingBoard -> {
                    CircularProgressIndicator()
                    Text("Mencari papan…")
                }
                ChessAssistantState.RecognizingPosition -> {
                    CircularProgressIndicator()
                    Text("Mengenali bidak…")
                }
                ChessAssistantState.Analyzing -> {
                    CircularProgressIndicator()
                    Text("Stockfish berpikir 3000 ms…")
                }
                is ChessAssistantState.Result -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Best Move: ${currentState.bestMove}", style = MaterialTheme.typography.titleLarge)
                            Text("Eval: ${currentState.evaluation}")
                            Text("FEN: ${currentState.fen}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                is ChessAssistantState.Error -> {
                    Text("Error: ${currentState.message}", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        viewModel.startCapture()
                        if (Settings.canDrawOverlays(context)) {
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        } else {
                            val overlayIntent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayLauncher.launch(overlayIntent)
                        }
                    },
                    enabled = state == ChessAssistantState.Idle || state is ChessAssistantState.Error
                ) {
                    Text("Start")
                }

                Button(
                    onClick = {
                        viewModel.stopCapture()
                        val intent = Intent(context, ScreenCaptureService::class.java).apply {
                            action = ScreenCaptureService.ACTION_STOP
                        }
                        context.startService(intent)
                    },
                    enabled = state != ChessAssistantState.Idle
                ) {
                    Text("Stop")
                }
            }
        }
    }
}
