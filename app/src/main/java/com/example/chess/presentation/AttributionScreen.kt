package com.example.chess.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lisensi & Atribusi") },
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
            Text("Stockfish Chess Engine", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Aplikasi ini menggunakan Stockfish, sebuah mesin catur open-source yang tangguh.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Lisensi: GNU General Public License v3.0 (GPLv3)", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Anda dapat menemukan source code Stockfish di:", style = MaterialTheme.typography.bodyMedium)
            Text("https://github.com/official-stockfish/Stockfish", color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Ketentuan GPLv3 mengharuskan Anda memiliki akses ke kode sumber dari program yang dilisensikan di bawahnya. " +
                "Modifikasi yang dilakukan pada Stockfish (jika ada) harus didistribusikan dengan lisensi yang sama.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
