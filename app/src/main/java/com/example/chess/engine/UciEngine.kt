package com.example.chess.engine

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
        process = ProcessBuilder(enginePath).start()
        reader = BufferedReader(InputStreamReader(process?.inputStream))
        writer = BufferedWriter(OutputStreamWriter(process?.outputStream))
        sendCommand("uci")
        waitFor("uciok")
        sendCommand("isready")
        waitFor("readyok")
    }

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult = withContext(Dispatchers.IO) {
        if (process == null) start()
        sendCommand("position fen $fen")
        sendCommand("go depth $depth")

        var bestMove = "-"
        var ponder: String? = null
        var evaluation = "0.00"
        var reachedDepth = 0

        while (true) {
            val currentLine = reader?.readLine() ?: break
            if (currentLine.startsWith("info ")) {
                val parts = currentLine.split(' ')
                val depthIndex = parts.indexOf("depth")
                if (depthIndex >= 0 && depthIndex + 1 < parts.size) {
                    reachedDepth = parts[depthIndex + 1].toIntOrNull() ?: reachedDepth
                }
                val cpIndex = parts.indexOf("cp")
                val mateIndex = parts.indexOf("mate")
                when {
                    cpIndex >= 0 && cpIndex + 1 < parts.size -> {
                        val score = parts[cpIndex + 1].toIntOrNull() ?: 0
                        evaluation = "%+.2f".format(score / 100.0)
                    }
                    mateIndex >= 0 && mateIndex + 1 < parts.size -> {
                        evaluation = "M${parts[mateIndex + 1]}"
                    }
                }
            }

            if (currentLine.startsWith("bestmove")) {
                val parts = currentLine.split(' ')
                if (parts.size >= 2) bestMove = parts[1]
                val ponderIndex = parts.indexOf("ponder")
                if (ponderIndex >= 0 && ponderIndex + 1 < parts.size) {
                    ponder = parts[ponderIndex + 1]
                }
                break
            }
        }

        ChessAnalysisResult(
            bestMove = bestMove,
            ponderMove = ponder,
            evaluation = evaluation,
            depth = if (reachedDepth > 0) reachedDepth else depth
        )
    }

    override fun stopAnalysis() {
        sendCommand("stop")
    }

    override fun close() {
        runCatching { sendCommand("quit") }
        runCatching { process?.destroy() }
        process = null
        reader = null
        writer = null
    }

    private fun waitFor(expected: String) {
        while (true) {
            val line = reader?.readLine() ?: error("UCI engine stopped before $expected")
            if (line == expected) return
        }
    }

    private fun sendCommand(command: String) {
        val output = writer ?: return
        output.write(command)
        output.newLine()
        output.flush()
    }
}
