package com.example.chess.capture

import com.example.chess.domain.ChessAnalysisResult
import com.example.chess.engine.ChessEngine
import com.example.chess.engine.EngineSettings
import com.example.chess.engine.RemoteStockfishEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

class ScreenFrameProcessor(
    private val engine: ChessEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var analysisJob: Job? = null
    
    private val _resultFlow = MutableStateFlow<ProcessorState>(ProcessorState.Idle)
    val resultFlow = _resultFlow.asStateFlow()
    
    private val lastFen = AtomicReference<String>("")
    private var engineSettings = EngineSettings()
    
    private var lockedBottomSide: String? = null // "w" or "b"

    fun updateSettings(settings: EngineSettings) {
        engineSettings = settings
        if (engine is RemoteStockfishEngine) {
            engine.updateSettings(settings.endpointUrl, settings.localFallback)
        }
    }

    fun processFrame(fen: String) {
        if (fen.isBlank() || fen == lastFen.get()) return
        lastFen.set(fen)

        val parts = fen.split(" ")
        val turn = if (parts.size > 1) parts[1] else "w"
        
        if (lockedBottomSide == null) {
            val rows = parts[0].split("/")
            if (rows.isNotEmpty()) {
                val lastRow = rows.last()
                if (lastRow.contains(Regex("[A-Z]"))) {
                    lockedBottomSide = "w" // White at bottom
                } else {
                    lockedBottomSide = "b" // Black at bottom
                }
            } else {
                lockedBottomSide = "w"
            }
        }

        val isUserTurn = (turn == lockedBottomSide)

        if (!isUserTurn) {
            analysisJob?.cancel()
            engine.stopAnalysis()
            _resultFlow.value = ProcessorState.WaitingForOpponent
            return
        }

        analysisJob?.cancel()
        analysisJob = scope.launch {
            _resultFlow.value = ProcessorState.Analyzing
            delay(300) // Debounce
            
            if (isActive) {
                try {
                    val result = engine.analyze(fen, 3000)
                    
                    if (isActive && fen == lastFen.get()) {
                        if (isValidMove(result.bestMove, fen)) {
                            _resultFlow.value = ProcessorState.Result(fen, result, lockedBottomSide ?: "w")
                        } else {
                            _resultFlow.value = ProcessorState.Error("Invalid move from engine")
                        }
                    }
                } catch (e: Exception) {
                    if (isActive && fen == lastFen.get()) {
                        _resultFlow.value = ProcessorState.NetworkError
                    }
                }
            }
        }
    }

    fun stop() {
        analysisJob?.cancel()
        engine.close()
        scope.cancel()
    }

    private fun isValidMove(move: String, fen: String): Boolean {
        if (move.isBlank() || move == "(none)" || move == "0000" || move == "null") return false
        if (!move.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?\$"))) return false
        
        val fromCol = move[0] - 'a'
        val fromRow = '8' - move[1]
        
        val rows = fen.split(" ")[0].split("/")
        if (fromRow in 0..7) {
            val rowStr = rows[fromRow]
            var colIdx = 0
            var pieceAtSquare: Char? = null
            for (char in rowStr) {
                if (char.isDigit()) {
                    colIdx += char.digitToInt()
                } else {
                    if (colIdx == fromCol) {
                        pieceAtSquare = char
                        break
                    }
                    colIdx++
                }
                if (colIdx > fromCol) break
            }
            
            if (pieceAtSquare != null) {
                if (lockedBottomSide == "w" && pieceAtSquare.isLowerCase()) return false
                if (lockedBottomSide == "b" && pieceAtSquare.isUpperCase()) return false
            }
        }
        
        return true
    }
}

sealed interface ProcessorState {
    object Idle : ProcessorState
    object Analyzing : ProcessorState
    object WaitingForOpponent : ProcessorState
    object NetworkError : ProcessorState
    data class Result(val fen: String, val result: ChessAnalysisResult, val lockedBottomSide: String) : ProcessorState
    data class Error(val message: String) : ProcessorState
}
