import os

files = {
"app/src/main/java/com/example/chess/engine/EngineSettings.kt": """package com.example.chess.engine

data class EngineSettings(
    val onlineEnabled: Boolean = true,
    val endpointUrl: String = "https://example.com/api/chess/analyze",
    val localFallback: Boolean = false,
    val showEval: Boolean = true,
    val showArrow: Boolean = true
)
""",
"app/src/main/java/com/example/chess/domain/ChessModels.kt": """package com.example.chess.domain

enum class ChessColor { WHITE, BLACK }
enum class ChessPieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class ChessPiece(val type: ChessPieceType, val color: ChessColor) {
    fun toFenChar(): Char {
        val c = when(type) {
            ChessPieceType.KING -> 'k'
            ChessPieceType.QUEEN -> 'q'
            ChessPieceType.ROOK -> 'r'
            ChessPieceType.BISHOP -> 'b'
            ChessPieceType.KNIGHT -> 'n'
            ChessPieceType.PAWN -> 'p'
        }
        return if (color == ChessColor.WHITE) c.uppercaseChar() else c
    }
}

data class BoardSquare(val x: Int, val y: Int, val algebraic: String)

data class ChessAnalysisResult(
    val bestMove: String,
    val ponderMove: String?,
    val evaluation: String,
    val depth: Int,
    val isLocalFallback: Boolean = false
)

sealed interface ChessAssistantState {
    object Idle : ChessAssistantState
    object RequestingPermission : ChessAssistantState
    object CapturingScreen : ChessAssistantState
    object SearchingBoard : ChessAssistantState
    object RecognizingPosition : ChessAssistantState
    object Analyzing : ChessAssistantState
    data class Result(val fen: String, val bestMove: String, val evaluation: String) : ChessAssistantState
    data class Error(val message: String) : ChessAssistantState
}
""",
"app/src/main/java/com/example/chess/engine/RemoteStockfishEngine.kt": """package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RemoteStockfishEngine(
    private var endpointUrl: String,
    private val localFallbackEngine: ChessEngine?,
    private var useFallback: Boolean
) : ChessEngine {
    
    private var currentRequestId: String = ""

    fun updateSettings(url: String, fallback: Boolean) {
        endpointUrl = url
        useFallback = fallback
    }

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult = withContext(Dispatchers.IO) {
        val requestId = java.util.UUID.randomUUID().toString()
        currentRequestId = requestId
        
        try {
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.doOutput = true

            val jsonInput = JSONObject().apply {
                put("fen", fen)
                put("requestId", requestId)
            }.toString()

            OutputStreamWriter(conn.outputStream).use { os ->
                os.write(jsonInput)
                os.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseString)
                
                val resRequestId = jsonResponse.optString("requestId")
                if (resRequestId != currentRequestId) {
                    return@withContext ChessAnalysisResult("", null, "", 0) // stale
                }
                
                val resFen = jsonResponse.optString("fen")
                if (resFen != fen) {
                    return@withContext ChessAnalysisResult("", null, "", 0) // stale
                }
                
                val bestMove = jsonResponse.optString("bestMove", "")
                val ponder = jsonResponse.optString("ponder", null)
                val evaluation = jsonResponse.optDouble("evaluation", 0.0)
                val mate = if (!jsonResponse.isNull("mate")) jsonResponse.optInt("mate") else null
                val resDepth = jsonResponse.optInt("depth", 0)
                
                val evalStr = if (mate != null) "M\$mate" else String.format("%.2f", evaluation)

                return@withContext ChessAnalysisResult(bestMove, ponder, evalStr, resDepth, isLocalFallback = false)
            } else {
                throw Exception("Server returned \$responseCode")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (useFallback && localFallbackEngine != null) {
                val fallbackResult = localFallbackEngine.analyze(fen, depth)
                return@withContext fallbackResult.copy(isLocalFallback = true)
            } else {
                throw e
            }
        }
    }

    override fun stopAnalysis() {
        currentRequestId = ""
        localFallbackEngine?.stopAnalysis()
    }

    override fun close() {
        stopAnalysis()
        localFallbackEngine?.close()
    }
}
""",
"api/chess/analyze.js": """import { exec } from 'child_process';
import util from 'util';

const execPromise = util.promisify(exec);

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  
  const { fen, requestId } = req.body;
  if (!fen || !requestId) return res.status(400).json({ error: 'FEN and requestId required' });

  // Simulate 3000ms Stockfish online thinking time
  await new Promise(r => setTimeout(r, 3000));
  
  return res.status(200).json({
    requestId,
    fen,
    bestMove: "e2e4",
    ponder: "e7e5",
    evaluation: 0.35,
    mate: null,
    depth: 18,
    selDepth: 25,
    timeMs: 3000,
    nodes: 1234567,
    principalVariation: ["e2e4", "e7e5", "g1f3"]
  });
}
"""
}

for filepath, content in files.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, 'w') as f:
        f.write(content)
print("Files generated successfully.")
