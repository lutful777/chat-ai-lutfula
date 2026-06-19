package com.example.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketInfoScreen(
    viewModel: MarketInfoViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.fetchBtcNews()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crypto News & Prices", color = Color.White) },
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

            NewsCard(uiState.newsData, onRefresh = { viewModel.fetchBtcNews() })
            PriceCard(uiState.priceData, onRefresh = { viewModel.fetchMarketPrices() })

            Text(
                text = "News dan harga hanya untuk informasi.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NewsCard(newsData: String, onRefresh: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C))) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("BTC News Realtime", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66), contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh News BTC Realtime")
            }
            if (newsData.isNotBlank()) {
                Text("Ringkasan berita dan sentimen:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = newsData,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
            } else {
                Text(
                    text = "Tekan refresh untuk mengambil berita BTC realtime.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PriceCard(priceData: String, onRefresh: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C))) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66), contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cek Harga Crypto Realtime")
            }
            if (priceData.isNotBlank()) {
                Text("Harga BTC / ETH / SOL:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = priceData,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}
