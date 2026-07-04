package com.example.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class SemanticBrowserDecision(
    val useBrowser: Boolean,
    val mode: String,
    val query: String,
    val decidedByModel: Boolean
)

internal object SemanticBrowserRouter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun decide(
        question: String,
        hasDedicatedRealtimeContext: Boolean
    ): SemanticBrowserDecision? {
        val config = TextApiRuntimeConfig.snapshot()
        if (!config.isComplete || question.isBlank()) return null

        return try {
            val systemPrompt = """
                Kamu adalah router alat browser untuk aplikasi AI.
                Tugasmu hanya menentukan apakah pertanyaan pengguna membutuhkan internet/browser untuk dijawab dengan akurat.

                Gunakan browser jika:
                - informasi dapat berubah setelah pengetahuan model, termasuk berita, kondisi hari ini, status layanan, pejabat/CEO saat ini, harga/paket/fitur terbaru, jadwal, skor, cuaca, versi, produk atau model yang sedang tersedia;
                - pengguna meminta membuka, mencari, mengecek, atau membandingkan website/internet;
                - jawaban bergantung pada isi situs, platform, halaman, tautan, atau katalog online;
                - pengguna menanyakan perkembangan, alasan, sentimen, atau kejadian terbaru.

                Jangan gunakan browser jika:
                - tugas kreatif, terjemahan, parafrase, perhitungan sederhana, percakapan biasa, atau pengetahuan umum yang stabil;
                - data khusus aplikasi sudah cukup menjawab pertanyaan langsung.

                DEDICATED_REALTIME_CONTEXT_AVAILABLE=$hasDedicatedRealtimeContext
                Jika nilai tersebut true dan data khusus sudah cukup untuk pertanyaan langsung, pilih use_browser=false.
                Namun tetap pilih use_browser=true bila pengguna meminta berita, alasan, sentimen, perkembangan, atau konteks online tambahan.

                Kembalikan JSON valid saja tanpa markdown:
                {"use_browser":true|false,"mode":"search"|"news","query":"kueri pencarian ringkas"}

                Aturan mode:
                - news untuk berita, perkembangan terbaru, sentimen, kejadian hari ini, atau alasan pergerakan terbaru.
                - search untuk website, daftar produk/model/fitur, status, pejabat, harga/paket, dokumentasi, dan informasi online lainnya.
                Jika use_browser=false, query boleh kosong.
            """.trimIndent()

            val requestJson = JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", systemPrompt)
                                    )
                                )
                        )
                        .put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", question.take(3000))
                                    )
                                )
                        )
                )

            val request = Request.Builder()
                .url(config.endpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(
                    requestJson.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) return null

                val root = JSONObject(body)
                val message = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?: return null

                val content = extractContent(message.opt("content"))
                val decisionJson = extractJsonObject(content) ?: return null
                val useBrowser = decisionJson.optBoolean("use_browser", false)
                val rawMode = decisionJson.optString("mode", "search")
                    .lowercase(Locale.ROOT)
                val mode = if (rawMode == "news") "berita" else "cari"
                val query = decisionJson.optString("query", "")
                    .trim()
                    .ifBlank { question }
                    .take(500)

                SemanticBrowserDecision(
                    useBrowser = useBrowser,
                    mode = mode,
                    query = query,
                    decidedByModel = true
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractContent(value: Any?): String {
        return when (value) {
            is String -> value
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    val item = value.opt(index)
                    when (item) {
                        is JSONObject -> {
                            val text = item.optString("text", "")
                            if (text.isNotBlank()) append(text)
                        }
                        is String -> append(item)
                    }
                }
            }
            else -> value?.toString().orEmpty()
        }
    }

    private fun extractJsonObject(text: String): JSONObject? {
        val trimmed = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            try {
                JSONObject(trimmed.substring(start, end + 1))
            } catch (_: Exception) {
                null
            }
        }
    }
}
