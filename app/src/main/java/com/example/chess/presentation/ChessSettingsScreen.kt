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
import com.example.chess.engine.ChessApiConfig
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
    val endpointUrl by repository.endpointUrl.collectAsState(initial = ChessApiConfig.DEFAULT_ENDPOINT_URL)
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

            Text(
                "Stockfish Online Settings",
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )

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
                supportingText = { Text("Default: ${ChessApiConfig.DEFAULT_ENDPOINT_URL}") },
                singleLine = true,
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

            Text("Capture FPS: $fps", modifier = Modifier.padding(top = 8.dp))
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
