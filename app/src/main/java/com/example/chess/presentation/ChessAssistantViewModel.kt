package com.example.chess.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chess.domain.ChessAssistantState
import com.example.chess.engine.SimpleChessEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class ChessAssistantViewModel : ViewModel() {
    private val _state = MutableStateFlow<ChessAssistantState>(ChessAssistantState.Idle)
    val state: StateFlow<ChessAssistantState> = _state.asStateFlow()
    
    private val engine = SimpleChessEngine()

    fun startCapture() {
        _state.value = ChessAssistantState.RequestingPermission
    }
    
    fun onPermissionGranted() {
        _state.value = ChessAssistantState.CapturingScreen
    }
    
    fun onPermissionDenied() {
        _state.value = ChessAssistantState.Error("Permission denied")
    }

    fun stopCapture() {
        _state.value = ChessAssistantState.Idle
        engine.stopAnalysis()
    }
    
    // Simulate pipeline
    fun simulatePipeline() {
        viewModelScope.launch {
            _state.value = ChessAssistantState.SearchingBoard
            delay(1000)
            _state.value = ChessAssistantState.RecognizingPosition
            delay(1000)
            _state.value = ChessAssistantState.Analyzing
            
            val result = engine.analyze("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 10)
            _state.value = ChessAssistantState.Result(
                fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                bestMove = result.bestMove,
                evaluation = result.evaluation
            )
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
