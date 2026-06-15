package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class FiatRateQuery(
    val from: String,
    val to: String,
    val amount: Double = 1.0
)

data class FiatRateResult(
    val from: String,
    val to: String,
    val amount: Double,
    val convertedAmount: Double,
    val rate: Double,
    val date: String?,
    val source: String = "Frankfurter"
)

class FiatRateRepository(
    private val okHttpClient: OkHttpClient
) {
    private val currencyAliases = mapOf(
        "usd" to "USD",
        "dollar" to "USD",
        "dollars" to "USD",
        "dolar" to "USD",
        "eur" to "EUR",
        "euro" to "EUR",
        "gbp" to "GBP",
        "pound" to "GBP",
        "pounds" to "GBP",
        "poundsterling" to "GBP",
        "idr" to "IDR",
        "rupiah" to "IDR",
        "rp" to "IDR",
        "jpy" to "JPY",
        "yen" to "JPY",
        "sgd" to "SGD",
        "aud" to "AUD",
        "cad" to "CAD",
        "chf" to "CHF",
        "cny" to "CNY",
        "yuan" to "CNY",
        "myr" to "MYR",
        "ringgit" to "MYR",
        "thb" to "THB",
        "baht" to "THB",
        "php" to "PHP",
        "peso" to "PHP",
        "inr" to "INR",
        "rupee" to "INR",
        "krw" to "KRW",
        "won" to "KRW",
        "try" to "TRY",
        "lira" to "TRY",
        "rub" to "RUB",
        "ruble" to "RUB",
        "aed" to "AED",
        "dirham" to "AED",
        "sar" to "SAR",
        "riyal" to "SAR"
    )

    fun parseFiatRateQuery(messageText: String): FiatRateQuery? {
        val normalized = messageText.trim().lowercase(Locale.US)
            .replace(",", ".")
            .replace("/", " ")
            .replace("-", " ")
            .replace("_", " ")

        if (normalized.isBlank()) return null
        if (Regex("\\b(btc|bitcoin|eth|ethereum|crypto|emas|gold|xau)\\b").containsMatchIn(normalized)) return null

        val amount = Regex("\\b\\d+(?:\\.\\d+)?\\b").find(normalized)?.value?.toDoubleOrNull() ?: 1.0
        val currencyMatches = currencyAliases.entries
            .filter { (alias, _) -> Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(normalized) }
            .map { it.value }
            .distinct()

        if (currencyMatches.size >= 2) {
            return FiatRateQuery(from = currencyMatches[0], to = currencyMatches[1], amount = amount)
        }

        val asksRate = listOf("kurs", "mata uang", "currency", "rate", "convert", "konversi", "berapa", "harga").any { normalized.contains(it) }
        if (!asksRate) return null

        if (currencyMatches.size == 1) {
            val from = currencyMatches.first()
            val to = if (from == "IDR") "USD" else "IDR"
            return FiatRateQuery(from = from, to = to, amount = amount)
        }

        if (normalized.contains("dolar") || normalized.contains("dollar") || normalized.contains("usd")) {
            return FiatRateQuery(from = "USD", to = "IDR", amount = amount)
        }

        return null
    }

    fun getLatestRate(query: FiatRateQuery): Result<FiatRateResult> {
        return try {
            val url = "https://api.frankfurter.dev/v2/rate/${query.from}/${query.to}"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body.isNullOrBlank()) {
                return Result.failure(Exception("Frankfurter HTTP ${response.code}: ${body?.take(200) ?: "empty response"}"))
            }

            val json = JSONObject(body)
            val base = json.optString("base", query.from)
            val quote = json.optString("quote", query.to)
            val date = json.optString("date", null)
            val rate = json.getDouble("rate")
            val convertedAmount = query.amount * rate

            Result.success(
                FiatRateResult(
                    from = base,
                    to = quote,
                    amount = query.amount,
                    convertedAmount = convertedAmount,
                    rate = rate,
                    date = date
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatFiatRateAnswer(result: FiatRateResult): String {
        val amountText = formatMoney(result.amount, result.from)
        val convertedText = formatMoney(result.convertedAmount, result.to)
        val rateText = formatMoney(result.rate, result.to)
        val dateText = result.date ?: formatUtcDate()

        return "Kurs mata uang terbaru:\n\n" +
            "$amountText = $convertedText\n" +
            "1 ${result.from} = $rateText\n" +
            "Tanggal data: $dateText\n" +
            "Sumber: ${result.source}\n\n" +
            "Catatan: kurs bank/aplikasi jual-beli bisa sedikit berbeda karena spread dan biaya."
    }

    private fun formatMoney(value: Double, currency: String): String {
        return try {
            val format = NumberFormat.getCurrencyInstance(Locale.US)
            format.currency = java.util.Currency.getInstance(currency)
            if (currency == "IDR" || currency == "JPY" || currency == "KRW") {
                format.maximumFractionDigits = 0
            } else {
                format.maximumFractionDigits = 2
            }
            format.format(value)
        } catch (_: Exception) {
            "${String.format(Locale.US, "%.2f", value)} $currency"
        }
    }

    private fun formatUtcDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
