package com.example.chess.presentation

import android.graphics.BitmapFactory
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

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            viewModel.onPermissionDenied()
        } else {
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
            if (bitmap == null) {
                viewModel.onPermissionDenied()
            } else {
                viewModel.analyzeBitmap(bitmap)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chess Screen Assistant") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
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
            Text(text = "Analisis Screenshot Catur", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Ambil screenshot papan mulai dari posisi awal, lalu pilih gambarnya di sini. " +
                    "Untuk langkah berikutnya, pilih screenshot terbaru dari permainan yang sama.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(20.dp))

            when (val currentState = state) {
                ChessAssistantState.Idle -> Text("Siap menganalisis")
                ChessAssistantState.RequestingPermission -> Text("Pilih screenshot dari galeri…")
                ChessAssistantState.CapturingScreen -> Text("Gambar diterima")
                ChessAssistantState.SearchingBoard -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Mencari papan catur…")
                }
                ChessAssistantState.RecognizingPosition -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Membaca perubahan posisi…")
                }
                ChessAssistantState.Analyzing -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Menghitung langkah…")
                }
                is ChessAssistantState.Result -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Langkah terbaik: ${currentState.bestMove}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text("Evaluasi: ${currentState.evaluation}")
                            if (currentState.depth > 0) Text("Kedalaman: ${currentState.depth}")
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
                    onClick = {
                        viewModel.startCapture()
                        imagePicker.launch("image/*")
                    },
                    enabled = state !is ChessAssistantState.SearchingBoard &&
                        state !is ChessAssistantState.RecognizingPosition &&
                        state !is ChessAssistantState.Analyzing
                ) {
                    Text("Pilih Screenshot")
                }

                OutlinedButton(onClick = viewModel::stopCapture) {
                    Text("Reset Sesi")
                }
            }
        }
    }
}
