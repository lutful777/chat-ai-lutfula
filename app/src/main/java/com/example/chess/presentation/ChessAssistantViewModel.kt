package com.example.chess.presentation

import androidx.lifecycle.ViewModel
import com.example.chess.domain.ChessAssistantState
import kotlinx.coroutines.flow.StateFlow

class ChessAssistantViewModel : ViewModel() {
    val state: StateFlow<ChessAssistantState> = ChessAssistantController.state

    fun startCapture() {
        ChessAssistantController.update(ChessAssistantState.RequestingPermission)
    }

    fun onPermissionDenied() {
        ChessAssistantController.update(ChessAssistantState.Error("Permission denied"))
    }

    fun stopCapture() {
        ChessAssistantController.reset()
    }
}
