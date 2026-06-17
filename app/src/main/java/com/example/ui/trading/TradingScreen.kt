package com.example.ui.trading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradingScreen(
    viewModel: TradingViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf("") }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Konfirmasi Demo Order") },
            text = { Text("Apakah Anda yakin ingin mengeksekusi order $pendingAction untuk BTC-USDT? (Hanya untuk VST/Demo account)") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    when (pendingAction) {
                        "Long" -> viewModel.openLongDemo()
                        "Short" -> viewModel.openShortDemo()
                        "Close" -> viewModel.closePositionDemo()
                    }
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BingX Demo Trading", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E2C))
            )
        },
        containerColor = Color(0xFF13131A)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.error != null) {
                Text(text = uiState.error!!, color = Color.Red, style = MaterialTheme.typography.bodyMedium)
            }
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF00FF66))
            }

            // Price Section
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.fetchPrice("BTC-USDT") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66), contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cek Harga BTC")
                    }
                    if (uiState.priceData.isNotBlank()) {
                        Text("Response JSON:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = uiState.priceData,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f)).padding(8.dp).fillMaxWidth()
                        )
                    }
                }
            }

            // Balance Section
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.fetchBalance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66), contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cek Saldo")
                    }
                    if (uiState.balanceData.isNotBlank()) {
                        Text("Response JSON:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = uiState.balanceData,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f)).padding(8.dp).fillMaxWidth()
                        )
                    }
                }
            }

            // Positions Section
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.fetchPositions() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66), contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cek Posisi")
                    }
                    if (uiState.positionData.isNotBlank()) {
                        Text("Response JSON:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = uiState.positionData,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f)).padding(8.dp).fillMaxWidth()
                        )
                    }
                }
            }
            
            // Demo Order Section
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Eksekusi Demo Order (VST Only)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { pendingAction = "Long"; showConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Open Long Demo", color = Color.White) }

                        Button(
                            onClick = { pendingAction = "Short"; showConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Open Short Demo", color = Color.White) }
                    }
                    Button(
                        onClick = { pendingAction = "Close"; showConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Close Position Demo", color = Color.White) }
                    
                    if (uiState.orderData.isNotBlank()) {
                        Text("Response JSON:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = uiState.orderData,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f)).padding(8.dp).fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
