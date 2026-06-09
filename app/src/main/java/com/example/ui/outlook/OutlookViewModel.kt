package com.example.ui.outlook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.EmailMessage
import com.example.data.MicrosoftGraphRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class OutlookUiState(
    val emails: List<EmailMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class OutlookViewModel(
    private val graphRepository: MicrosoftGraphRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OutlookUiState())
    val uiState: StateFlow<OutlookUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            graphRepository.isLoading.collectLatest { loading ->
                _uiState.value = _uiState.value.copy(isLoading = loading)
            }
        }
        viewModelScope.launch {
            graphRepository.error.collectLatest { error ->
                _uiState.value = _uiState.value.copy(error = error)
            }
        }
        viewModelScope.launch {
            graphRepository.emails.collectLatest { emails ->
                _uiState.value = _uiState.value.copy(emails = emails)
            }
        }
        loadEmails()
    }

    fun loadEmails() {
        viewModelScope.launch {
            graphRepository.loadLatestEmails()
        }
    }

    fun searchEmails(query: String) {
        if (query.isBlank()) {
            loadEmails()
            return
        }
        viewModelScope.launch {
            graphRepository.searchEmails(query)
        }
    }

    class Factory(
        private val graphRepository: MicrosoftGraphRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OutlookViewModel::class.java)) {
                return OutlookViewModel(graphRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
