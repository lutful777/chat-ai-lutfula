package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import com.example.network.AiModelConfig
import com.example.network.ChatRequest
import com.example.network.ChatRequestMessage
import com.example.network.VisionContent
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

data class SettingsUiState(
    val textProvider: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val textPath: String = "/chat/completions",
    val modelName: String = "",
    val supportsVision: Boolean = false,
    val savedModelsList: List<AiModelConfig> = emptyList(),
    val isSaved: Boolean = false,
    val isTextTesting: Boolean = false,
    val textTestResult: String? = null,
    val textTestError: String? = null,
    val requireValidation: Boolean = false,
    val validationError: String? = null,

    val createPhotoProvider: String = "",
    val createPhotoApiKey: String = "",
    val createPhotoBaseUrl: String = "",
    val createPhotoEndpoint: String = "",
    val createPhotoModel: String = "",
    val createPhotoFormat: String = "JSON",

    val editPhotoProvider: String = "",
    val editPhotoApiKey: String = "",
    val editPhotoBaseUrl: String = "",
    val editPhotoEndpoint: String = "",
    val editPhotoModel: String = "",
    val editPhotoFormat: String = "JSON",
    val editPhotoImageFormat: String = "base64",

    val photoVideoProvider: String = "",
    val photoVideoApiKey: String = "",
    val photoVideoBaseUrl: String = "",
    val photoVideoCreateEndpoint: String = "",
    val photoVideoStatusEndpoint: String = "",
    val photoVideoResultEndpoint: String = "",
    val photoVideoModel: String = "",
    val photoVideoFormat: String = "JSON",
    val photoVideoImageFormat: String = "base64",
    val photoVideoDuration: String = "5",

    val isCreatePhotoSaved: Boolean = false,
    val isEditPhotoSaved: Boolean = false,
    val isPhotoVideoSaved: Boolean = false,

    val isCreatePhotoTesting: Boolean = false,
    val createPhotoTestResult: String? = null,
    val createPhotoTestError: String? = null,

    val isEditPhotoTesting: Boolean = false,
    val editPhotoTestResult: String? = null,
    val editPhotoTestError: String? = null,

    val isPhotoVideoTesting: Boolean = false,
    val photoVideoTestResult: String? = null,
    val photoVideoTestError: String? = null,

    val economyMode: Boolean = true
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val localStorage: com.example.data.LocalStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        viewModelScope.launch {
            settingsRepository.savedModelsList.collect { models ->
                val currentModel = _uiState.value.modelName
                val match = models.find { it.modelName == currentModel }
                _uiState.update {
                    it.copy(
                        savedModelsList = models,
                        supportsVision = match?.supportsVision ?: it.supportsVision
                    )
                }
            }
        }
    }

    fun removeSavedModel(model: String) {
        viewModelScope.launch { settingsRepository.removeSavedModel(model) }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val rawProvider = settingsRepository.textProvider.first()
            val provider = when (rawProvider) {
                "OpenAI", "BlueSminds" -> "bluesminds"
                "OpenRouter" -> "openrouter"
                "xAI" -> "xai"
                "Custom" -> "custom"
                else -> rawProvider
            }
            val textApiKey = settingsRepository.apiKey.value

            _uiState.update {
                it.copy(
                    textProvider = provider.ifBlank { "bluesminds" },
                    baseUrl = settingsRepository.baseUrl.first(),
                    apiKey = if (textApiKey.isNotBlank()) MASKED_KEY_PLACEHOLDER else "",
                    textPath = settingsRepository.textPath.first().ifBlank { "/chat/completions" },
                    modelName = settingsRepository.model.first(),

                    createPhotoProvider = settingsRepository.createPhotoProvider.first(),
                    createPhotoApiKey = settingsRepository.createPhotoApiKey.first(),
                    createPhotoBaseUrl = settingsRepository.createPhotoBaseUrl.first(),
                    createPhotoEndpoint = settingsRepository.createPhotoEndpoint.first(),
                    createPhotoModel = settingsRepository.createPhotoModel.first(),
                    createPhotoFormat = settingsRepository.createPhotoFormat.first(),

                    editPhotoProvider = settingsRepository.editPhotoProvider.first(),
                    editPhotoApiKey = settingsRepository.editPhotoApiKey.first(),
                    editPhotoBaseUrl = settingsRepository.editPhotoBaseUrl.first(),
                    editPhotoEndpoint = settingsRepository.editPhotoEndpoint.first(),
                    editPhotoModel = settingsRepository.editPhotoModel.first(),
                    editPhotoFormat = settingsRepository.editPhotoFormat.first(),
                    editPhotoImageFormat = settingsRepository.editPhotoImageFormat.first()
                        .let { if (it == "url") "multipart" else it },

                    photoVideoProvider = settingsRepository.photoVideoProvider.first(),
                    photoVideoApiKey = settingsRepository.photoVideoApiKey.first(),
                    photoVideoBaseUrl = settingsRepository.photoVideoBaseUrl.first(),
                    photoVideoCreateEndpoint = settingsRepository.photoVideoCreateEndpoint.first(),
                    photoVideoStatusEndpoint = settingsRepository.photoVideoStatusEndpoint.first(),
                    photoVideoResultEndpoint = settingsRepository.photoVideoResultEndpoint.first(),
                    photoVideoModel = settingsRepository.photoVideoModel.first(),
                    photoVideoFormat = settingsRepository.photoVideoFormat.first(),
                    photoVideoImageFormat = settingsRepository.photoVideoImageFormat.first()
                        .let { if (it == "url") "multipart" else it },
                    photoVideoDuration = settingsRepository.photoVideoDuration.first(),
                    economyMode = settingsRepository.economyMode.first()
                )
            }
        }
    }

    fun updateTextProvider(provider: String) {
        _uiState.update { it.copy(textProvider = provider, isSaved = false) }
    }

    fun updateBaseUrl(url: String) {
        _uiState.update {
            it.copy(baseUrl = url, isSaved = false, validationError = null)
        }
    }

    fun updateApiKey(key: String) {
        _uiState.update {
            it.copy(apiKey = key, isSaved = false, validationError = null)
        }
    }

    fun updateTextPath(path: String) {
        _uiState.update {
            it.copy(textPath = path, isSaved = false, validationError = null)
        }
    }

    fun updateModelName(model: String) {
        val saved = _uiState.value.savedModelsList.find { it.modelName == model }
        _uiState.update {
            it.copy(
                modelName = model,
                supportsVision = saved?.supportsVision ?: false,
                isSaved = false,
                validationError = null
            )
        }
    }

    fun updateSupportsVision(supports: Boolean) {
        _uiState.update { it.copy(supportsVision = supports, isSaved = false) }
    }

    fun clearTestResult() {
        _uiState.update {
            it.copy(textTestResult = null, textTestError = null, validationError = null)
        }
    }

    fun applyPreset(presetName: String) {
        val (url, model, path) = when (presetName) {
            "bluesminds" -> Triple("https://api.bluesminds.com/v1", "", "/chat/completions")
            "openrouter" -> Triple(
                "https://openrouter.ai/api/v1",
                "openai/o1-mini",
                "/chat/completions"
            )
            "xai" -> Triple("https://api.x.ai/v1", "grok-2-latest", "/chat/completions")
            else -> Triple("", "", "")
        }
        _uiState.update {
            it.copy(
                baseUrl = url.ifBlank { it.baseUrl },
                modelName = model.ifBlank { it.modelName },
                textPath = path.ifBlank { it.textPath },
                isSaved = false,
                validationError = null
            )
        }
    }

    private fun validateTextSettings(): Boolean {
        val state = _uiState.value
        val actualKey = actualTextApiKey(state.apiKey)
        val error = when {
            state.baseUrl.isBlank() -> "Base URL is required"
            actualKey.isBlank() -> "API key is required"
            state.textPath.isBlank() -> "API path is required"
            state.modelName.isBlank() -> "Model is required"
            else -> null
        }
        _uiState.update { it.copy(validationError = error) }
        return error == null
    }

    fun save() {
        if (!validateTextSettings()) return
        viewModelScope.launch {
            val state = _uiState.value
            val key = actualTextApiKey(state.apiKey)
            settingsRepository.saveSettings(
                state.textProvider,
                key,
                state.baseUrl,
                state.textPath,
                state.modelName
            )
            settingsRepository.addSavedModel(
                AiModelConfig(
                    modelName = state.modelName,
                    providerName = state.textProvider,
                    supportsVision = state.supportsVision
                )
            )
            _uiState.update {
                it.copy(
                    isSaved = true,
                    validationError = null,
                    textTestResult = null,
                    textTestError = null,
                    apiKey = if (key.isNotBlank()) MASKED_KEY_PLACEHOLDER else ""
                )
            }
        }
    }

    fun testConnection() {
        if (!validateTextSettings()) return
        _uiState.update {
            it.copy(
                isTextTesting = true,
                textTestResult = null,
                textTestError = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val endpoint = buildUrl(state.baseUrl, state.textPath)
                val requestBody = ChatRequest(
                    model = state.modelName,
                    messages = listOf(
                        ChatRequestMessage(
                            role = "user",
                            content = listOf(VisionContent(type = "text", text = "Hello"))
                        )
                    )
                )
                val json = moshi.adapter(ChatRequest::class.java).toJson(requestBody)
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer ${actualTextApiKey(state.apiKey)}")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            _uiState.update {
                                it.copy(
                                    isTextTesting = false,
                                    textTestResult = "Connection successful"
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isTextTesting = false,
                                    textTestError = connectionError(response.code, body)
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isTextTesting = false,
                            textTestError = "Network error: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun updateCreatePhotoProvider(value: String) {
        _uiState.update {
            it.copy(createPhotoProvider = value, isCreatePhotoSaved = false)
        }
    }

    fun updateCreatePhotoApiKey(value: String) {
        _uiState.update {
            it.copy(createPhotoApiKey = value, isCreatePhotoSaved = false)
        }
    }

    fun updateCreatePhotoBaseUrl(value: String) {
        _uiState.update {
            it.copy(createPhotoBaseUrl = value, isCreatePhotoSaved = false)
        }
    }

    fun updateCreatePhotoEndpoint(value: String) {
        _uiState.update {
            it.copy(createPhotoEndpoint = value, isCreatePhotoSaved = false)
        }
    }

    fun updateCreatePhotoModel(value: String) {
        _uiState.update {
            it.copy(createPhotoModel = value, isCreatePhotoSaved = false)
        }
    }

    fun updateCreatePhotoFormat(value: String) {
        _uiState.update {
            it.copy(createPhotoFormat = value, isCreatePhotoSaved = false)
        }
    }

    fun saveCreatePhotoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveCreatePhotoSettings(
                state.createPhotoProvider,
                state.createPhotoApiKey,
                state.createPhotoBaseUrl,
                state.createPhotoEndpoint,
                state.createPhotoModel,
                state.createPhotoFormat
            )
            _uiState.update { it.copy(isCreatePhotoSaved = true) }
        }
    }

    fun testCreatePhotoConnection() {
        val state = _uiState.value
        if (!validateMediaSettings(
                state.createPhotoBaseUrl,
                state.createPhotoApiKey,
                state.createPhotoEndpoint,
                state.createPhotoModel
            )
        ) {
            _uiState.update {
                it.copy(
                    createPhotoTestError =
                        "Base URL, API Key, Endpoint, and Model are required"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isCreatePhotoTesting = true,
                createPhotoTestResult = null,
                createPhotoTestError = null
            )
        }
        runMediaTest(
            kind = MediaTestKind.CREATE_IMAGE,
            baseUrl = state.createPhotoBaseUrl,
            endpoint = state.createPhotoEndpoint,
            apiKey = state.createPhotoApiKey,
            model = state.createPhotoModel,
            format = state.createPhotoFormat,
            onResult = { result ->
                _uiState.update {
                    if (result.isSuccess) {
                        it.copy(
                            isCreatePhotoTesting = false,
                            createPhotoTestResult = result.getOrNull()
                        )
                    } else {
                        it.copy(
                            isCreatePhotoTesting = false,
                            createPhotoTestError =
                                result.exceptionOrNull()?.message ?: "Unknown error"
                        )
                    }
                }
            }
        )
    }

    fun updateEditPhotoProvider(value: String) {
        _uiState.update { it.copy(editPhotoProvider = value, isEditPhotoSaved = false) }
    }

    fun updateEditPhotoApiKey(value: String) {
        _uiState.update { it.copy(editPhotoApiKey = value, isEditPhotoSaved = false) }
    }

    fun updateEditPhotoBaseUrl(value: String) {
        _uiState.update { it.copy(editPhotoBaseUrl = value, isEditPhotoSaved = false) }
    }

    fun updateEditPhotoEndpoint(value: String) {
        _uiState.update { it.copy(editPhotoEndpoint = value, isEditPhotoSaved = false) }
    }

    fun updateEditPhotoModel(value: String) {
        _uiState.update { it.copy(editPhotoModel = value, isEditPhotoSaved = false) }
    }

    fun updateEditPhotoFormat(value: String) {
        _uiState.update { it.copy(editPhotoFormat = value, isEditPhotoSaved = false) }
    }

    fun updateEditPhotoImageFormat(value: String) {
        _uiState.update {
            it.copy(editPhotoImageFormat = value, isEditPhotoSaved = false)
        }
    }

    fun saveEditPhotoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveEditPhotoSettings(
                state.editPhotoProvider,
                state.editPhotoApiKey,
                state.editPhotoBaseUrl,
                state.editPhotoEndpoint,
                state.editPhotoModel,
                state.editPhotoFormat,
                state.editPhotoImageFormat
            )
            _uiState.update { it.copy(isEditPhotoSaved = true) }
        }
    }

    fun testEditPhotoConnection() {
        val state = _uiState.value
        if (!validateMediaSettings(
                state.editPhotoBaseUrl,
                state.editPhotoApiKey,
                state.editPhotoEndpoint,
                state.editPhotoModel
            )
        ) {
            _uiState.update {
                it.copy(
                    editPhotoTestError =
                        "Base URL, API Key, Endpoint, and Model are required"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isEditPhotoTesting = true,
                editPhotoTestResult = null,
                editPhotoTestError = null
            )
        }
        runMediaTest(
            kind = MediaTestKind.EDIT_IMAGE,
            baseUrl = state.editPhotoBaseUrl,
            endpoint = state.editPhotoEndpoint,
            apiKey = state.editPhotoApiKey,
            model = state.editPhotoModel,
            format = state.editPhotoFormat,
            onResult = { result ->
                _uiState.update {
                    if (result.isSuccess) {
                        it.copy(
                            isEditPhotoTesting = false,
                            editPhotoTestResult = result.getOrNull()
                        )
                    } else {
                        it.copy(
                            isEditPhotoTesting = false,
                            editPhotoTestError =
                                result.exceptionOrNull()?.message ?: "Unknown error"
                        )
                    }
                }
            }
        )
    }

    fun updatePhotoVideoProvider(value: String) {
        _uiState.update {
            it.copy(photoVideoProvider = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoApiKey(value: String) {
        _uiState.update {
            it.copy(photoVideoApiKey = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoBaseUrl(value: String) {
        _uiState.update {
            it.copy(photoVideoBaseUrl = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoCreateEndpoint(value: String) {
        _uiState.update {
            it.copy(photoVideoCreateEndpoint = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoStatusEndpoint(value: String) {
        _uiState.update {
            it.copy(photoVideoStatusEndpoint = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoResultEndpoint(value: String) {
        _uiState.update {
            it.copy(photoVideoResultEndpoint = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoModel(value: String) {
        _uiState.update {
            it.copy(photoVideoModel = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoFormat(value: String) {
        _uiState.update {
            it.copy(photoVideoFormat = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoImageFormat(value: String) {
        _uiState.update {
            it.copy(photoVideoImageFormat = value, isPhotoVideoSaved = false)
        }
    }

    fun updatePhotoVideoDuration(value: String) {
        _uiState.update {
            it.copy(photoVideoDuration = value, isPhotoVideoSaved = false)
        }
    }

    fun updateEconomyMode(value: Boolean) {
        _uiState.update { it.copy(economyMode = value) }
        viewModelScope.launch { settingsRepository.saveEconomyMode(value) }
    }

    fun savePhotoVideoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.savePhotoVideoSettings(
                state.photoVideoProvider,
                state.photoVideoApiKey,
                state.photoVideoBaseUrl,
                state.photoVideoCreateEndpoint,
                state.photoVideoStatusEndpoint,
                state.photoVideoResultEndpoint,
                state.photoVideoModel,
                state.photoVideoFormat,
                state.photoVideoImageFormat,
                state.photoVideoDuration
            )
            _uiState.update { it.copy(isPhotoVideoSaved = true) }
        }
    }

    fun testPhotoToVideoConnection() {
        val state = _uiState.value
        if (!validateMediaSettings(
                state.photoVideoBaseUrl,
                state.photoVideoApiKey,
                state.photoVideoCreateEndpoint,
                state.photoVideoModel
            )
        ) {
            _uiState.update {
                it.copy(
                    photoVideoTestError =
                        "Base URL, API Key, create Endpoint, and Model are required"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isPhotoVideoTesting = true,
                photoVideoTestResult = null,
                photoVideoTestError = null
            )
        }
        runMediaTest(
            kind = MediaTestKind.IMAGE_TO_VIDEO,
            baseUrl = state.photoVideoBaseUrl,
            endpoint = state.photoVideoCreateEndpoint,
            apiKey = state.photoVideoApiKey,
            model = state.photoVideoModel,
            format = state.photoVideoFormat,
            onResult = { result ->
                _uiState.update {
                    if (result.isSuccess) {
                        it.copy(
                            isPhotoVideoTesting = false,
                            photoVideoTestResult = result.getOrNull()
                        )
                    } else {
                        it.copy(
                            isPhotoVideoTesting = false,
                            photoVideoTestError =
                                result.exceptionOrNull()?.message ?: "Unknown error"
                        )
                    }
                }
            }
        )
    }

    private fun runMediaTest(
        kind: MediaTestKind,
        baseUrl: String,
        endpoint: String,
        apiKey: String,
        model: String,
        format: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = executeMediaTest(kind, baseUrl, endpoint, apiKey, model, format)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private suspend fun executeMediaTest(
        kind: MediaTestKind,
        baseUrl: String,
        endpoint: String,
        apiKey: String,
        model: String,
        format: String
    ): Result<String> {
        return try {
            val requestBody = if (format.equals("multipart", ignoreCase = true)) {
                buildMultipartMediaTest(kind, model)
            } else {
                buildJsonMediaTest(kind, baseUrl, model)
            }

            val request = Request.Builder()
                .url(buildUrl(baseUrl, endpoint))
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val message = when (kind) {
                        MediaTestKind.CREATE_IMAGE ->
                            "Connection successful. Image request accepted."
                        MediaTestKind.EDIT_IMAGE ->
                            "Connection successful. Edit request accepted."
                        MediaTestKind.IMAGE_TO_VIDEO ->
                            "Connection successful. Video request accepted."
                    }
                    Result.success(message)
                } else {
                    Result.failure(Exception(connectionError(response.code, body)))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    private fun buildJsonMediaTest(
        kind: MediaTestKind,
        baseUrl: String,
        model: String
    ): RequestBody {
        val json = JSONObject()
            .put("model", model)
            .put("prompt", when (kind) {
                MediaTestKind.CREATE_IMAGE -> "A simple gray square on a white background"
                MediaTestKind.EDIT_IMAGE -> "Make the image slightly brighter"
                MediaTestKind.IMAGE_TO_VIDEO -> "Add a very subtle slow zoom"
            })

        when (kind) {
            MediaTestKind.CREATE_IMAGE -> Unit
            MediaTestKind.EDIT_IMAGE -> {
                if (isXai(baseUrl)) {
                    json.put(
                        "image",
                        JSONObject()
                            .put("url", TEST_IMAGE_DATA_URI)
                            .put("type", "image_url")
                    )
                } else {
                    json.put("image", TEST_IMAGE_DATA_URI)
                }
            }
            MediaTestKind.IMAGE_TO_VIDEO -> {
                json.put("duration", 5)
                if (isXai(baseUrl)) {
                    json.put("image", JSONObject().put("url", TEST_IMAGE_DATA_URI))
                } else {
                    json.put("image", TEST_IMAGE_DATA_URI)
                }
            }
        }

        return json.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun buildMultipartMediaTest(
        kind: MediaTestKind,
        model: String
    ): RequestBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "prompt",
                when (kind) {
                    MediaTestKind.CREATE_IMAGE ->
                        "A simple gray square on a white background"
                    MediaTestKind.EDIT_IMAGE ->
                        "Make the image slightly brighter"
                    MediaTestKind.IMAGE_TO_VIDEO ->
                        "Add a very subtle slow zoom"
                }
            )

        if (kind != MediaTestKind.CREATE_IMAGE) {
            builder.addFormDataPart(
                "image",
                "connection-test.png",
                TEST_IMAGE_BYTES.toRequestBody(PNG_MEDIA_TYPE)
            )
        }
        if (kind == MediaTestKind.IMAGE_TO_VIDEO) {
            builder.addFormDataPart("duration", "5")
        }
        return builder.build()
    }

    private fun validateMediaSettings(
        baseUrl: String,
        apiKey: String,
        endpoint: String,
        model: String
    ): Boolean =
        baseUrl.isNotBlank() &&
            apiKey.isNotBlank() &&
            endpoint.isNotBlank() &&
            model.isNotBlank()

    private fun connectionError(code: Int, body: String): String {
        val output = body.take(500)
        return when (code) {
            400 -> "400 Invalid request: $output"
            401 -> "401 Unauthorized - Check your API key."
            402 -> "402 Payment Required - No credit or check billing."
            404 -> "404 Not Found - Wrong Base URL or path."
            413 -> "413 Payload too large."
            415 -> "415 Unsupported Media Type - Check JSON or multipart format."
            422 -> "422 Provider rejected the sample media: $output"
            429 -> "429 Rate Limit Exceeded - Sending too many requests."
            else -> "HTTP $code: $output"
        }
    }

    private fun actualTextApiKey(value: String): String =
        if (value == MASKED_KEY_PLACEHOLDER) settingsRepository.apiKey.value else value

    private fun buildUrl(baseUrl: String, endpoint: String): String =
        "${baseUrl.trimEnd('/')}/${endpoint.trim('/')}"

    private fun isXai(baseUrl: String): Boolean =
        baseUrl.contains("api.x.ai", ignoreCase = true)

    fun resetSaveState() {
        _uiState.update {
            it.copy(
                isSaved = false,
                isCreatePhotoSaved = false,
                isEditPhotoSaved = false,
                isPhotoVideoSaved = false
            )
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi,
        private val localStorage: com.example.data.LocalStorage
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class")
            }
            return SettingsViewModel(
                settingsRepository,
                okHttpClient,
                moshi,
                localStorage
            ) as T
        }
    }

    private enum class MediaTestKind {
        CREATE_IMAGE,
        EDIT_IMAGE,
        IMAGE_TO_VIDEO
    }

    companion object {
        private const val MASKED_KEY_PLACEHOLDER = "••••••••••••••••"
        private val JSON_MEDIA_TYPE =
            "application/json; charset=utf-8".toMediaType()
        private val PNG_MEDIA_TYPE = "image/png".toMediaType()
        private const val TEST_IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAIAAADTED8xAAAB+0lEQVR42u3TQQ0AAAjEMED5SeeNBloJS9ZJCr4aCTAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAATAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAbAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHgWu7LA4CJx71QAAAAAElFTkSuQmCC"
        private val TEST_IMAGE_BYTES: ByteArray by lazy {
            Base64.getDecoder().decode(TEST_IMAGE_BASE64)
        }
        private val TEST_IMAGE_DATA_URI: String by lazy {
            "data:image/png;base64,$TEST_IMAGE_BASE64"
        }
    }
}
