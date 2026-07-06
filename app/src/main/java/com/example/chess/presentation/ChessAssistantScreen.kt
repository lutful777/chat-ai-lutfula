package com.example.chess.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
            ContextCompat.startForegroundService(context, intent)
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            viewModel.onPermissionDenied(
                "Izin tampil di atas aplikasi diperlukan agar panah catur dapat ditampilkan"
            )
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
                is ChessAssistantState.Idle -> Text("Menunggu dimulai")
                is ChessAssistantState.RequestingPermission -> Text("Meminta izin overlay dan layar...")
                is ChessAssistantState.SelectingBoardArea -> {
                    CircularProgressIndicator()
                    Text("Atur kotak tepat di atas papan, lalu tekan Gunakan area")
                }
                is ChessAssistantState.CapturingScreen -> Text("Area papan aktif")
                is ChessAssistantState.SearchingBoard -> {
                    CircularProgressIndicator()
                    Text("Menunggu area papan...")
                }
                is ChessAssistantState.RecognizingPosition -> {
                    CircularProgressIndicator()
                    Text("Mengenali bidak...")
                }
                is ChessAssistantState.Analyzing -> {
                    CircularProgressIndicator()
                    Text("Menganalisis dengan Stockfish...")
                }
                is ChessAssistantState.Result -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Best Move: ${currentState.bestMove}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text("Eval: ${currentState.evaluation}")
                            Text("FEN: ${currentState.fen}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                is ChessAssistantState.Error -> {
                    Text(
                        "Error: ${currentState.message}",
                        color = MaterialTheme.colorScheme.error
                    )
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
                            val permissionIntent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayLauncher.launch(permissionIntent)
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
