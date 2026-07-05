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

    fun isWorkingDay(targetDate: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        val dateStr = sdf.format(targetDate)

        if (cache.containsKey(dateStr)) {
            return cache[dateStr] ?: ""
        }

        try {
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/holiday?date=$dateStr&country=$defaultCountry")
                .build()
                
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            
            if (response.isSuccessful && body != null) {
                // If your backend returns plain text, just return it. 
                // Or if it returns JSON, parse it as needed. Since the backend handles it, let's just assume it returns text.
                // Assuming it returns text directly:
                val resultStr = JSONObject(body).optString("result", body) // If it returns JSON { result: "..." } or text
                cache[dateStr] = resultStr
                return resultStr
            } else {
                return "Backend realtime belum tersedia atau gagal mengambil data."
            }
        } catch (e: Exception) {
            return "Backend realtime belum tersedia atau gagal mengambil data."
        }
    }
}
