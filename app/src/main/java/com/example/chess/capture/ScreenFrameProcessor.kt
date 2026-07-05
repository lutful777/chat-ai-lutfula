package com.example.chess.capture

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
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
    private val onBestMove: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val detector = ChessBoardDetector()
    private val tracker = ChessPositionTracker()
    private val engine: ChessEngine = SimpleChessEngine()
    private val processing = AtomicBoolean(false)

    @Volatile
    private var closed = false

    private var lastAcceptedFrameAt = 0L

    fun submit(image: Image) {
        if (closed) {
            image.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAcceptedFrameAt < FRAME_INTERVAL_MS ||
            !processing.compareAndSet(false, true)) {
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
        if (detection == null) return

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

                ChessAssistantController.update(ChessAssistantState.Analyzing)
                val result = engine.analyze(
                    fen = tracking.fen,
                    depth = ENGINE_DEPTH
                )
                if (closed) return

                ChessAssistantController.update(
                    ChessAssistantState.Result(
                        fen = tracking.fen,
                        bestMove = result.bestMove,
                        evaluation = result.evaluation,
                        depth = result.depth,
                        boardConfidence = detection.confidence
                    )
                )
                onBestMove(result.bestMove)
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
        private const val FRAME_INTERVAL_MS = 1_000L
        private const val ENGINE_DEPTH = 3
    }
}
