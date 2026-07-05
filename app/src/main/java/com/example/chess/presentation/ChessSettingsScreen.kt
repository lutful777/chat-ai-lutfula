package com.example.chess.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chess.data.ChessSettingsRepository
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessSettingsScreen(
    repository: ChessSettingsRepository,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    val enabled by repository.enabled.collectAsState(initial = true)
    val depth by repository.depth.collectAsState(initial = 3)
    val fps by repository.fps.collectAsState(initial = 1)
    val minConfidence by repository.minConfidence.collectAsState(initial = 0.15f)
    val showEval by repository.showEval.collectAsState(initial = true)
    val showArrow by repository.showArrow.collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan Chess Assistant") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali"
                        )
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
            SettingSwitchRow(
                label = "Aktifkan fitur",
                checked = enabled,
                onCheckedChange = {
                    coroutineScope.launch { repository.updateEnabled(it) }
                }
            )

            HorizontalDivider()

            Text(
                text = "Kedalaman mesin: $depth",
                modifier = Modifier.padding(top = 16.dp)
            )
            Slider(
                value = depth.toFloat(),
                onValueChange = {
                    coroutineScope.launch { repository.updateDepth(it.toInt()) }
                },
                valueRange = 1f..3f,
                steps = 1
            )

            Text(
                text = "Kecepatan pembacaan: $fps FPS",
                modifier = Modifier.padding(top = 16.dp)
            )
            Slider(
                value = fps.toFloat(),
                onValueChange = {
                    coroutineScope.launch { repository.updateFps(it.toInt()) }
                },
                valueRange = 1f..3f,
                steps = 1
            )

            Text(
                text = "Ambang keyakinan papan: ${String.format(Locale.US, "%.2f", minConfidence)}",
                modifier = Modifier.padding(top = 16.dp)
            )
            Slider(
                value = minConfidence,
                onValueChange = {
                    coroutineScope.launch { repository.updateMinConfidence(it) }
                },
                valueRange = 0.05f..0.50f,
                steps = 8
            )

            SettingSwitchRow(
                label = "Tampilkan evaluasi",
                checked = showEval,
                onCheckedChange = {
                    coroutineScope.launch { repository.updateShowEval(it) }
                }
            )

            SettingSwitchRow(
                label = "Tampilkan panah",
                checked = showArrow,
                onCheckedChange = {
                    coroutineScope.launch { repository.updateShowArrow(it) }
                }
            )

            Text(
                text = "Perubahan diterapkan saat sesi pembacaan layar berikutnya dimulai.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
