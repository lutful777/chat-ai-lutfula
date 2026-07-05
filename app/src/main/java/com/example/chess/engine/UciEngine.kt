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
            writer?.write(command + "\n")
            writer?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
