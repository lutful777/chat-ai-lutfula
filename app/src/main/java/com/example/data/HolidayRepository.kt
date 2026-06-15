package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.Calendar

class HolidayRepository(private val okHttpClient: OkHttpClient) {
    // Cache: "YYYY-MM-DD" -> Holiday result String
    private val cache = mutableMapOf<String, String>()

    // Use default country ID
    private val defaultCountry = "ID"

    fun isWorkingDay(targetDate: Date, apiNinjasKeyFallback: String): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        val dateStr = sdf.format(targetDate)

        if (cache.containsKey(dateStr)) {
            return cache[dateStr] ?: ""
        }

        var result = ""
        var apiNinjasKey = com.example.BuildConfig.API_NINJAS_API_KEY
        if (apiNinjasKey.isBlank() || apiNinjasKey == "YOUR_API_NINJAS_API_KEY" || apiNinjasKey == "\"YOUR_API_NINJAS_API_KEY\"") {
            apiNinjasKey = apiNinjasKeyFallback
        }

        if (apiNinjasKey.isBlank() || apiNinjasKey == "YOUR_API_NINJAS_API_KEY" || apiNinjasKey == "\"YOUR_API_NINJAS_API_KEY\"") {
            return "API Ninjas key belum dikonfigurasi."
        }
        
        try {
                val request = Request.Builder()
                    .url("https://api.api-ninjas.com/v1/isworkingday?country=$defaultCountry&date=$dateStr")
                    .addHeader("X-Api-Key", apiNinjasKey)
                    .build()
                    
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string()
                
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val isWorkingDay = json.optBoolean("is_working_day", true)
                    var holidayName = ""
                    var resultStr = ""
                    
                    try {
                        // Fetch public holidays
                        val yearStr = dateStr.substring(0, 4)
                        val cacheKeyHoliday = "holiday_$yearStr"
                        val hBody = if (cache.containsKey(cacheKeyHoliday)) {
                            cache[cacheKeyHoliday]
                        } else {
                            val hRequest = Request.Builder()
                                .url("https://api.api-ninjas.com/v1/publicholidays?country=$defaultCountry&year=$yearStr")
                                .addHeader("X-Api-Key", apiNinjasKey)
                                .build()
                            val hResponse = okHttpClient.newCall(hRequest).execute()
                            val bodyText = hResponse.body?.string()
                            if (hResponse.isSuccessful && bodyText != null) {
                                cache[cacheKeyHoliday] = bodyText
                                bodyText
                            } else null
                        }
                        
                        if (hBody != null) {
                            val hArr = JSONArray(hBody)
                            for (i in 0 until hArr.length()) {
                                val obj = hArr.optJSONObject(i)
                                if (obj != null && obj.optString("date") == dateStr) {
                                    holidayName = obj.optString("name", "Public Holiday")
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                    
                    if (holidayName.isNotEmpty()) {
                        resultStr = "Tanggal merah nasional: Yes. Libur: $holidayName. Status isWorkingDay: $isWorkingDay."
                    } else if (!isWorkingDay) {
                        resultStr = "Tanggal merah nasional: Yes (Weekend/Non-working day). Status isWorkingDay: $isWorkingDay."
                    } else {
                        resultStr = "Tanggal merah nasional: No (Working Day)."
                    }
                    
                    try {
                        val yearStr = dateStr.substring(0, 4)
                        val cacheKeyHoliday = "holiday_$yearStr"
                        val hBody = cache[cacheKeyHoliday]
                        if (hBody != null) {
                            val hArr = JSONArray(hBody)
                            var allHolidays = "\n\nDaftar hari libur tahun $yearStr:\n"
                            for (i in 0 until hArr.length()) {
                                val obj = hArr.optJSONObject(i)
                                if (obj != null) {
                                    allHolidays += "- ${obj.optString("date")}: ${obj.optString("name")}\n"
                                }
                            }
                            resultStr += allHolidays
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                    
                    cache[dateStr] = resultStr
                    return resultStr
                }
            } catch (e: Exception) {
                return "Gagal mengecek data libur. Periksa koneksi internet atau API key."
            }
        
        return result
    }
}
