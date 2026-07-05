import os

files = {
"app/src/main/java/com/example/chess/engine/UciEngine.kt": """package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class UciEngine(private val enginePath: String) : ChessEngine {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    fun start() {
        try {
            process = ProcessBuilder(enginePath).start()
            reader = BufferedReader(InputStreamReader(process?.inputStream))
            writer = BufferedWriter(OutputStreamWriter(process?.outputStream))
            sendCommand("uci")
            // Wait for uciok
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                if (line == "uciok") break
            }
            sendCommand("isready")
            while (reader?.readLine().also { line = it } != null) {
                if (line == "readyok") break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult = withContext(Dispatchers.IO) {
        if (process == null) start()
        sendCommand("position fen \$fen")
        sendCommand("go depth \$depth")
        
        var bestMove = ""
        var ponder = ""
        var eval = ""
        
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                val currentLine = line ?: ""
                if (currentLine.startsWith("info depth \$depth") && currentLine.contains("score cp")) {
                    val parts = currentLine.split(" ")
                    val scoreIndex = parts.indexOf("cp")
                    if (scoreIndex != -1 && scoreIndex + 1 < parts.size) {
                        val scoreCp = parts[scoreIndex + 1].toIntOrNull() ?: 0
                        eval = String.format("%.2f", scoreCp / 100.0)
                    }
                } else if (currentLine.startsWith("info depth \$depth") && currentLine.contains("score mate")) {
                    val parts = currentLine.split(" ")
                    val scoreIndex = parts.indexOf("mate")
                    if (scoreIndex != -1 && scoreIndex + 1 < parts.size) {
                        eval = "M" + parts[scoreIndex + 1]
                    }
                }
                
                if (currentLine.startsWith("bestmove")) {
                    val parts = currentLine.split(" ")
                    if (parts.size >= 2) {
                        bestMove = parts[1]
                    }
                    if (parts.size >= 4 && parts[2] == "ponder") {
                        ponder = parts[3]
                    }
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        ChessAnalysisResult(bestMove, ponder, eval, depth)
    }

    override fun stopAnalysis() {
        sendCommand("stop")
    }

    override fun close() {
        sendCommand("quit")
        try {
            process?.waitFor()
        } catch (e: Exception) {}
        process = null
    }

    private fun sendCommand(command: String) {
        try {
            writer?.write(command + "\\n")
            writer?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val DEPTH = intPreferencesKey("depth")
        val MULTI_PV = intPreferencesKey("multi_pv")
        val FPS = intPreferencesKey("fps")
        val MIN_CONFIDENCE = floatPreferencesKey("min_confidence")
        val SHOW_EVAL = booleanPreferencesKey("show_eval")
        val SHOW_ARROW = booleanPreferencesKey("show_arrow")
        val PLAYER_SIDE = stringPreferencesKey("player_side") // AUTO, WHITE, BLACK
    }

    val enabled: Flow<Boolean> = context.chessDataStore.data.map { it[ENABLED] ?: true }
    val depth: Flow<Int> = context.chessDataStore.data.map { it[DEPTH] ?: 10 }
    val multiPv: Flow<Int> = context.chessDataStore.data.map { it[MULTI_PV] ?: 1 }
    val fps: Flow<Int> = context.chessDataStore.data.map { it[FPS] ?: 1 }
    val minConfidence: Flow<Float> = context.chessDataStore.data.map { it[MIN_CONFIDENCE] ?: 0.7f }
    val showEval: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_EVAL] ?: true }
    val showArrow: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_ARROW] ?: true }
    val playerSide: Flow<String> = context.chessDataStore.data.map { it[PLAYER_SIDE] ?: "AUTO" }

    suspend fun updateEnabled(value: Boolean) { context.chessDataStore.edit { it[ENABLED] = value } }
    suspend fun updateDepth(value: Int) { context.chessDataStore.edit { it[DEPTH] = value } }
    suspend fun updateMultiPv(value: Int) { context.chessDataStore.edit { it[MULTI_PV] = value } }
    suspend fun updateFps(value: Int) { context.chessDataStore.edit { it[FPS] = value } }
    suspend fun updateMinConfidence(value: Float) { context.chessDataStore.edit { it[MIN_CONFIDENCE] = value } }
    suspend fun updateShowEval(value: Boolean) { context.chessDataStore.edit { it[SHOW_EVAL] = value } }
    suspend fun updateShowArrow(value: Boolean) { context.chessDataStore.edit { it[SHOW_ARROW] = value } }
    suspend fun updatePlayerSide(value: String) { context.chessDataStore.edit { it[PLAYER_SIDE] = value } }
}
""",
"app/src/main/java/com/example/chess/overlay/ArrowView.kt": """package com.example.chess.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ArrowView(context: Context) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDrawing = false

    private val paint = Paint().apply {
        color = Color.parseColor("#80FF0000") // Semi-transparent red
        strokeWidth = 15f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowPaint = Paint().apply {
        color = Color.parseColor("#80FF0000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setArrow(sx: Float, sy: Float, ex: Float, ey: Float) {
        startX = sx
        startY = sy
        endX = ex
        endY = ey
        isDrawing = true
        invalidate()
    }

    fun clearArrow() {
        isDrawing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDrawing) return

        canvas.drawLine(startX, startY, endX, endY, paint)

        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val arrowSize = 40.0
        val arrowAngle = Math.PI / 6.0

        val x1 = endX - arrowSize * cos(angle - arrowAngle)
        val y1 = endY - arrowSize * sin(angle - arrowAngle)
        val x2 = endX - arrowSize * cos(angle + arrowAngle)
        val y2 = endY - arrowSize * sin(angle + arrowAngle)

        val path = Path()
        path.moveTo(endX, endY)
        path.lineTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        path.close()

        canvas.drawPath(path, arrowPaint)
    }
}
"""
}

for filepath, content in files.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, 'w') as f:
        f.write(content)
print("Files generated successfully.")
