package com.example.data

import com.example.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CryptoPriceResult(
    val coinId: String,
    val symbol: String,
    val priceUsd: Double,
    val change24hPercent: Double?,
    val lastUpdatedAtSeconds: Long?,
    val source: String = "CoinGecko"
)

class CryptoPriceRepository(
    private val okHttpClient: OkHttpClient
) {
    fun isBtcUsdQuery(messageText: String): Boolean {
        val text = messageText.trim().lowercase(Locale.US)
        if (text.isBlank()) return false

        val exactQueries = setOf(
            "btc",
            "bitcoin",
            "btc usd",
            "btc to usd",
            "btc ke usd",
            "harga btc",
            "harga bitcoin",
            "bitcoin usd",
            "bitcoin to usd"
        )
        if (text in exactQueries) return true

        val hasBtc = Regex("\\b(btc|bitcoin)\\b").containsMatchIn(text)
        val asksPrice = listOf("harga", "price", "usd", "dollar", "dolar", "rate", "kurs", "sekarang", "latest").any { text.contains(it) }
        return hasBtc && asksPrice
    }

    fun getBtcUsdPrice(): Result<CryptoPriceResult> {
        return try {
            val url = "https://api.coingecko.com/api/v3/simple/price" +
                "?ids=bitcoin" +
                "&vs_currencies=usd" +
                "&include_24hr_change=true" +
                "&include_last_updated_at=true"

            val requestBuilder = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/json")

            readOptionalCoinGeckoKey().takeIf { it.isNotBlank() }?.let { key ->
                requestBuilder.addHeader("x-cg-demo-api-key", key)
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body.isNullOrBlank()) {
                return Result.failure(Exception("CoinGecko HTTP ${response.code}: ${body?.take(200) ?: "empty response"}"))
            }

            val json = JSONObject(body)
            val bitcoin = json.getJSONObject("bitcoin")
            val price = bitcoin.getDouble("usd")
            val change = if (bitcoin.has("usd_24h_change") && !bitcoin.isNull("usd_24h_change")) bitcoin.getDouble("usd_24h_change") else null
            val updatedAt = if (bitcoin.has("last_updated_at") && !bitcoin.isNull("last_updated_at")) bitcoin.getLong("last_updated_at") else null

            Result.success(
                CryptoPriceResult(
                    coinId = "bitcoin",
                    symbol = "BTC",
                    priceUsd = price,
                    change24hPercent = change,
                    lastUpdatedAtSeconds = updatedAt
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatBtcUsdAnswer(result: CryptoPriceResult): String {
        val usdFormat = NumberFormat.getCurrencyInstance(Locale.US)
        usdFormat.maximumFractionDigits = 2

        val price = usdFormat.format(result.priceUsd)
        val changeText = result.change24hPercent?.let {
            val sign = if (it >= 0) "+" else ""
            "$sign${String.format(Locale.US, "%.2f", it)}%"
        } ?: "tidak tersedia"

        val updatedText = result.lastUpdatedAtSeconds?.let { seconds ->
            val sdf = SimpleDateFormat("dd MMM yyyy HH:mm 'UTC'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.format(Date(seconds * 1000))
        } ?: "tidak tersedia"

        val p01 = usdFormat.format(result.priceUsd * 0.1)
        val p001 = usdFormat.format(result.priceUsd * 0.01)
        val p0001 = usdFormat.format(result.priceUsd * 0.001)

        return "Harga terbaru BTC ke USD:\n\n" +
            "1 BTC = $price\n" +
            "Perubahan 24 jam: $changeText\n" +
            "Update: $updatedText\n" +
            "Sumber: ${result.source}\n\n" +
            "Konversi cepat:\n" +
            "0.1 BTC = $p01\n" +
            "0.01 BTC = $p001\n" +
            "0.001 BTC = $p0001\n\n" +
            "Catatan: harga bisa sedikit berbeda antar exchange."
    }

    private fun readOptionalCoinGeckoKey(): String {
        return try {
            val fieldName = listOf("COINGECKO", "API", "KEY").joinToString("_")
            BuildConfig::class.java.getDeclaredField(fieldName).get(null)?.toString()?.trim()?.trim('"') ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
