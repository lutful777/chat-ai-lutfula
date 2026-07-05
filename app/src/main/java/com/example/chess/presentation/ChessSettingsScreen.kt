package com.example.chess.presentation

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
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    val enabled by repository.enabled.collectAsState(initial = true)
    val depth by repository.depth.collectAsState(initial = 10)
    val fps by repository.fps.collectAsState(initial = 1)
    val showEval by repository.showEval.collectAsState(initial = true)
    val showArrow by repository.showArrow.collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chess Assistant Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
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
                Text("Aktifkan fitur", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = enabled,
                    onCheckedChange = { coroutineScope.launch { repository.updateEnabled(it) } }
                )
            }

            HorizontalDivider()

            Text("Kedalaman mesin: $depth", modifier = Modifier.padding(top = 16.dp))
            Slider(
                value = depth.toFloat(),
                onValueChange = { coroutineScope.launch { repository.updateDepth(it.toInt()) } },
                valueRange = 1f..20f,
                steps = 18
            )

            Text("Kecepatan pembacaan: $fps FPS", modifier = Modifier.padding(top = 16.dp))
            Slider(
                value = fps.toFloat(),
                onValueChange = { coroutineScope.launch { repository.updateFps(it.toInt()) } },
                valueRange = 1f..5f,
                steps = 3
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

            Text(
                text = "Catatan: mesin lokal bawaan membatasi kedalaman efektif hingga 3 agar tetap ringan di ponsel.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
