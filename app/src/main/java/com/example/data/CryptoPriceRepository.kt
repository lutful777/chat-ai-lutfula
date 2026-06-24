package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CryptoPriceRepository(private val okHttpClient: OkHttpClient) {

    suspend fun getCryptoPrice(cryptoIds: List<String>): String = withContext(Dispatchers.IO) {
        val ids = cryptoIds.joinToString(",")
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=usd,idr&include_market_cap=true&include_24hr_vol=true&include_24hr_change=true&include_last_updated_at=true"
        
        val request = Request.Builder()
            .url(url)
            .build()
            
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Coingecko API failed with code: ${response.code}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response body")
            val json = JSONObject(body)
            
            val result = StringBuilder()
            cryptoIds.forEach { id ->
                if (json.has(id)) {
                    val data = json.getJSONObject(id)
                    val usd = data.optDouble("usd", 0.0)
                    val idr = data.optDouble("idr", 0.0)
                    val change24h = data.optDouble("usd_24h_change", 0.0)
                    val marketCap = data.optDouble("usd_market_cap", 0.0)
                    val volume = data.optDouble("usd_24h_vol", 0.0)
                    
                    val name = when(id) {
                        "bitcoin" -> "BTC"
                        "ethereum" -> "ETH"
                        "solana" -> "SOL"
                        "binancecoin" -> "BNB"
                        "ripple" -> "XRP"
                        "dogecoin" -> "DOGE"
                        "tether" -> "USDT"
                        else -> id.replaceFirstChar { it.uppercase() }
                    }
                    
                    result.append("Harga $name realtime:\n\n")
                    // Example formatting:
                    // 1 BTC = $...
                    // 1 BTC = Rp...
                    // Perubahan 24 jam = ...%
                    // Market cap = ...
                    // Volume 24 jam = ...
                    // Sumber: CoinGecko realtime API
                    
                    result.append("1 $name = $${usd}\n")
                    result.append("1 $name = Rp${idr}\n")
                    result.append("Perubahan 24 jam = $change24h%\n")
                    result.append("Market cap = $${marketCap}\n")
                    result.append("Volume 24 jam = $${volume}\n")
                    result.append("Sumber: CoinGecko realtime API\n\n")
                }
            }
            if (result.isEmpty()) {
                throw Exception("No data found for keywords")
            }
            result.toString().trim()
        }
    }
}
