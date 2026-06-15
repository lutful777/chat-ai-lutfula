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
    private val goldGramCode = "XAU_GRAM"

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
        if (Regex("\\b(btc|bitcoin|eth|ethereum|crypto)\\b").containsMatchIn(normalized)) return null

        val amount = Regex("\\b\\d+(?:\\.\\d+)?\\b").find(normalized)?.value?.toDoubleOrNull() ?: 1.0
        val hasGold = Regex("\\b(emas|gold|xau)\\b").containsMatchIn(normalized)
        val asksGoldPrice = hasGold && listOf("harga", "price", "idr", "rupiah", "gram", "sekarang", "latest", "cek").any { normalized.contains(it) }
        if (asksGoldPrice) {
            return FiatRateQuery(from = goldGramCode, to = "IDR", amount = amount)
        }

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
            if (query.from == goldGramCode) {
                return getGoldGramIdr(query.amount)
            }

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
        if (result.from == goldGramCode) {
            return formatGoldAnswer(result)
        }

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

    private fun getGoldGramIdr(amount: Double): Result<FiatRateResult> {
        return try {
            val key = readOptionalMetalsKey()
            if (key.isBlank()) {
                return Result.failure(Exception("METALS_DEV_API_KEY belum tersedia di build."))
            }

            val url = "https://api.metals.dev/v1/latest?api_key=$key&currency=IDR&unit=g"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body.isNullOrBlank()) {
                return Result.failure(Exception("Metals.dev HTTP ${response.code}: ${body?.take(200) ?: "empty response"}"))
            }

            val json = JSONObject(body)
            if (json.optString("status") == "failure") {
                return Result.failure(Exception(json.optString("error_message", "Metals.dev error")))
            }

            val goldPerGramIdr = json.getJSONObject("metals").getDouble("gold")
            val timestamp = json.optString("timestamp", null)

            Result.success(
                FiatRateResult(
                    from = goldGramCode,
                    to = "IDR",
                    amount = amount,
                    convertedAmount = amount * goldPerGramIdr,
                    rate = goldPerGramIdr,
                    date = timestamp,
                    source = "Metals.dev"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatGoldAnswer(result: FiatRateResult): String {
        val idrFormat = NumberFormat.getCurrencyInstance(Locale("in", "ID"))
        idrFormat.maximumFractionDigits = 0

        val amountText = if (result.amount == 1.0) "1 gram emas" else "${String.format(Locale.US, "%.4f", result.amount).trimEnd('0').trimEnd('.')} gram emas"
        val convertedText = idrFormat.format(result.convertedAmount)
        val perGramText = idrFormat.format(result.rate)
        val dateText = result.date ?: formatUtcDate()

        return "Harga emas terbaru:\n\n" +
            "$amountText = $convertedText\n" +
            "1 gram emas = $perGramText\n" +
            "Update: $dateText\n" +
            "Sumber: ${result.source}\n\n" +
            "Catatan: harga toko emas bisa berbeda karena spread, biaya cetak, pajak, dan merek."
    }

    private fun readOptionalMetalsKey(): String {
        return try {
            val fieldName = listOf("METALS", "DEV", "API", "KEY").joinToString("_")
            BuildConfig::class.java.getDeclaredField(fieldName).get(null)?.toString()?.trim()?.trim('"') ?: ""
        } catch (_: Exception) {
            ""
        }
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
