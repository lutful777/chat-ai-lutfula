package com.example.chess.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ChessAssistantStatusBus {
    private val _state = MutableStateFlow<ChessAssistantState>(ChessAssistantState.Idle)
    val state: StateFlow<ChessAssistantState> = _state.asStateFlow()

    fun update(state: ChessAssistantState) {
        _state.value = state
    }
}
