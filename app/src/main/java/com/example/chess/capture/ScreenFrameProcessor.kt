package com.example.chess.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.os.SystemClock
import com.example.chess.detection.BoardOrientation
import com.example.chess.detection.ChessBoardDetector
import com.example.chess.detection.ChessPositionTracker
import com.example.chess.detection.PositionTrackingResult
import com.example.chess.domain.ChessAssistantState
import com.example.chess.engine.ChessEngine
import com.example.chess.engine.SimpleChessEngine
import com.example.chess.presentation.ChessAssistantController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ScreenFrameProcessor(
    framesPerSecond: Int,
    engineDepth: Int,
    private val minimumConfidence: Float,
    private val showEvaluation: Boolean,
    private val onBestMove: (
        move: String,
        boardBounds: Rect,
        orientation: BoardOrientation,
        frameWidth: Int,
        frameHeight: Int
    ) -> Unit = { _, _, _, _, _ -> },
    private val onWaitingForOpponent: () -> Unit = {},
    private val onBoardLost: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val detector = ChessBoardDetector()
    private val tracker = ChessPositionTracker()
    private val engine: ChessEngine = SimpleChessEngine()
    private val processing = AtomicBoolean(false)
    private val frameIntervalMs = 1_000L / framesPerSecond.coerceIn(1, 3)
    private val analysisDepth = engineDepth.coerceIn(1, 3)

    @Volatile
    private var closed = false

    private var lastAcceptedFrameAt = 0L
    private var missingBoardFrames = 0

    fun submit(image: Image) {
        if (closed) {
            image.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAcceptedFrameAt < frameIntervalMs ||
            !processing.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastAcceptedFrameAt = now

        val bitmap = try {
            imageToBitmap(image)
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            processing.set(false)
            return
        }

        scope.launch {
            try {
                processBitmap(bitmap)
            } catch (error: Throwable) {
                if (!closed) {
                    ChessAssistantController.update(
                        ChessAssistantState.Error(
                            error.message ?: "Pemrosesan layar gagal."
                        )
                    )
                }
            } finally {
                bitmap.recycle()
                processing.set(false)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        engine.stopAnalysis()
        engine.close()
        tracker.reset()
        scope.cancel()
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        if (closed) return

        val previousState = ChessAssistantController.state.value
        if (previousState !is ChessAssistantState.Result) {
            ChessAssistantController.update(ChessAssistantState.SearchingBoard)
        }

        val detection = detector.detectBoard(bitmap)
        if (detection == null || detection.confidence < minimumConfidence) {
            missingBoardFrames++
            if (missingBoardFrames >= BOARD_LOST_FRAME_LIMIT) {
                onBoardLost()
                if (previousState is ChessAssistantState.Result) {
                    ChessAssistantController.update(ChessAssistantState.SearchingBoard)
                }
            }
            return
        }
        missingBoardFrames = 0

        if (previousState !is ChessAssistantState.Result) {
            ChessAssistantController.update(ChessAssistantState.RecognizingPosition)
        }

        when (val tracking = tracker.update(detection)) {
            is PositionTrackingResult.Waiting -> {
                if (previousState !is ChessAssistantState.Result) {
                    ChessAssistantController.update(
                        ChessAssistantState.Waiting(tracking.message)
                    )
                }
            }

            is PositionTrackingResult.Position -> {
                if (!tracking.changed) return

                if (!BottomSidePolicy.isBottomSideTurn(tracking.fen, tracking.orientation)) {
                    engine.stopAnalysis()
                    onWaitingForOpponent()
                    ChessAssistantController.update(
                        ChessAssistantState.Waiting(
                            "Menunggu langkah sisi atas. Arahan hanya diberikan untuk bidak di bagian bawah."
                        )
                    )
                    return
                }

                ChessAssistantController.update(ChessAssistantState.Analyzing)
                val result = engine.analyze(
                    fen = tracking.fen,
                    depth = analysisDepth
                )
                if (closed) return

                ChessAssistantController.update(
                    ChessAssistantState.Result(
                        fen = tracking.fen,
                        bestMove = result.bestMove,
                        evaluation = if (showEvaluation) result.evaluation else "Disembunyikan",
                        depth = result.depth,
                        boardConfidence = detection.confidence
                    )
                )
                onBestMove(
                    result.bestMove,
                    Rect(detection.bounds),
                    tracking.orientation,
                    bitmap.width,
                    bitmap.height
                )
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull()
            ?: error("Frame layar tidak memiliki pixel plane.")
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)

        if (paddedWidth == image.width) return padded

        val cropped = Bitmap.createBitmap(
            padded,
            0,
            0,
            image.width,
            image.height
        )
        padded.recycle()
        return cropped
    }

    companion object {
        private const val BOARD_LOST_FRAME_LIMIT = 3
    }
}
