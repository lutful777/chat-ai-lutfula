package com.example.chess.presentation

import com.example.chess.domain.ChessAssistantState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local state bridge between the foreground capture service and Compose UI.
 * No bitmap or screen data is retained here.
 */
object ChessAssistantController {
    private val _state = MutableStateFlow<ChessAssistantState>(ChessAssistantState.Idle)
    val state: StateFlow<ChessAssistantState> = _state.asStateFlow()

    fun update(state: ChessAssistantState) {
        _state.value = state
    }

    fun reset() {
        _state.value = ChessAssistantState.Idle
    }
}
