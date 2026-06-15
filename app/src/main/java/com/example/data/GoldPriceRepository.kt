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

data class GoldPriceResult(
    val pricePerGramIdr: Double,
    val pricePerTroyOunceIdr: Double,
    val timestamp: String?,
    val source: String = "Metals.dev"
)

class GoldPriceRepository(
    private val okHttpClient: OkHttpClient
) {
    fun isGoldPriceQuery(messageText: String): Boolean {
        val text = messageText.trim().lowercase(Locale.US)
        if (text.isBlank()) return false
        val hasGold = Regex("\\b(emas|gold|xau)\\b").containsMatchIn(text)
        val asksPrice = listOf("harga", "price", "idr", "rupiah", "gram", "1 gram", "per gram", "sekarang", "latest", "cek").any { text.contains(it) }
        return hasGold && asksPrice
    }

    fun getGoldPriceIdr(): Result<GoldPriceResult> {
        return try {
            val metalsKey = readOptionalMetalsDevKey()
            if (metalsKey.isBlank()) {
                return Result.failure(Exception("METALS_DEV_API_KEY belum tersedia di build config."))
            }

            val gramUrl = "https://api.metals.dev/v1/latest" +
                "?api_key=$metalsKey" +
                "&currency=IDR" +
                "&unit=g"

            val ounceUrl = "https://api.metals.dev/v1/latest" +
                "?api_key=$metalsKey" +
                "&currency=IDR" +
                "&unit=toz"

            val gramJson = fetchJson(gramUrl)
            val ounceJson = fetchJson(ounceUrl)

            val gramGold = gramJson.getJSONObject("metals").getDouble("gold")
            val ounceGold = ounceJson.getJSONObject("metals").getDouble("gold")
            val timestamp = gramJson.optString("timestamp", null)

            Result.success(
                GoldPriceResult(
                    pricePerGramIdr = gramGold,
                    pricePerTroyOunceIdr = ounceGold,
                    timestamp = timestamp
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatGoldPriceAnswer(result: GoldPriceResult): String {
        val idrFormat = NumberFormat.getCurrencyInstance(Locale("in", "ID"))
        idrFormat.maximumFractionDigits = 0

        val perGram = idrFormat.format(result.pricePerGramIdr)
        val perOunce = idrFormat.format(result.pricePerTroyOunceIdr)
        val per01 = idrFormat.format(result.pricePerGramIdr * 0.1)
        val per10 = idrFormat.format(result.pricePerGramIdr * 10)

        val timeText = result.timestamp?.takeIf { it.isNotBlank() } ?: formatUtcDate()

        return "Harga emas terbaru:\n\n" +
            "1 gram emas = $perGram\n" +
            "1 troy ounce emas = $perOunce\n" +
            "0.1 gram emas = $per01\n" +
            "10 gram emas = $per10\n" +
            "Update: $timeText\n" +
            "Sumber: ${result.source}\n\n" +
            "Catatan: harga toko emas bisa berbeda karena spread, biaya cetak, pajak, dan merek."
    }

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()

        if (!response.isSuccessful || body.isNullOrBlank()) {
            throw Exception("Metals.dev HTTP ${response.code}: ${body?.take(200) ?: "empty response"}")
        }

        val json = JSONObject(body)
        if (json.optString("status") == "failure") {
            val code = json.optString("error_code", "unknown")
            val message = json.optString("error_message", "unknown error")
            throw Exception("Metals.dev error $code: $message")
        }
        return json
    }

    private fun readOptionalMetalsDevKey(): String {
        return try {
            val fieldName = listOf("METALS", "DEV", "API", "KEY").joinToString("_")
            BuildConfig::class.java.getDeclaredField(fieldName).get(null)?.toString()?.trim()?.trim('"') ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatUtcDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
