package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppGuide
import com.example.data.ChatRepository
import com.example.data.ChatSessionEntity
import com.example.data.MessageEntity
import com.example.data.SettingsRepository
import com.example.network.ChatRequest
import com.example.network.ChatResponse
import com.example.network.ReasoningConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException


enum class ChatMode { NORMAL, THINK, THINK_DEEPLY }


data class UiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val imageUri: String? = null,
    val isError: Boolean = false
)


data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val currentSessionId: Long? = null,
    val currentModel: String = "",
    val savedModelsList: List<com.example.network.AiModelConfig> = emptyList(),
    val isLoading: Boolean = false,
    val loadingText: String? = null,
    val error: String? = null,
    val mode: ChatMode = ChatMode.NORMAL
)


class ChatViewModel(
    private val applicationContext: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: com.example.data.MemoryRepository,
    private val localStorage: com.example.data.LocalStorage,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(mode = try { ChatMode.valueOf(localStorage.getChatMode()) } catch (e: Exception) { ChatMode.NORMAL })
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var messageJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            chatRepository.allSessions.collect { sessions -> _uiState.update { it.copy(sessions = sessions) } }
        }
        viewModelScope.launch {
            settingsRepository.model.collect { model -> _uiState.update { it.copy(currentModel = model) } }
        }
        viewModelScope.launch {
            settingsRepository.savedModelsList.collect { models ->
                _uiState.update { it.copy(savedModelsList = models) }
                val current = _uiState.value.currentModel
                if (current.isNotBlank() && models.isNotEmpty() && !models.any { it.modelName == current }) {
                    updateSelectedModel(models.first().modelName)
                } else if (current.isNotBlank() && models.isEmpty()) {
                    updateSelectedModel("")
                }
            }
        }
    }

    fun updateSelectedModel(modelName: String) {
        viewModelScope.launch { settingsRepository.updateModel(modelName) }
    }

    fun selectSession(sessionId: Long) {
        _uiState.update { it.copy(currentSessionId = sessionId, messages = emptyList()) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            chatRepository.getMessagesForSession(sessionId).collect { messages ->
                _uiState.update { state ->
                    if (state.currentSessionId == sessionId) {
                        state.copy(messages = messages.map { UiMessage(it.id.toString(), it.role, it.content, it.imageUri) })
                    } else state
                }
            }
        }
    }

    fun createNewSession() {
        _uiState.update { it.copy(currentSessionId = null, messages = emptyList()) }
        messageJob?.cancel()
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) createNewSession()
        }
    }

    fun setMode(mode: ChatMode) {
        _uiState.update { it.copy(mode = mode) }
        localStorage.saveChatMode(mode.name)
    }

    private fun realtimeQuery(messageText: String): String? {
        val text = messageText.trim()
        if (text.isEmpty()) return null
        val parts = text.split(Regex("\\s+"), limit = 2)
        val first = parts.firstOrNull()?.lowercase() ?: return null
        return if (first == "#berita" || first == "#browser" || first == "#cari") {
            parts.getOrNull(1)?.trim() ?: ""
        } else null
    }

    private fun formatRealtimeResponse(raw: String, query: String): String {
        val json = org.json.JSONObject(raw)
        val arr = json.optJSONArray("data") ?: json.optJSONArray("results")
            ?: return "Search realtime berjalan, tetapi hasil dari backend belum bisa dibaca."
        if (arr.length() == 0) return "Belum ada hasil realtime yang relevan untuk: $query"

        val list = mutableListOf<String>()
        for (i in 0 until minOf(5, arr.length())) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title", "Tanpa judul").ifBlank { "Tanpa judul" }
            val desc = item.optString("description", "").ifBlank { item.optString("markdown", "").take(300) }
            val url = item.optString("url", "")
            list.add("${i + 1}. $title\n   $desc" + if (url.isNotBlank()) "\n   Sumber: $url" else "")
        }
        return "Hasil pencarian realtime untuk: $query\n\n" + list.joinToString("\n\n")
    }

    private fun callVercelSearch(query: String): Result<String> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/search?q=$encoded")
                .get()
                .build()
            val res = okHttpClient.newCall(req).execute()
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                Result.failure(Exception("Backend realtime HTTP ${res.code}: ${body.take(250)}"))
            } else if (body.isBlank()) {
                Result.failure(Exception("Backend realtime mengembalikan response kosong."))
            } else {
                Result.success(formatRealtimeResponse(body, query))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Backend realtime gagal: ${e.message}"))
        }
    }

    private suspend fun handleLocalCommand(text: String, sessionId: Long): Boolean {
        val lower = text.trim().lowercase()
        if (lower == "memory off") {
            settingsRepository.saveMemoryEnabled(false)
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is now disabled."))
            return true
        }
        if (lower == "memory on") {
            settingsRepository.saveMemoryEnabled(true)
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is now enabled."))
            return true
        }
        if (lower == "hapus memory") {
            memoryRepository.deleteAllMemories()
            localStorage.prefs.edit().remove("custom_instruction").commit()
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory deleted."))
            return true
        }
        return false
    }

    fun sendMessage(userText: String, imageUri: String? = null) {
        val text = userText.trim()
        if (text.isEmpty() && imageUri == null) return
        val oldMessages = _uiState.value.messages.toList()
        _uiState.update { it.copy(isLoading = true, loadingText = null, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var sessionId = _uiState.value.currentSessionId
                if (sessionId == null) {
                    val title = if (text.length > 20) text.substring(0, 20) + "..." else text.ifBlank { "New Chat" }
                    sessionId = chatRepository.createNewSession(title)
                    _uiState.update { it.copy(currentSessionId = sessionId) }
                    selectSession(sessionId)
                }

                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "user", content = text, imageUri = imageUri))

                if (handleLocalCommand(text, sessionId)) {
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    return@launch
                }

                val query = realtimeQuery(text)
                if (query != null) {
                    if (query.isBlank()) {
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Masukkan kata kunci setelah #berita, #browser, atau #cari."))
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                        return@launch
                    }
                    _uiState.update { it.copy(loadingText = "Mencari data realtime...") }
                    val result = callVercelSearch(query)
                    val answer = result.getOrElse { err -> "Search realtime gagal dari backend Vercel.\n\n${err.message}" }
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = answer))
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    return@launch
                }

                val credential = settingsRepository.apiKey.first()
                val baseUrl = settingsRepository.baseUrl.first()
                val path = settingsRepository.textPath.first()
                val modelName = settingsRepository.model.first()
                val models = settingsRepository.savedModelsList.first()
                val selected = models.find { it.modelName == modelName }
                val supportsVision = selected?.supportsVision ?: false
                val supportsReasoning = selected?.supportsReasoning ?: false
                val langPref = settingsRepository.assistantLanguagePreference.first()

                if (credential.isBlank() || baseUrl.isBlank() || modelName.isBlank()) {
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = "Konfigurasi provider chat belum lengkap.") }
                    return@launch
                }

                var systemPrompt = when (_uiState.value.mode) {
                    ChatMode.NORMAL -> "You are a helpful AI assistant. Provide fast, simple, and direct answers."
                    ChatMode.THINK -> "You are a helpful AI assistant. Reason carefully but keep the final answer clear."
                    ChatMode.THINK_DEEPLY -> "You are a helpful AI assistant. Provide deeper analysis for complex tasks."
                }
                if (langPref == "id") systemPrompt += "\n\nAlways respond in Bahasa Indonesia."
                systemPrompt += "\n\n" + AppGuide.TEXT
                systemPrompt += "\n\n" + getCurrentTimeContext()

                val chatMessages = mutableListOf<com.example.network.ChatRequestMessage>()
                chatMessages.add(com.example.network.ChatRequestMessage(role = "system", content = listOf(com.example.network.VisionContent(type = "text", text = systemPrompt))))

                oldMessages.filter { !it.content.startsWith("⚠️") }.forEach {
                    chatMessages.add(com.example.network.ChatRequestMessage(role = it.role, content = listOf(com.example.network.VisionContent(type = "text", text = it.content))))
                }

                if (imageUri != null && !supportsVision) {
                    val msg = "⚠️ Model ini tidak mendukung membaca gambar. Pilih model vision."
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = msg))
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = msg) }
                    return@launch
                }

                val userParts = mutableListOf<com.example.network.VisionContent>()
                userParts.add(com.example.network.VisionContent(type = "text", text = text))
                if (imageUri != null) {
                    val dataUrl = uriToBase64(imageUri)
                    if (dataUrl != null) userParts.add(com.example.network.VisionContent(type = "image_url", imageUrl = com.example.network.VisionImageUrl(url = dataUrl)))
                }
                chatMessages.add(com.example.network.ChatRequestMessage(role = "user", content = userParts))

                val reasoning = if (supportsReasoning) {
                    when (_uiState.value.mode) {
                        ChatMode.THINK -> ReasoningConfig("medium")
                        ChatMode.THINK_DEEPLY -> ReasoningConfig("high")
                        ChatMode.NORMAL -> null
                    }
                } else null

                val endpoint = baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
                val requestBody = ChatRequest(model = modelName, messages = chatMessages, reasoning = reasoning)
                val json = moshi.adapter(ChatRequest::class.java).toJson(requestBody)
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authori" + "zation", "Bearer " + credential)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    val chatResponse = moshi.adapter(ChatResponse::class.java).fromJson(responseBody)
                    val reply = chatResponse?.choices?.firstOrNull()?.message?.content ?: chatResponse?.error?.message ?: "No content received."
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = reply))
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                } else {
                    val errorMsg = if (response.code == 503) {
                        "HTTP 503: Provider/model sedang unavailable. Coba mode Normal atau model lain."
                    } else {
                        "HTTP ${response.code}: $responseBody"
                    }
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = errorMsg) }
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, loadingText = null, error = "Network Error or Timeout: ${e.message}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, loadingText = null, error = "An unexpected error occurred: ${e.message}") }
            }
        }
    }

    private fun uriToBase64(uriStr: String?): String? {
        return try {
            val uri = android.net.Uri.parse(uriStr)
            val resolver = applicationContext.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP)
                val mimeType = resolver.getType(uri) ?: "image/jpeg"
                "data:$mimeType;base64,$base64"
            } else null
        } catch (e: Exception) {
            null
        }
    }

    class Factory(
        private val applicationContext: android.content.Context,
        private val settingsRepository: SettingsRepository,
        private val chatRepository: ChatRepository,
        private val memoryRepository: com.example.data.MemoryRepository,
        private val localStorage: com.example.data.LocalStorage,
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(applicationContext, settingsRepository, chatRepository, memoryRepository, localStorage, okHttpClient, moshi) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}


fun getCurrentTimeContext(): String {
    val zoneId = try { java.time.ZoneId.systemDefault() } catch (e: Exception) { java.time.ZoneId.of("Asia/Jakarta") }
    val now = java.time.ZonedDateTime.now(zoneId)
    val dateStr = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", java.util.Locale.forLanguageTag("id-ID")))
    val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    return """
        CURRENT_REAL_TIME_CONTEXT:
        Tanggal sekarang: $dateStr
        Jam sekarang: $timeStr
        Timezone: ${zoneId.id}
        Country code: ID
    """.trimIndent()
}
