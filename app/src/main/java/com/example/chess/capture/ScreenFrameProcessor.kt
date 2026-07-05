package com.example.chess.capture

import android.graphics.Bitmap
import com.example.chess.detection.BoardGeometry
import com.example.chess.detection.ChessBoardDetector
import com.example.chess.detection.ChessPieceRecognizer
import com.example.chess.detection.ChessPositionTracker
import com.example.chess.detection.PositionTrackingResult
import com.example.chess.domain.ChessAnalysisResult
import com.example.chess.domain.ChessColor
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class ScreenFrameProcessor(
    private val engine: ChessEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val boardDetector: ChessBoardDetector = ChessBoardDetector(),
    private val pieceRecognizer: ChessPieceRecognizer = ChessPieceRecognizer(),
    private val positionTracker: ChessPositionTracker = ChessPositionTracker()
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var frameJob: Job? = null
    private var analysisJob: Job? = null
    private var previousGeometry: BoardGeometry? = null
    private var lastAnalysis: ChessAnalysisResult? = null

    private val _resultFlow = MutableStateFlow<ProcessorState>(ProcessorState.Idle)
    val resultFlow = _resultFlow.asStateFlow()

    private val lastFen = AtomicReference("")

    fun updateSettings(settings: EngineSettings) {
        if (engine is RemoteStockfishEngine) {
            engine.updateSettings(settings.endpointUrl, settings.localFallback)
        }
    }

    fun processFrame(bitmap: Bitmap) {
        if (frameJob?.isActive == true) {
            bitmap.recycle()
            return
        }

        frameJob = scope.launch {
            try {
                _resultFlow.value = ProcessorState.SearchingBoard
                val geometry = boardDetector.detect(bitmap, previousGeometry)
                if (geometry == null) {
                    previousGeometry = null
                    _resultFlow.value = ProcessorState.BoardNotFound
                    return@launch
                }
                previousGeometry = geometry

                _resultFlow.value = ProcessorState.RecognizingPosition(geometry)
                val observation = pieceRecognizer.recognize(bitmap, geometry)
                when (val tracking = positionTracker.update(observation)) {
                    is PositionTrackingResult.Waiting -> {
                        _resultFlow.value = ProcessorState.WaitingForPosition(tracking.message, geometry)
                    }
                    is PositionTrackingResult.Lost -> {
                        lastFen.set("")
                        lastAnalysis = null
                        _resultFlow.value = ProcessorState.WaitingForPosition(tracking.message, geometry)
                    }
                    is PositionTrackingResult.Ready -> analyzePosition(tracking)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.printStackTrace()
                _resultFlow.value = ProcessorState.Error(error.message ?: "Gagal membaca papan")
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun analyzePosition(position: PositionTrackingResult.Ready) {
        val parts = position.fen.split(Regex("\\s+"))
        if (parts.size < 2) {
            _resultFlow.value = ProcessorState.Error("FEN hasil pembacaan tidak valid")
            return
        }

        val sideToMove = if (parts[1] == "w") ChessColor.WHITE else ChessColor.BLACK
        if (sideToMove != position.bottomSide) {
            analysisJob?.cancel()
            engine.stopAnalysis()
            lastAnalysis = null
            lastFen.set(position.fen)
            _resultFlow.value = ProcessorState.WaitingForOpponent(position.geometry)
            return
        }

        val cachedResult = lastAnalysis
        if (position.fen == lastFen.get() && cachedResult != null) {
            _resultFlow.value = ProcessorState.Result(
                fen = position.fen,
                result = cachedResult,
                bottomSide = position.bottomSide,
                whiteAtBottom = position.whiteAtBottom,
                geometry = position.geometry
            )
            return
        }

        lastFen.set(position.fen)
        analysisJob?.cancel()
        analysisJob = scope.launch {
            _resultFlow.value = ProcessorState.Analyzing(position.geometry)
            delay(220)
            if (!isActive || position.fen != lastFen.get()) return@launch

            try {
                val result = engine.analyze(position.fen, ChessApiConfig.DEFAULT_MOVE_TIME_MS)
                if (isActive && position.fen == lastFen.get()) {
                    if (isValidMove(result.bestMove, position.fen, position.bottomSide)) {
                        lastAnalysis = result
                        _resultFlow.value = ProcessorState.Result(
                            fen = position.fen,
                            result = result,
                            bottomSide = position.bottomSide,
                            whiteAtBottom = position.whiteAtBottom,
                            geometry = position.geometry
                        )
                    } else {
                        lastAnalysis = null
                        _resultFlow.value = ProcessorState.Error("Langkah dari Stockfish tidak valid")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.printStackTrace()
                if (isActive && position.fen == lastFen.get()) {
                    lastAnalysis = null
                    _resultFlow.value = ProcessorState.NetworkError(position.geometry)
                }
            }
        }
    }

    fun stop() {
        frameJob?.cancel()
        analysisJob?.cancel()
        engine.stopAnalysis()
        engine.close()
        positionTracker.reset()
        scope.cancel()
        lastFen.set("")
        lastAnalysis = null
        previousGeometry = null
        _resultFlow.value = ProcessorState.Idle
    }

    private fun isValidMove(move: String, fen: String, bottomSide: ChessColor): Boolean {
        if (!move.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?$"))) return false
        val fromFile = move[0] - 'a'
        val fromRankFromTop = '8' - move[1]
        val rows = fen.substringBefore(' ').split('/')
        if (rows.size != 8 || fromRankFromTop !in 0..7) return false

        val row = rows[fromRankFromTop]
        var file = 0
        var piece: Char? = null
        for (character in row) {
            if (character.isDigit()) {
                file += character.digitToInt()
            } else {
                if (file == fromFile) {
                    piece = character
                    break
                }
                file++
            }
            if (file > fromFile) break
        }
        if (piece == null) return false
        return if (bottomSide == ChessColor.WHITE) piece.isUpperCase() else piece.isLowerCase()
    }
}

sealed interface ProcessorState {
    object Idle : ProcessorState
    object SearchingBoard : ProcessorState
    object BoardNotFound : ProcessorState
    data class RecognizingPosition(val geometry: BoardGeometry) : ProcessorState
    data class WaitingForPosition(val message: String, val geometry: BoardGeometry?) : ProcessorState
    data class Analyzing(val geometry: BoardGeometry) : ProcessorState
    data class WaitingForOpponent(val geometry: BoardGeometry) : ProcessorState
    data class NetworkError(val geometry: BoardGeometry?) : ProcessorState
    data class Result(
        val fen: String,
        val result: ChessAnalysisResult,
        val bottomSide: ChessColor,
        val whiteAtBottom: Boolean,
        val geometry: BoardGeometry
    ) : ProcessorState
    data class Error(val message: String) : ProcessorState
}
