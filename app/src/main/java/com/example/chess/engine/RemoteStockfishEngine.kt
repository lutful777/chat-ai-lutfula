package com.example.chess.engine

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
