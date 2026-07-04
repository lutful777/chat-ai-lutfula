package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HolidayRepository(private val okHttpClient: OkHttpClient) {
    // Cache: "COUNTRY:YYYY-MM-DD" -> Holiday result String
    private val cache = mutableMapOf<String, String>()

    // Indonesia remains the default when the user does not mention a country.
    private val defaultCountry = "ID"
    private val supportedCountryCodes = Locale.getISOCountries().toSet()

    fun isWorkingDay(targetDate: Date, countryCode: String = defaultCountry): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        val dateStr = sdf.format(targetDate)
        val normalizedCountry = countryCode
            .uppercase(Locale.ROOT)
            .takeIf { it in supportedCountryCodes }
            ?: defaultCountry
        val cacheKey = "$normalizedCountry:$dateStr"

        cache[cacheKey]?.let { return it }

        return try {
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/holiday?date=$dateStr&country=$normalizedCountry")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful && body != null) {
                val resultStr = JSONObject(body).optString("result", body)
                cache[cacheKey] = resultStr
                resultStr
            } else {
                "Backend realtime belum tersedia atau gagal mengambil data."
            }
        } catch (e: Exception) {
            "Backend realtime belum tersedia atau gagal mengambil data."
        }
    }
}
