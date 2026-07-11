package com.example.network

import android.content.Context
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

class HonchoMemoryClient(
    context: Context,
    private val httpClient: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val endpoint = "https://chat-ai-lutfula.vercel.app/api/honcho-memory"

    private fun userId(): String {
        val raw = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: appContext.packageName
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun getContext(sessionId: Long, message: String): String {
        return call(
            JSONObject()
                .put("action", "context")
                .put("userId", userId())
                .put("sessionId", sessionId.toString())
                .put("message", message)
        )?.optString("context", "").orEmpty()
    }

    fun saveTurn(sessionId: Long, userMessage: String, assistantMessage: String) {
        call(
            JSONObject()
                .put("action", "save")
                .put("userId", userId())
                .put("sessionId", sessionId.toString())
                .put("userMessage", userMessage)
                .put("assistantMessage", assistantMessage)
        )
    }

    private fun call(payload: JSONObject): JSONObject? {
        return try {
            val body = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(endpoint).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful || text.isBlank()) null else JSONObject(text)
            }
        } catch (_: Exception) {
            null
        }
    }
}
