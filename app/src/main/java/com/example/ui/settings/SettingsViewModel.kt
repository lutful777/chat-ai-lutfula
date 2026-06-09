package com.example.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import com.example.data.MicrosoftAuthService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.microsoft.identity.client.IAccount

data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val firecrawlApiKey: String = "",
    val isSaved: Boolean = false,
    val microsoftAccount: IAccount? = null,
    
    // Create Photo
    val createPhotoProvider: String = "",
    val createPhotoApiKey: String = "",
    val createPhotoBaseUrl: String = "",
    val createPhotoEndpoint: String = "",
    val createPhotoModel: String = "",
    val createPhotoFormat: String = "JSON",

    // Edit Photo
    val editPhotoProvider: String = "",
    val editPhotoApiKey: String = "",
    val editPhotoBaseUrl: String = "",
    val editPhotoEndpoint: String = "",
    val editPhotoModel: String = "",
    val editPhotoFormat: String = "JSON",
    val editPhotoImageFormat: String = "base64",

    // Photo to Video
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

    // Save states
    val isCreatePhotoSaved: Boolean = false,
    val isEditPhotoSaved: Boolean = false,
    val isPhotoVideoSaved: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val microsoftAuthService: MicrosoftAuthService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        viewModelScope.launch {
            microsoftAuthService.account.collect { account ->
                _uiState.update { it.copy(microsoftAccount = account) }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val url = settingsRepository.baseUrl.first()
            val key = settingsRepository.apiKey.first()
            val model = settingsRepository.model.first()
            val firecrawlKey = settingsRepository.firecrawlApiKey.first()
            
            _uiState.update {
                it.copy(
                    baseUrl = url,
                    apiKey = key,
                    modelName = model,
                    firecrawlApiKey = firecrawlKey,
                    
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
                    editPhotoImageFormat = settingsRepository.editPhotoImageFormat.first(),
                    
                    photoVideoProvider = settingsRepository.photoVideoProvider.first(),
                    photoVideoApiKey = settingsRepository.photoVideoApiKey.first(),
                    photoVideoBaseUrl = settingsRepository.photoVideoBaseUrl.first(),
                    photoVideoCreateEndpoint = settingsRepository.photoVideoCreateEndpoint.first(),
                    photoVideoStatusEndpoint = settingsRepository.photoVideoStatusEndpoint.first(),
                    photoVideoResultEndpoint = settingsRepository.photoVideoResultEndpoint.first(),
                    photoVideoModel = settingsRepository.photoVideoModel.first(),
                    photoVideoFormat = settingsRepository.photoVideoFormat.first(),
                    photoVideoImageFormat = settingsRepository.photoVideoImageFormat.first(),
                    photoVideoDuration = settingsRepository.photoVideoDuration.first()
                )
            }
        }
    }

    // Chat Settings Updaters
    fun updateBaseUrl(url: String) { _uiState.update { it.copy(baseUrl = url, isSaved = false) } }
    fun updateApiKey(key: String) { _uiState.update { it.copy(apiKey = key, isSaved = false) } }
    fun updateModelName(model: String) { _uiState.update { it.copy(modelName = model, isSaved = false) } }
    fun updateFirecrawlApiKey(key: String) { _uiState.update { it.copy(firecrawlApiKey = key, isSaved = false) } }

    fun applyPreset(presetName: String) {
        val (url, model) = when (presetName) {
            "OpenAI" -> "https://api.openai.com/v1" to "gpt-4o"
            "OpenRouter" -> "https://openrouter.ai/api/v1" to "openai/o1-mini"
            "xAI" -> "https://api.x.ai/v1" to "grok-2-latest"
            else -> "" to ""
        }
        _uiState.update { 
            it.copy(
                baseUrl = url.takeIf { url.isNotEmpty() } ?: it.baseUrl,
                modelName = model.takeIf { model.isNotEmpty() } ?: it.modelName,
                isSaved = false
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveSettings(state.apiKey, state.baseUrl, state.modelName, state.firecrawlApiKey)
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    // Create Photo Updaters
    fun updateCreatePhotoProvider(value: String) { _uiState.update { it.copy(createPhotoProvider = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoApiKey(value: String) { _uiState.update { it.copy(createPhotoApiKey = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoBaseUrl(value: String) { _uiState.update { it.copy(createPhotoBaseUrl = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoEndpoint(value: String) { _uiState.update { it.copy(createPhotoEndpoint = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoModel(value: String) { _uiState.update { it.copy(createPhotoModel = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoFormat(value: String) { _uiState.update { it.copy(createPhotoFormat = value, isCreatePhotoSaved = false) } }

    fun saveCreatePhotoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveCreatePhotoSettings(
                state.createPhotoProvider, state.createPhotoApiKey, state.createPhotoBaseUrl,
                state.createPhotoEndpoint, state.createPhotoModel, state.createPhotoFormat
            )
            _uiState.update { it.copy(isCreatePhotoSaved = true) }
        }
    }

    // Edit Photo Updaters
    fun updateEditPhotoProvider(value: String) { _uiState.update { it.copy(editPhotoProvider = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoApiKey(value: String) { _uiState.update { it.copy(editPhotoApiKey = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoBaseUrl(value: String) { _uiState.update { it.copy(editPhotoBaseUrl = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoEndpoint(value: String) { _uiState.update { it.copy(editPhotoEndpoint = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoModel(value: String) { _uiState.update { it.copy(editPhotoModel = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoFormat(value: String) { _uiState.update { it.copy(editPhotoFormat = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoImageFormat(value: String) { _uiState.update { it.copy(editPhotoImageFormat = value, isEditPhotoSaved = false) } }

    fun saveEditPhotoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveEditPhotoSettings(
                state.editPhotoProvider, state.editPhotoApiKey, state.editPhotoBaseUrl,
                state.editPhotoEndpoint, state.editPhotoModel, state.editPhotoFormat, state.editPhotoImageFormat
            )
            _uiState.update { it.copy(isEditPhotoSaved = true) }
        }
    }

    // Photo Video Updaters
    fun updatePhotoVideoProvider(value: String) { _uiState.update { it.copy(photoVideoProvider = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoApiKey(value: String) { _uiState.update { it.copy(photoVideoApiKey = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoBaseUrl(value: String) { _uiState.update { it.copy(photoVideoBaseUrl = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoCreateEndpoint(value: String) { _uiState.update { it.copy(photoVideoCreateEndpoint = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoStatusEndpoint(value: String) { _uiState.update { it.copy(photoVideoStatusEndpoint = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoResultEndpoint(value: String) { _uiState.update { it.copy(photoVideoResultEndpoint = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoModel(value: String) { _uiState.update { it.copy(photoVideoModel = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoFormat(value: String) { _uiState.update { it.copy(photoVideoFormat = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoImageFormat(value: String) { _uiState.update { it.copy(photoVideoImageFormat = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoDuration(value: String) { _uiState.update { it.copy(photoVideoDuration = value, isPhotoVideoSaved = false) } }

    fun savePhotoVideoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.savePhotoVideoSettings(
                state.photoVideoProvider, state.photoVideoApiKey, state.photoVideoBaseUrl,
                state.photoVideoCreateEndpoint, state.photoVideoStatusEndpoint, state.photoVideoResultEndpoint,
                state.photoVideoModel, state.photoVideoFormat, state.photoVideoImageFormat, state.photoVideoDuration
            )
            _uiState.update { it.copy(isPhotoVideoSaved = true) }
        }
    }
    
    fun signInMicrosoft(activity: Activity) {
        viewModelScope.launch {
            microsoftAuthService.acquireTokenInteractive(activity)
        }
    }

    fun signOutMicrosoft() {
        viewModelScope.launch {
            microsoftAuthService.signOut()
        }
    }

    fun resetSaveState() {
        _uiState.update { it.copy(isSaved = false, isCreatePhotoSaved = false, isEditPhotoSaved = false, isPhotoVideoSaved = false) }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val microsoftAuthService: MicrosoftAuthService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(settingsRepository, microsoftAuthService) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

