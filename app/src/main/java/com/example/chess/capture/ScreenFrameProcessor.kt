package com.example.chess.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.os.SystemClock
import com.example.chess.detection.BoardOrientation
import com.example.chess.detection.ChessBoardDetector
import com.example.chess.detection.ChessPositionTracker
import com.example.chess.detection.PositionTrackingResult
import com.example.chess.domain.ChessAnalysisResult
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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ScreenFrameProcessor(
    private val engine: ChessEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    framesPerSecond: Int = 1,
    private val minimumConfidence: Float = 0.15f
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val detector = ChessBoardDetector()
    private val tracker = ChessPositionTracker()
    private val processing = AtomicBoolean(false)
    private val frameIntervalMs = 1_000L / framesPerSecond.coerceIn(1, 3)

    private val _resultFlow = MutableStateFlow<ProcessorState>(ProcessorState.Idle)
    val resultFlow = _resultFlow.asStateFlow()

    private var analysisJob: Job? = null
    private var lastAcceptedFrameAt = 0L
    private var missingBoardFrames = 0
    private var latestFen = ""
    private var manualOrientation: BoardOrientation? = null

    @Volatile
    private var closed = false

    fun updateSettings(settings: EngineSettings) {
        if (engine is RemoteStockfishEngine) {
            engine.updateSettings(settings.endpointUrl, settings.localFallback)
        }
    }

    fun submit(image: Image) {
        if (closed) {
            image.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAcceptedFrameAt < frameIntervalMs || !processing.compareAndSet(false, true)) {
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
            } finally {
                bitmap.recycle()
                processing.set(false)
            }
        }
    }

    fun processFrame(fen: String) {
        if (fen.isBlank() || closed) return
        val orientation = manualOrientation ?: inferOrientationFromFen(fen).also {
            manualOrientation = it
        }
        schedulePosition(
            fen = fen,
            orientation = orientation,
            boardBounds = Rect(0, 0, 800, 800),
            frameWidth = 800,
            frameHeight = 800
        )
    }

    fun stop() = close()

    fun close() {
        if (closed) return
        closed = true
        analysisJob?.cancel()
        engine.stopAnalysis()
        engine.close()
        tracker.reset()
        scope.cancel()
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        if (closed) return
        val previousState = _resultFlow.value
        val detection = detector.detectBoard(bitmap)

        if (detection == null || detection.confidence < minimumConfidence) {
            missingBoardFrames++
            if (missingBoardFrames >= BOARD_LOST_FRAME_LIMIT) {
                analysisJob?.cancel()
                engine.stopAnalysis()
                latestFen = ""
                _resultFlow.value = ProcessorState.SearchingBoard
            }
            return
        }

        missingBoardFrames = 0
        if (previousState !is ProcessorState.Result) {
            _resultFlow.value = ProcessorState.RecognizingPosition
        }

        when (val tracking = tracker.update(detection)) {
            is PositionTrackingResult.Waiting -> {
                if (previousState !is ProcessorState.Result) {
                    _resultFlow.value = ProcessorState.Waiting(tracking.message)
                }
            }

            is PositionTrackingResult.Position -> {
                if (!tracking.changed) return
                schedulePosition(
                    fen = tracking.fen,
                    orientation = tracking.orientation,
                    boardBounds = Rect(detection.bounds),
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height
                )
            }
        }
    }

    private fun schedulePosition(
        fen: String,
        orientation: BoardOrientation,
        boardBounds: Rect,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (closed || fen == latestFen) return
        latestFen = fen
        analysisJob?.cancel()
        engine.stopAnalysis()

        if (!BottomSidePolicy.isBottomSideTurn(fen, orientation)) {
            _resultFlow.value = ProcessorState.WaitingForOpponent
            return
        }

        _resultFlow.value = ProcessorState.Analyzing
        analysisJob = scope.launch {
            try {
                delay(250)
                val result = engine.analyze(fen, 3000)
                if (closed || fen != latestFen) return@launch

                if (!isValidMove(result.bestMove, fen, orientation)) {
                    _resultFlow.value = ProcessorState.Error("Langkah Stockfish tidak valid untuk sisi bawah.")
                    return@launch
                }

                _resultFlow.value = ProcessorState.Result(
                    fen = fen,
                    result = result,
                    orientation = orientation,
                    boardBounds = Rect(boardBounds),
                    frameWidth = frameWidth,
                    frameHeight = frameHeight
                )
            } catch (_: CancellationException) {
                // Posisi sudah berubah atau service dihentikan.
            } catch (error: Throwable) {
                if (!closed && fen == latestFen) {
                    _resultFlow.value = ProcessorState.NetworkError(
                        error.message ?: "Stockfish online tidak dapat dihubungi."
                    )
                }
            }
        }
    }

    private fun isValidMove(
        move: String,
        fen: String,
        orientation: BoardOrientation
    ): Boolean {
        if (!move.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?$"))) return false
        if (!BottomSidePolicy.isBottomSideTurn(fen, orientation)) return false

        val fromCol = move[0] - 'a'
        val fromRow = '8' - move[1]
        val rows = fen.substringBefore(' ').split('/')
        if (rows.size != 8 || fromRow !in 0..7 || fromCol !in 0..7) return false

        var logicalCol = 0
        var pieceAtSource: Char? = null
        for (char in rows[fromRow]) {
            if (char.isDigit()) {
                logicalCol += char.digitToInt()
            } else {
                if (logicalCol == fromCol) {
                    pieceAtSource = char
                    break
                }
                logicalCol++
            }
            if (logicalCol > fromCol) break
        }

        val piece = pieceAtSource ?: return false
        return when (orientation) {
            BoardOrientation.WHITE_BOTTOM -> piece.isUpperCase()
            BoardOrientation.BLACK_BOTTOM -> piece.isLowerCase()
        }
    }

    private fun inferOrientationFromFen(fen: String): BoardOrientation {
        val rows = fen.substringBefore(' ').split('/')
        val bottom = rows.getOrNull(7).orEmpty()
        return if (bottom.any { it.isUpperCase() }) {
            BoardOrientation.WHITE_BOTTOM
        } else {
            BoardOrientation.BLACK_BOTTOM
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

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    companion object {
        private const val BOARD_LOST_FRAME_LIMIT = 3
    }
}

sealed interface ProcessorState {
    object Idle : ProcessorState
    object SearchingBoard : ProcessorState
    object RecognizingPosition : ProcessorState
    object Analyzing : ProcessorState
    object WaitingForOpponent : ProcessorState
    data class Waiting(val message: String) : ProcessorState
    data class NetworkError(val message: String) : ProcessorState
    data class Result(
        val fen: String,
        val result: ChessAnalysisResult,
        val orientation: BoardOrientation,
        val boardBounds: Rect,
        val frameWidth: Int,
        val frameHeight: Int
    ) : ProcessorState
    data class Error(val message: String) : ProcessorState
}
