import os

files = {
"app/src/main/java/com/example/chess/engine/ChessEngineFactory.kt": """package com.example.chess.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChessEngineFactory {
    suspend fun createEngine(context: Context, settings: EngineSettings): ChessEngine = withContext(Dispatchers.IO) {
        val fallback = if (settings.localFallback || !settings.onlineEnabled) {
            val binaryManager = StockfishBinaryManager(context)
            val path = binaryManager.getExecutablePath()
            if (path != null) {
                StockfishEngine(path)
            } else {
                SimpleChessEngine()
            }
        } else null
        
        if (!settings.onlineEnabled && fallback != null) {
            return@withContext fallback
        }
        
        RemoteStockfishEngine(settings.endpointUrl, fallback, settings.localFallback)
    }
}
""",
"app/src/main/java/com/example/chess/data/ChessSettingsRepository.kt": """package com.example.chess.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chessDataStore by preferencesDataStore(name = "chess_settings")

class ChessSettingsRepository(private val context: Context) {
    companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val ONLINE_ENABLED = booleanPreferencesKey("online_enabled")
        val ENDPOINT_URL = stringPreferencesKey("endpoint_url")
        val LOCAL_FALLBACK = booleanPreferencesKey("local_fallback")
        val FPS = intPreferencesKey("fps")
        val SHOW_EVAL = booleanPreferencesKey("show_eval")
        val SHOW_ARROW = booleanPreferencesKey("show_arrow")
    }

    val enabled: Flow<Boolean> = context.chessDataStore.data.map { it[ENABLED] ?: true }
    val onlineEnabled: Flow<Boolean> = context.chessDataStore.data.map { it[ONLINE_ENABLED] ?: true }
    val endpointUrl: Flow<String> = context.chessDataStore.data.map { it[ENDPOINT_URL] ?: "https://example.com/api/chess/analyze" }
    val localFallback: Flow<Boolean> = context.chessDataStore.data.map { it[LOCAL_FALLBACK] ?: false }
    val fps: Flow<Int> = context.chessDataStore.data.map { it[FPS] ?: 1 }
    val showEval: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_EVAL] ?: true }
    val showArrow: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_ARROW] ?: true }

    suspend fun updateEnabled(value: Boolean) { context.chessDataStore.edit { it[ENABLED] = value } }
    suspend fun updateOnlineEnabled(value: Boolean) { context.chessDataStore.edit { it[ONLINE_ENABLED] = value } }
    suspend fun updateEndpointUrl(value: String) { context.chessDataStore.edit { it[ENDPOINT_URL] = value } }
    suspend fun updateLocalFallback(value: Boolean) { context.chessDataStore.edit { it[LOCAL_FALLBACK] = value } }
    suspend fun updateFps(value: Int) { context.chessDataStore.edit { it[FPS] = value } }
    suspend fun updateShowEval(value: Boolean) { context.chessDataStore.edit { it[SHOW_EVAL] = value } }
    suspend fun updateShowArrow(value: Boolean) { context.chessDataStore.edit { it[SHOW_ARROW] = value } }
}
""",
"app/src/main/java/com/example/chess/capture/ScreenFrameProcessor.kt": """package com.example.chess.capture

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
""",
"app/src/main/java/com/example/chess/overlay/ChessOverlayManager.kt": """package com.example.chess.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class ChessOverlayManager(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var textView: TextView? = null
    private var arrowView: ArrowView? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun ensureOverlayView() {
        if (overlayView == null) {
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            textView = TextView(context).apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
                setPadding(32, 16, 32, 16)
                textSize = 14f
            }
            
            arrowView = ArrowView(context)

            overlayView = FrameLayout(context).apply {
                addView(arrowView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                
                val textParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = 100
                    leftMargin = 100
                }
                addView(textView, textParams)
            }

            try {
                windowManager?.addView(overlayView, layoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun showOverlay(bestMove: String, evaluation: String, depth: Int, ponder: String, playerSide: String, isLocalFallback: Boolean = false) {
        ensureOverlayView()
        val text = StringBuilder()
        if (isLocalFallback) {
            text.append("Mode lokal — akurasi lebih rendah\\n")
        } else {
            text.append("Stockfish online aktif\\n")
        }
        text.append("Saran sisi bawah: \$bestMove\\n")
        if (evaluation.isNotEmpty()) text.append("Eval: \$evaluation ")
        if (depth > 0) text.append("(d\$depth)\\n") else text.append("\\n")
        if (ponder.isNotEmpty()) text.append("PV: \$ponder")
        textView?.text = text.toString()
        
        if (bestMove.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?\$"))) {
            val fromCol = bestMove[0] - 'a'
            val fromRow = bestMove[1] - '1'
            val toCol = bestMove[2] - 'a'
            val toRow = bestMove[3] - '1'
            
            val squareSize = 100f
            val startX = if (playerSide == "w") fromCol * squareSize + 50f else (7 - fromCol) * squareSize + 50f
            val startY = if (playerSide == "w") (7 - fromRow) * squareSize + 250f else fromRow * squareSize + 250f
            
            val endX = if (playerSide == "w") toCol * squareSize + 50f else (7 - toCol) * squareSize + 50f
            val endY = if (playerSide == "w") (7 - toRow) * squareSize + 250f else toRow * squareSize + 250f
            
            arrowView?.setArrow(startX, startY, endX, endY)
        } else {
            arrowView?.clearArrow()
        }
    }
    
    fun showWaiting() {
        ensureOverlayView()
        textView?.text = "Menunggu langkah sisi atas..."
        arrowView?.clearArrow()
    }
    
    fun showAnalyzing() {
        ensureOverlayView()
        textView?.text = "Stockfish berpikir 3000 ms..."
        arrowView?.clearArrow()
    }
    
    fun showNetworkError() {
        ensureOverlayView()
        textView?.text = "Stockfish online tidak dapat dihubungi"
        arrowView?.clearArrow()
    }

    fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
            textView = null
            arrowView = null
        }
    }
}
"""
}

for filepath, content in files.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, 'w') as f:
        f.write(content)
print("Files generated successfully part 2.")
