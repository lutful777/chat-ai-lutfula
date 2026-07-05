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
        ChessAssistantController.update(
            ChessAssistantState.Error("Izin membaca layar ditolak.")
        )
    }

    fun onOverlayPermissionDenied() {
        ChessAssistantController.update(
            ChessAssistantState.Error(
                "Izin tampil di atas aplikasi lain diperlukan agar panah dapat terlihat."
            )
        )
    }

    fun stopCapture() {
        ChessAssistantController.reset()
    }
}
