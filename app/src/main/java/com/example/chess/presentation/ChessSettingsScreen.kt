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
    onNavigateBack: () -> Unit,
    onNavigateToAttribution: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val enabled by repository.enabled.collectAsState(initial = true)
    val onlineEnabled by repository.onlineEnabled.collectAsState(initial = true)
    val endpointUrl by repository.endpointUrl.collectAsState(
        initial = ChessSettingsRepository.DEFAULT_STOCKFISH_ENDPOINT
    )
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
            SettingSwitch("Aktifkan fitur", enabled) {
                scope.launch { repository.updateEnabled(it) }
            }

            HorizontalDivider()
            Text(
                "Stockfish online",
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )

            SettingSwitch("Gunakan server online", onlineEnabled) {
                scope.launch { repository.updateOnlineEnabled(it) }
            }

            OutlinedTextField(
                value = endpointUrl,
                onValueChange = { value -> scope.launch { repository.updateEndpointUrl(value) } },
                label = { Text("URL endpoint") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true
            )

            SettingSwitch("Fallback lokal", localFallback) {
                scope.launch { repository.updateLocalFallback(it) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Capture FPS: $fps", modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = fps.toFloat(),
                onValueChange = { value -> scope.launch { repository.updateFps(value.toInt()) } },
                valueRange = 1f..3f,
                steps = 1
            )

            SettingSwitch("Tampilkan evaluasi", showEval) {
                scope.launch { repository.updateShowEval(it) }
            }
            SettingSwitch("Tampilkan panah", showArrow) {
                scope.launch { repository.updateShowArrow(it) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Button(onClick = onNavigateToAttribution, modifier = Modifier.fillMaxWidth()) {
                Text("Lisensi & Atribusi Stockfish")
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
