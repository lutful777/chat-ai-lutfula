package com.example.chess.presentation

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chess.detection.ChessBoardDetector
import com.example.chess.detection.ChessPositionTracker
import com.example.chess.detection.PositionTrackingResult
import com.example.chess.domain.ChessAssistantState
import com.example.chess.engine.ChessEngine
import com.example.chess.engine.SimpleChessEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChessAssistantViewModel : ViewModel() {
    val state: StateFlow<ChessAssistantState> = ChessAssistantController.state

    private val detector = ChessBoardDetector()
    private val tracker = ChessPositionTracker()
    private val engine: ChessEngine = SimpleChessEngine()
    private var analysisJob: Job? = null

    fun startCapture() {
        ChessAssistantController.update(ChessAssistantState.RequestingPermission)
    }

    fun onPermissionDenied() {
        ChessAssistantController.update(ChessAssistantState.Error("Tidak ada gambar yang dipilih."))
    }

    fun analyzeBitmap(bitmap: Bitmap) {
        analysisJob?.cancel()
        engine.stopAnalysis()
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                ChessAssistantController.update(ChessAssistantState.SearchingBoard)
                val detection = detector.detectBoard(bitmap)
                if (detection == null) {
                    ChessAssistantController.update(
                        ChessAssistantState.Error("Papan catur tidak ditemukan pada gambar.")
                    )
                    return@launch
                }

                ChessAssistantController.update(ChessAssistantState.RecognizingPosition)
                var tracking: PositionTrackingResult = tracker.update(detection)
                if (tracking is PositionTrackingResult.Waiting &&
                    tracking.message.startsWith("Menunggu gambar papan stabil")) {
                    tracking = tracker.update(detection)
                }

                when (tracking) {
                    is PositionTrackingResult.Waiting -> {
                        ChessAssistantController.update(ChessAssistantState.Error(tracking.message))
                    }
                    is PositionTrackingResult.Position -> {
                        ChessAssistantController.update(ChessAssistantState.Analyzing)
                        val result = engine.analyze(tracking.fen, depth = 3)
                        ChessAssistantController.update(
                            ChessAssistantState.Result(
                                fen = tracking.fen,
                                bestMove = result.bestMove,
                                evaluation = result.evaluation,
                                depth = result.depth,
                                boardConfidence = detection.confidence
                            )
                        )
                    }
                }
            } catch (error: Throwable) {
                ChessAssistantController.update(
                    ChessAssistantState.Error(error.message ?: "Analisis gambar gagal.")
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun stopCapture() {
        analysisJob?.cancel()
        analysisJob = null
        engine.stopAnalysis()
        tracker.reset()
        ChessAssistantController.reset()
    }

    override fun onCleared() {
        analysisJob?.cancel()
        engine.close()
        super.onCleared()
    }
}
