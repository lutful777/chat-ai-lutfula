package com.example.data

import java.util.Locale

/** Detects crypto price questions and extracts the coin query to search in CoinGecko. */
data class CryptoQuery(
    val query: String,
    val originalText: String
)

object CryptoDetector {
    private val directAliases: Map<String, String> = mapOf(
        "btc" to "bitcoin",
        "bitcoin" to "bitcoin",
        "eth" to "ethereum",
        "ethereum" to "ethereum",
        "sol" to "solana",
        "solana" to "solana",
        "bnb" to "binancecoin",
        "binance" to "binancecoin",
        "binancecoin" to "binancecoin",
        "xrp" to "ripple",
        "ripple" to "ripple",
        "doge" to "dogecoin",
        "dogecoin" to "dogecoin",
        "usdt" to "tether",
        "tether" to "tether"
    )

    private val cryptoIntentWords = listOf(
        "crypto", "kripto", "coin", "token", "altcoin", "blockchain", "wallet",
        "market cap crypto", "volume crypto", "airdrop", "defi", "nft"
    )

    private val webOrTravelWords = listOf(
        "http://", "https://", ".com", ".id", ".net", ".org", ".dev", ".app", ".co",
        "website", "web", "link", "browser", "internet", "tiket", "pesawat", "hotel",
        "booking", "travel", "refund", "reschedule", "promo", "voucher", "checkout",
        "produk", "barang", "belanja", "marketplace", "shopee", "tokopedia", "lazada",
        "tiket.com", "traveloka", "agoda", "booking.com"
    )

    private val stopWords = setOf(
        "harga", "price", "berapa", "realtime", "market", "cap", "volume", "kurs", "rate",
        "usd", "idr", "rupiah", "dollar", "dolar", "coin", "crypto", "kripto", "token",
        "ke", "to", "in", "dalam", "sekarang", "hari", "ini", "cek", "chek", "tolong", "dong", "ya",
        "naik", "turun", "berita", "news", "sentimen", "kenapa", "analisa", "analisis"
    )

    fun detect(message: String): CryptoQuery? {
        val text = message.trim().lowercase(Locale.US)
        if (text.isBlank()) return null

        if (looksLikeWebOrTravel(text)) return null

        directAliases.forEach { (alias, coinId) ->
            if (Regex("(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])").containsMatchIn(text)) {
                return CryptoQuery(query = coinId, originalText = message)
            }
        }

        val hasCryptoIntent = cryptoIntentWords.any { text.contains(it) }
        if (!hasCryptoIntent) return null

        val cleaned = text
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopWords && it.length >= 2 }

        val query = cleaned.take(4).joinToString(" ").trim()
        if (query.isBlank()) return null

        return CryptoQuery(query = query, originalText = message)
    }

    private fun looksLikeWebOrTravel(text: String): Boolean {
        if (webOrTravelWords.any { text.contains(it) }) return true
        return Regex("(?<![a-z0-9])([a-z0-9-]+\\.)+[a-z]{2,}(?![a-z0-9])").containsMatchIn(text)
    }
}
