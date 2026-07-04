package com.example.ui.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

internal data class AutomaticBrowserResult(
    val context: String,
    val success: Boolean
)

internal class AutomaticBrowserRepository(
    private val okHttpClient: OkHttpClient
) {
    fun search(query: String, mode: String): AutomaticBrowserResult {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val encodedMode = URLEncoder.encode(mode, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/api/search?q=$encodedQuery&mode=$encodedMode")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    return failure("Pencarian web gagal dengan HTTP ${response.code}.")
                }

                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data == null || data.length() == 0) {
                    return failure("Pencarian web tidak menemukan hasil yang dapat digunakan.")
                }

                val context = StringBuilder()
                context.appendLine("Status browser: berhasil")
                context.appendLine("Kueri: $query")
                context.appendLine()

                for (index in 0 until minOf(5, data.length())) {
                    val item = data.optJSONObject(index) ?: continue
                    val title = item.optString("title", "Tanpa judul")
                    val description = item.optString("description", "").take(1600)
                    val url = item.optString("url", "")

                    context.appendLine("Hasil ${index + 1}: $title")
                    if (url.isNotBlank()) context.appendLine("URL: $url")
                    if (description.isNotBlank()) context.appendLine("Isi: $description")
                    context.appendLine()
                }

                context.appendLine("Gunakan hasil web ini untuk menjawab pertanyaan pengguna.")
                context.appendLine("Sertakan URL sumber yang relevan pada jawaban.")
                AutomaticBrowserResult(context.toString(), true)
            }
        } catch (error: Exception) {
            failure("Pencarian web gagal: ${error.message ?: "kesalahan tidak diketahui"}.")
        }
    }

    fun readUrl(url: String): AutomaticBrowserResult {
        return try {
            val body = JSONObject()
                .put("url", url)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$BASE_URL/api/read-url")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                    return failure("Browser gagal membaca $url dengan HTTP ${response.code}.")
                }

                val pageText = try {
                    val json = JSONObject(responseBody)
                    json.optJSONObject("data")?.optString("markdown")
                        ?.takeIf { it.isNotBlank() }
                        ?: json.optString("markdown", "")
                } catch (_: Exception) {
                    responseBody
                }

                if (pageText.isBlank()) {
                    return failure("Halaman $url tidak menghasilkan teks yang dapat dibaca.")
                }

                val safeText = pageText.take(10000)
                AutomaticBrowserResult(
                    context = buildString {
                        appendLine("Status browser: berhasil")
                        appendLine("URL: $url")
                        appendLine()
                        appendLine("Isi halaman:")
                        appendLine(safeText)
                        appendLine()
                        appendLine("Jawab pertanyaan pengguna berdasarkan isi halaman ini.")
                    },
                    success = true
                )
            }
        } catch (error: Exception) {
            failure("Browser gagal membaca $url: ${error.message ?: "kesalahan tidak diketahui"}.")
        }
    }

    private fun failure(message: String): AutomaticBrowserResult {
        return AutomaticBrowserResult(
            context = "Status browser: gagal\nDetail: $message",
            success = false
        )
    }

    private companion object {
        const val BASE_URL = "https://chat-ai-lutfula.vercel.app"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
