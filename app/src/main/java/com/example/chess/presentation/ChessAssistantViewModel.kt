package com.example.chess.presentation

import androidx.lifecycle.ViewModel
import com.example.chess.domain.ChessAssistantState
import com.example.chess.domain.ChessAssistantStatusBus
import kotlinx.coroutines.flow.StateFlow

class ChessAssistantViewModel : ViewModel() {
    val state: StateFlow<ChessAssistantState> = ChessAssistantStatusBus.state

    fun startCapture() {
        ChessAssistantStatusBus.update(ChessAssistantState.RequestingPermission)
    }

    fun onPermissionGranted() {
        ChessAssistantStatusBus.update(ChessAssistantState.CapturingScreen)
    }

    fun onPermissionDenied() {
        ChessAssistantStatusBus.update(ChessAssistantState.Error("Izin perekaman layar ditolak"))
    }

    fun onOverlayPermissionRequired() {
        ChessAssistantStatusBus.update(
            ChessAssistantState.Error("Aktifkan izin tampil di atas aplikasi, lalu tekan Start kembali")
        )
    }

    fun stopCapture() {
        ChessAssistantStatusBus.update(ChessAssistantState.Idle)
    }
}
