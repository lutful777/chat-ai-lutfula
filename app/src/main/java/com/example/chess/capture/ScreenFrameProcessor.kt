package com.example.chess.capture

import com.example.chess.domain.ChessAnalysisResult
import com.example.chess.engine.ChessApiConfig
import com.example.chess.engine.ChessEngine
import com.example.chess.engine.EngineSettings
import com.example.chess.engine.RemoteStockfishEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val lastFen = AtomicReference("")
    private var lockedBottomSide: String? = null

    fun updateSettings(settings: EngineSettings) {
        if (engine is RemoteStockfishEngine) {
            engine.updateSettings(settings.endpointUrl, settings.localFallback)
        }
    }

    fun processFrame(fen: String) {
        if (fen.isBlank() || fen == lastFen.get()) return
        lastFen.set(fen)

        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            _resultFlow.value = ProcessorState.Error("FEN tidak valid")
            return
        }
        val turn = parts[1]

        if (lockedBottomSide == null) {
            val rows = parts[0].split("/")
            lockedBottomSide = if (rows.lastOrNull()?.any { it.isUpperCase() } == true) "w" else "b"
        }

        if (turn != lockedBottomSide) {
            analysisJob?.cancel()
            engine.stopAnalysis()
            _resultFlow.value = ProcessorState.WaitingForOpponent
            return
        }

        analysisJob?.cancel()
        analysisJob = scope.launch {
            _resultFlow.value = ProcessorState.Analyzing
            delay(300)

            if (!isActive) return@launch

            try {
                val result = engine.analyze(fen, ChessApiConfig.DEFAULT_MOVE_TIME_MS)
                if (isActive && fen == lastFen.get()) {
                    _resultFlow.value = if (isValidMove(result.bestMove, fen)) {
                        ProcessorState.Result(fen, result, lockedBottomSide ?: "w")
                    } else {
                        ProcessorState.Error("Langkah dari Stockfish tidak valid")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.printStackTrace()
                if (isActive && fen == lastFen.get()) {
                    _resultFlow.value = ProcessorState.NetworkError
                }
            }
        }
    }

    fun stop() {
        analysisJob?.cancel()
        engine.stopAnalysis()
        engine.close()
        scope.cancel()
        lastFen.set("")
        _resultFlow.value = ProcessorState.Idle
    }

    private fun isValidMove(move: String, fen: String): Boolean {
        if (move.isBlank() || move == "(none)" || move == "0000" || move == "null") return false
        if (!move.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?$"))) return false

        val fromCol = move[0] - 'a'
        val fromRow = '8' - move[1]
        val rows = fen.substringBefore(' ').split("/")
        if (rows.size != 8 || fromRow !in 0..7) return false

        val row = rows[fromRow]
        var column = 0
        var pieceAtSquare: Char? = null
        for (character in row) {
            if (character.isDigit()) {
                column += character.digitToInt()
            } else {
                if (column == fromCol) {
                    pieceAtSquare = character
                    break
                }
                column++
            }
            if (column > fromCol) break
        }

        if (pieceAtSquare == null) return false
        if (lockedBottomSide == "w" && pieceAtSquare.isLowerCase()) return false
        if (lockedBottomSide == "b" && pieceAtSquare.isUpperCase()) return false
        return true
    }
}

sealed interface ProcessorState {
    object Idle : ProcessorState
    object Analyzing : ProcessorState
    object WaitingForOpponent : ProcessorState
    object NetworkError : ProcessorState
    data class Result(
        val fen: String,
        val result: ChessAnalysisResult,
        val lockedBottomSide: String
    ) : ProcessorState
    data class Error(val message: String) : ProcessorState
}
