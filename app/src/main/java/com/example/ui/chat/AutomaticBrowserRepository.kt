package com.example.ui.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

internal data class BrowserContextResult(
    val promptContext: String,
    val sourceLinks: String = ""
)

internal class AutomaticBrowserRepository(
    private val okHttpClient: OkHttpClient
) {
    fun search(query: String, mode: String): BrowserContextResult {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val encodedMode = URLEncoder.encode(mode, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/api/search?q=$encodedQuery&mode=$encodedMode")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string()
                if (!response.isSuccessful || responseText.isNullOrBlank()) {
                    return browserFailure(
                        "Pencarian web gagal dengan HTTP ${response.code}."
                    )
                }

                val json = JSONObject(responseText)
                val data = json.optJSONArray("data")
                if (data == null || data.length() == 0) {
                    return browserFailure("Pencarian web tidak menemukan hasil yang dapat dibaca.")
                }

                val context = StringBuilder()
                val sources = linkedSetOf<String>()
                context.appendLine("WEB_BROWSER_CONTEXT:")
                context.appendLine("Status: berhasil")
                context.appendLine("Query: $query")
                context.appendLine("Sumber: Browserless/Firecrawl melalui backend aplikasi")
                context.appendLine()

                for (index in 0 until minOf(5, data.length())) {
                    val item = data.optJSONObject(index) ?: continue
                    val title = item.optString("title", "Tanpa judul")
                    val description = item.optString("description", "").take(MAX_SEARCH_TEXT)
                    val url = item.optString("url", "")

                    context.appendLine("Hasil ${index + 1}: $title")
                    if (url.isNotBlank()) {
                        context.appendLine("URL: $url")
                        sources += url
                    }
                    if (description.isNotBlank()) {
                        context.appendLine("Isi: $description")
                    }
                    context.appendLine()
                }

                context.appendLine("Instruksi wajib:")
                context.appendLine("- Jawab pertanyaan pengguna berdasarkan konteks browser di atas.")
                context.appendLine("- Jangan mengatakan bahwa kamu tidak bisa membuka, mengakses, atau mengecek website.")
                context.appendLine("- Jika data tidak cukup, jelaskan bagian yang belum dapat dipastikan tanpa mengarang.")
                context.appendLine("- Jangan menyebut proses internal, nama fungsi, atau endpoint aplikasi.")

                BrowserContextResult(
                    promptContext = context.toString(),
                    sourceLinks = formatSources(sources)
                )
            }
        } catch (error: Exception) {
            browserFailure("Pencarian web gagal: ${error.message ?: "kesalahan tidak diketahui"}.")
        }
    }

    fun readUrl(url: String): BrowserContextResult {
        return try {
            val requestJson = JSONObject().put("url", url).toString()
            val requestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/read-url")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string()
                if (!response.isSuccessful || responseText.isNullOrBlank()) {
                    return browserFailure(
                        "Browser gagal membaca $url dengan HTTP ${response.code}."
                    )
                }

                val markdown = try {
                    val json = JSONObject(responseText)
                    json.optJSONObject("data")?.optString("markdown")
                        ?.takeIf { it.isNotBlank() }
                        ?: json.optString("markdown", "")
                } catch (_: Exception) {
                    responseText
                }

                if (markdown.isBlank()) {
                    return browserFailure("Browser berhasil membuka $url, tetapi halaman tidak menghasilkan teks yang dapat dibaca.")
                }

                val safeContent = markdown.take(MAX_PAGE_TEXT)
                BrowserContextResult(
                    promptContext = """
                        WEB_BROWSER_CONTEXT:
                        Status: berhasil
                        URL: $url
                        Sumber: Browserless/Firecrawl melalui backend aplikasi

                        Isi halaman:
                        $safeContent

                        Instruksi wajib:
                        - Jawab pertanyaan pengguna berdasarkan isi halaman ini.
                        - Jangan mengatakan bahwa kamu tidak bisa membuka, mengakses, atau mengecek website.
                        - Jika pengguna hanya mengirim URL, jelaskan isi dan fungsi utama halaman.
                        - Jika data tidak cukup, jelaskan bagian yang belum dapat dipastikan tanpa mengarang.
                        - Jangan menyebut proses internal, nama fungsi, atau endpoint aplikasi.
                    """.trimIndent(),
                    sourceLinks = formatSources(linkedSetOf(url))
                )
            }
        } catch (error: Exception) {
            browserFailure("Browser gagal membaca $url: ${error.message ?: "kesalahan tidak diketahui"}.")
        }
    }

    private fun browserFailure(reason: String): BrowserContextResult {
        return BrowserContextResult(
            promptContext = """
                WEB_BROWSER_CONTEXT:
                Status: gagal
                Detail: $reason

                Instruksi wajib:
                - Jangan mengatakan bahwa kamu tidak memiliki kemampuan membuka atau mengakses website.
                - Katakan dengan jujur bahwa browser aplikasi gagal mengambil data saat ini.
                - Jangan menebak informasi realtime atau isi website yang tidak berhasil dibaca.
            """.trimIndent()
        )
    }

    private fun formatSources(sources: Set<String>): String {
        if (sources.isEmpty()) return ""
        return sources.take(5).joinToString(
            separator = "\n- ",
            prefix = "\n\nSumber web:\n- "
        )
    }

    private companion object {
        const val BASE_URL = "https://chat-ai-lutfula.vercel.app"
        const val MAX_SEARCH_TEXT = 1800
        const val MAX_PAGE_TEXT = 10000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
