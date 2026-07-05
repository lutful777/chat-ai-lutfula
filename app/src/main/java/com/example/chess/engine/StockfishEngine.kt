package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

class StockfishEngine(private val executablePath: String) : ChessEngine {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isReady = AtomicBoolean(false)
    private var crashed = AtomicBoolean(false)
    
    private var currentEval = ""
    private var currentDepth = 0

    init {
        startProcess()
    }

    private fun startProcess(): Boolean {
        try {
            process = ProcessBuilder(executablePath).start()
            reader = BufferedReader(InputStreamReader(process?.inputStream))
            writer = BufferedWriter(OutputStreamWriter(process?.outputStream))
            
            sendCommand("uci")
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                if (line == "uciok") break
            }
            
            sendCommand("isready")
            while (reader?.readLine().also { line = it } != null) {
                if (line == "readyok") break
            }
            
            sendCommand("setoption name Threads value 1")
            sendCommand("setoption name Hash value 64")
            sendCommand("setoption name MultiPV value 1")
            sendCommand("setoption name Ponder value false")
            sendCommand("ucinewgame")
            
            isReady.set(true)
            crashed.set(false)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            crashed.set(true)
            return false
        }
    }
    
    fun applySettings(settings: EngineSettings) {
        sendCommand("setoption name Threads value \${settings.threads}")
        sendCommand("setoption name Hash value \${settings.hashMb}")
        sendCommand("setoption name MultiPV value \${settings.multiPv}")
    }

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult = withContext(Dispatchers.IO) {
        // We use movetime, not depth, so we'll expect movetime in depth parameter here or via settings.
        // For compatibility with interface, we will treat 'depth' as movetime if it's large (e.g. > 100), 
        // else we just use a default movetime or we pass movetime instead.
        val moveTime = if (depth > 100) depth else 800
        
        if (crashed.get() || process == null) {
            val restarted = startProcess()
            if (!restarted) return@withContext ChessAnalysisResult("", null, "Error", 0)
        }
        
        sendCommand("position fen \$fen")
        sendCommand("go movetime \$moveTime")
        
        var bestMove = ""
        var ponder: String? = null
        currentEval = ""
        currentDepth = 0
        var pv = ""
        
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                val currentLine = line ?: ""
                
                val info = UciParser.parseInfo(currentLine)
                if (info != null) {
                    if (info.evaluation.isNotEmpty()) currentEval = info.evaluation
                    if (info.depth > 0) currentDepth = info.depth
                    if (info.principalVariation.isNotEmpty()) pv = info.principalVariation
                }
                
                val moveResult = UciParser.parseBestMove(currentLine)
                if (moveResult != null) {
                    bestMove = moveResult.first
                    ponder = moveResult.second
                    break
                }
            }
        } catch (e: Exception) {
            crashed.set(true)
        }
        
        ChessAnalysisResult(bestMove, ponder, currentEval, currentDepth)
    }

    override fun stopAnalysis() {
        sendCommand("stop")
    }

    override fun close() {
        sendCommand("quit")
        try {
            process?.waitFor()
        } catch (e: Exception) {}
        
        try {
            reader?.close()
            writer?.close()
        } catch (e: Exception) {}
        
        process = null
        isReady.set(false)
        scope.cancel()
    }

    private fun sendCommand(command: String) {
        try {
            writer?.write(command + "\n")
            writer?.flush()
        } catch (e: Exception) {
            crashed.set(true)
        }
    }
}
