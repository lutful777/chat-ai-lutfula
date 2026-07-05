package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

class StockfishEngine(private val executablePath: String) : ChessEngine {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val ready = AtomicBoolean(false)
    private val crashed = AtomicBoolean(false)

    private var currentEvaluation = ""
    private var currentDepth = 0

    init {
        startProcess()
    }

    private fun startProcess(): Boolean {
        closeProcess()
        return try {
            process = ProcessBuilder(executablePath)
                .redirectErrorStream(true)
                .start()
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))

            sendCommand("uci")
            if (!waitForExactLine("uciok")) error("Stockfish did not complete UCI handshake")

            sendCommand("setoption name Threads value 1")
            sendCommand("setoption name Hash value 64")
            sendCommand("setoption name MultiPV value 1")
            sendCommand("setoption name Ponder value false")
            sendCommand("ucinewgame")
            sendCommand("isready")
            if (!waitForExactLine("readyok")) error("Stockfish did not become ready")

            ready.set(true)
            crashed.set(false)
            true
        } catch (_: Exception) {
            ready.set(false)
            crashed.set(true)
            closeProcess()
            false
        }
    }

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult =
        withContext(Dispatchers.IO) {
            if (crashed.get() || !ready.get() || process?.isAlive != true) {
                if (!startProcess()) {
                    return@withContext ChessAnalysisResult("", null, "Error", 0)
                }
            }

            val moveTime = if (depth >= 100) depth else 3000
            currentEvaluation = ""
            currentDepth = 0

            sendCommand("position fen $fen")
            sendCommand("go movetime $moveTime")

            var bestMove = ""
            var ponder: String? = null

            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    UciParser.parseInfo(line)?.let { info ->
                        if (info.evaluation.isNotEmpty()) currentEvaluation = info.evaluation
                        if (info.depth > 0) currentDepth = info.depth
                    }
                    UciParser.parseBestMove(line)?.let { result ->
                        bestMove = result.first
                        ponder = result.second
                        return@let
                    }
                    if (bestMove.isNotEmpty()) break
                }
            } catch (_: Exception) {
                crashed.set(true)
            }

            ChessAnalysisResult(
                bestMove = bestMove,
                ponderMove = ponder,
                evaluation = currentEvaluation.ifBlank { "0.00" },
                depth = currentDepth
            )
        }

    override fun stopAnalysis() {
        if (ready.get()) sendCommand("stop")
    }

    override fun close() {
        if (ready.get()) sendCommand("quit")
        closeProcess()
    }

    private fun waitForExactLine(expected: String): Boolean {
        while (true) {
            val line = reader?.readLine() ?: return false
            if (line.trim() == expected) return true
        }
    }

    private fun sendCommand(command: String) {
        try {
            writer?.write(command)
            writer?.newLine()
            writer?.flush()
        } catch (_: Exception) {
            crashed.set(true)
        }
    }

    private fun closeProcess() {
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        reader = null
        writer = null
        process = null
        ready.set(false)
    }
}
