package com.example.chess.presentation

import androidx.lifecycle.ViewModel
import com.example.chess.domain.ChessAssistantState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChessAssistantViewModel : ViewModel() {
    private val _state = MutableStateFlow<ChessAssistantState>(ChessAssistantState.Idle)
    val state: StateFlow<ChessAssistantState> = _state.asStateFlow()

    fun startCapture() {
        _state.value = ChessAssistantState.RequestingPermission
    }

    fun onPermissionGranted() {
        _state.value = ChessAssistantState.CapturingScreen
    }

    fun onPermissionDenied(message: String = "Permission denied") {
        _state.value = ChessAssistantState.Error(message)
    }

    fun stopCapture() {
        _state.value = ChessAssistantState.Idle
    }
}
