package com.example.ui.market

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketInfoScreen(
    viewModel: MarketInfoViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

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

            BtcChartCard()
            PriceCard(uiState.priceData, onRefresh = { viewModel.fetchMarketPrices() })
            NewsCard(uiState.newsData, onRefresh = { viewModel.fetchBtcNews() })

            Text(
                text = "Harga, chart, dan news hanya untuk informasi.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun BtcChartCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2C))) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("BTCUSDT Chart 15m", color = Color.White, style = MaterialTheme.typography.titleMedium)
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        loadDataWithBaseURL(
                            "https://www.tradingview.com",
                            tradingViewHtml(),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                }
            )
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

@Composable
private fun NewsCard(newsData: String, onRefresh: () -> Unit) {
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
                Text("News BTC")
            }
            if (newsData.isNotBlank()) {
                Text("Berita BTC terbaru:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
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
            }
        }
    }
}

private fun tradingViewHtml(): String = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    html, body { margin:0; padding:0; height:100%; background:#13131A; overflow:hidden; }
    .tradingview-widget-container { height:100%; width:100%; }
    #tradingview_chart { height:100%; width:100%; }
  </style>
</head>
<body>
  <div class="tradingview-widget-container">
    <div id="tradingview_chart"></div>
  </div>
  <script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script>
  <script type="text/javascript">
    new TradingView.widget({
      "autosize": true,
      "symbol": "BINGX:BTCUSDT",
      "interval": "15",
      "timezone": "Asia/Jakarta",
      "theme": "dark",
      "style": "1",
      "locale": "en",
      "toolbar_bg": "#13131A",
      "enable_publishing": false,
      "hide_side_toolbar": false,
      "allow_symbol_change": true,
      "container_id": "tradingview_chart"
    });
  </script>
</body>
</html>
""".trimIndent()
