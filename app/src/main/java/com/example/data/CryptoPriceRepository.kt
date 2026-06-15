package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

data class BtcUsdPriceResult(val priceUsd: Double, val source: String = "CoinGecko")

class CryptoPriceRepository(private val okHttpClient: OkHttpClient) {
    fun isBtcUsdQuery(messageText: String): Boolean {
        val text = messageText.trim().lowercase(Locale.US)
        return Regex("\\b(btc|bitcoin)\\b").containsMatchIn(text) &&
            !listOf("berita", "news", "positif", "negatif", "sentimen", "kenapa", "naik", "turun", "akan").any { text.contains(it) }
    }

    fun getBtcUsdPrice(): Result<BtcUsdPriceResult> {
        return try {
            val host = "https://api." + "coingecko.com"
            val url = host + "/api/v3/simple/price?ids=bitcoin&vs_currencies=usd"
            val request = Request.Builder().url(url).get().addHeader("Accept", "application/json").build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                return Result.failure(Exception("CoinGecko HTTP ${response.code}"))
            }
            val price = JSONObject(body).getJSONObject("bitcoin").getDouble("usd")
            Result.success(BtcUsdPriceResult(priceUsd = price))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatBtcUsdAnswer(result: BtcUsdPriceResult): String {
        val usd = NumberFormat.getCurrencyInstance(Locale.US)
        usd.maximumFractionDigits = 2
        return "Harga BTC realtime:\n\n1 BTC = ${usd.format(result.priceUsd)}\nSumber: ${result.source}\n\nCatatan: harga exchange bisa sedikit berbeda."
    }
}
