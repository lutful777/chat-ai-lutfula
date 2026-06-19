package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

class CurrencyRepository(private val okHttpClient: OkHttpClient) {
    fun getExchangeRate(query: CurrencyQuery): String {
        val from = query.fromCode.uppercase(Locale.US)
        val to = query.toCode.uppercase(Locale.US)
        val amount = query.amount

        if (from == to) {
            return "Kurs realtime:\n${formatAmount(amount)} $from = ${formatAmount(amount)} $to\nMata uang asal dan tujuan sama."
        }

        val request = Request.Builder()
            .url("https://open.er-api.com/v6/latest/$from")
            .header("User-Agent", "AiChatMobile/1.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            return "API harga realtime gagal: Currency API HTTP ${response.code}\nPair: $from ke $to"
        }

        val json = JSONObject(body)
        val result = json.optString("result", "")
        if (result.isNotBlank() && result.lowercase(Locale.US) != "success") {
            val errorType = json.optString("error-type", "unknown")
            return "API harga realtime gagal: Currency API $errorType\nPair: $from ke $to"
        }

        val rates = json.optJSONObject("rates")
            ?: return "API harga realtime gagal: Currency API response tidak memiliki rates."

        if (!rates.has(to)) {
            return "API harga realtime gagal: Mata uang $to belum tersedia di Currency API untuk base $from."
        }

        val rate = rates.optDouble(to, Double.NaN)
        if (rate.isNaN() || rate <= 0.0) {
            return "API harga realtime gagal: Rate $from ke $to tidak valid."
        }

        val converted = amount * rate
        val updateTime = json.optString("time_last_update_utc", "")

        return buildString {
            append("Kurs realtime:\n")
            append("${formatAmount(amount)} $from = ${formatAmount(converted)} $to\n")
            append("Rate: 1 $from = ${formatAmount(rate)} $to\n")
            if (updateTime.isNotBlank()) append("Update: $updateTime\n")
            append("Sumber: Currency API realtime")
        }
    }

    private fun formatAmount(value: Double): String {
        return if (kotlin.math.abs(value) >= 1000) {
            String.format(Locale.US, "%,.2f", value)
        } else {
            String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
        }
    }
}
