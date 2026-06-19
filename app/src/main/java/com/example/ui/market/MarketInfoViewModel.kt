package com.example.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class MarketInfoUiState(
    val priceData: String = "",
    val newsData: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

class MarketInfoViewModel(
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketInfoUiState())
    val uiState: StateFlow<MarketInfoUiState> = _uiState

    fun fetchMarketPrices() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana&vs_currencies=usd,idr&include_24hr_change=true")
                    .header("User-Agent", "AiChatMobile/1.0")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, error = "Gagal mengambil harga: HTTP ${response.code}") }
                    return@launch
                }

                val formatted = formatPrices(JSONObject(body))
                _uiState.update { it.copy(priceData = formatted, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = formatNetworkError(e)) }
            }
        }
    }

    fun fetchBtcNews() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val query = URLEncoder.encode("bitcoin OR btc cryptocurrency", "UTF-8")
                val newsUrl = "https://api.gdeltproject.org/api/v2/doc/doc?query=$query&mode=artlist&format=json&maxrecords=10&sort=hybridrel"
                val request = Request.Builder()
                    .url(newsUrl)
                    .header("User-Agent", "AiChatMobile/1.0")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, error = "Gagal mengambil news BTC: HTTP ${response.code}") }
                    return@launch
                }

                val formatted = formatRealtimeBtcNews(body)
                _uiState.update { it.copy(newsData = formatted, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = formatNetworkError(e)) }
            }
        }
    }

    private fun formatPrices(json: JSONObject): String {
        val coins = listOf(
            Triple("bitcoin", "BTC", "Bitcoin"),
            Triple("ethereum", "ETH", "Ethereum"),
            Triple("solana", "SOL", "Solana")
        )
        return coins.joinToString("\n\n") { (id, symbol, name) ->
            val item = json.optJSONObject(id)
            if (item == null) {
                "$symbol ($name): data tidak tersedia"
            } else {
                val usd = item.optDouble("usd", Double.NaN)
                val idr = item.optDouble("idr", Double.NaN)
                val change = item.optDouble("usd_24h_change", Double.NaN)
                "$symbol ($name)\nUSD: ${formatUsd(usd)}\nIDR: ${formatIdr(idr)}\n24h: ${formatPercent(change)}"
            }
        } + "\n\nInfo harga hanya read-only."
    }

    private fun formatRealtimeBtcNews(body: String): String {
        return try {
            val json = JSONObject(body)
            val articles = json.optJSONArray("articles")
            if (articles == null || articles.length() == 0) {
                return "News BTC realtime belum tersedia dari sumber saat ini. Coba refresh beberapa menit lagi."
            }

            val articleTexts = mutableListOf<String>()
            val allTitles = mutableListOf<String>()
            val maxItems = minOf(6, articles.length())

            for (i in 0 until maxItems) {
                val item = articles.optJSONObject(i) ?: continue
                val title = item.optString("title", "No title").trim()
                val url = item.optString("url", "").trim()
                val domain = item.optString("domain", "").trim()
                val seenDate = item.optString("seendate", "").trim()

                if (title.isBlank()) continue
                allTitles.add(title)
                articleTexts.add(buildString {
                    append("${i + 1}. $title")
                    if (domain.isNotBlank()) append("\nSumber: $domain")
                    if (seenDate.isNotBlank()) append(" | Waktu: $seenDate")
                    if (url.isNotBlank()) append("\n$url")
                })
            }

            val joinedTitles = allTitles.joinToString(". ")
            val sentiment = analyzeBtcNewsSentiment(joinedTitles)

            buildString {
                append("BTC News Realtime\n")
                append("Sentimen berita: ${sentiment.label}\n")
                append("Alasan utama: ${sentiment.reason}\n\n")
                append("Berita yang paling berpengaruh:\n")
                append(articleTexts.joinToString("\n\n"))
                append("\n\nRingkasan dampak:\n")
                append(sentiment.impact)
                append("\n\nCatatan: analisis ini hanya informasi berita, bukan saran finansial atau instruksi transaksi.")
            }
        } catch (e: Exception) {
            if (body.isBlank()) "News response kosong." else body.take(4000)
        }
    }

    private data class NewsSentiment(
        val label: String,
        val reason: String,
        val impact: String
    )

    private fun analyzeBtcNewsSentiment(text: String): NewsSentiment {
        val lower = text.lowercase(Locale.US)
        val negativeKeywords = listOf(
            "hack", "hacked", "exploit", "lawsuit", "ban", "crackdown", "probe", "investigation",
            "outflow", "selloff", "sell-off", "liquidation", "dump", "falls", "drops", "slumps",
            "rate hike", "hawkish", "inflation", "recession", "risk-off"
        )
        val positiveKeywords = listOf(
            "etf inflow", "inflow", "approval", "approves", "adoption", "institutional", "accumulation",
            "buys", "purchase", "rallies", "surges", "jumps", "record high", "breakout", "easing", "rate cut"
        )

        val negativeScore = negativeKeywords.count { lower.contains(it) }
        val positiveScore = positiveKeywords.count { lower.contains(it) }

        return when {
            negativeScore > positiveScore -> NewsSentiment(
                label = "Bearish / hati-hati",
                reason = "headline lebih banyak memuat risiko seperti tekanan jual, regulasi, makro ketat, atau sentimen risk-off.",
                impact = "BTC bisa tetap tertekan dalam jangka pendek sampai muncul katalis positif atau tekanan jual mereda."
            )
            positiveScore > negativeScore -> NewsSentiment(
                label = "Bullish ringan",
                reason = "headline lebih banyak memuat dukungan seperti inflow, adopsi, pembelian institusi, atau reli harga.",
                impact = "BTC berpeluang mendapat dukungan sentimen, tetapi tetap perlu dikonfirmasi oleh volume dan pergerakan harga."
            )
            else -> NewsSentiment(
                label = "Netral / wait and see",
                reason = "headline belum menunjukkan dominasi sentimen positif atau negatif yang kuat.",
                impact = "BTC cenderung menunggu katalis baru seperti data makro, arus ETF, regulasi, atau pergerakan institusi."
            )
        }
    }

    private fun formatUsd(value: Double): String {
        if (value.isNaN()) return "N/A"
        return "$" + String.format(Locale.US, "%,.2f", value)
    }

    private fun formatIdr(value: Double): String {
        if (value.isNaN()) return "N/A"
        return "Rp " + String.format(Locale.US, "%,.0f", value)
    }

    private fun formatPercent(value: Double): String {
        if (value.isNaN()) return "N/A"
        val sign = if (value >= 0) "+" else ""
        return sign + String.format(Locale.US, "%.2f", value) + "%"
    }

    private fun formatNetworkError(e: Exception): String {
        val detail = e.message ?: "No detail"
        return "Network Error: ${e.javaClass.simpleName} - $detail"
    }

    class Factory(private val okHttpClient: OkHttpClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MarketInfoViewModel::class.java)) {
                return MarketInfoViewModel(okHttpClient) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
