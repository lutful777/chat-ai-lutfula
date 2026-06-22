package com.example.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class CloudMemoryClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = "https://chat-ai-lutfula.vercel.app"
) {
    fun saveMemory(content: String, category: String, source: String): Boolean {
        if (content.isBlank()) return false
        return try {
            val bodyJson = JSONObject()
                .put("content", content)
                .put("category", category)
                .put("source", source)
                .put("client", "android")
                .toString()

            val request = Request.Builder()
                .url("$baseUrl/api/memory/save")
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    fun searchMemories(query: String): List<MemoryEntity> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/memory/search?query=$encodedQuery")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                if (!response.isSuccessful) return emptyList()
                parseMemoryResponse(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMemoryResponse(body: String): List<MemoryEntity> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("memories")
                    ?: root.optJSONArray("documents")
                    ?: root.optJSONArray("data")
                    ?: JSONArray()
            }
        }

        val result = mutableListOf<MemoryEntity>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val content = item.optString("content", item.optString("memory", "")).trim()
            if (content.isBlank()) continue
            result.add(
                MemoryEntity(
                    content = content,
                    category = item.optString("category", "cloud"),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                    source = item.optString("source", "appwrite")
                )
            )
        }
        return result
    }
}
