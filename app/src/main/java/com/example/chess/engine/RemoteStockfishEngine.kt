package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class RemoteStockfishEngine(
    private var endpointUrl: String,
    private val localFallbackEngine: ChessEngine?,
    private var useFallback: Boolean
) : ChessEngine {

    @Volatile
    private var currentRequestId: String = ""

    @Volatile
    private var currentConnection: HttpURLConnection? = null

    fun updateSettings(url: String, fallback: Boolean) {
        endpointUrl = url.trim().ifBlank { ChessApiConfig.DEFAULT_ENDPOINT_URL }
        useFallback = fallback
    }

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult = withContext(Dispatchers.IO) {
        val requestId = java.util.UUID.randomUUID().toString()
        currentRequestId = requestId
        val moveTimeMs = if (depth > 100) depth.coerceIn(100, 5000) else ChessApiConfig.DEFAULT_MOVE_TIME_MS
        var connection: HttpURLConnection? = null

        try {
            val url = URL(endpointUrl.trim().ifBlank { ChessApiConfig.DEFAULT_ENDPOINT_URL })
            connection = url.openConnection() as HttpURLConnection
            currentConnection?.disconnect()
            currentConnection = connection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = (moveTimeMs + 12_000).coerceAtLeast(15_000)
            connection.doOutput = true

            val jsonInput = JSONObject().apply {
                put("fen", fen)
                put("requestId", requestId)
                if (depth in 1..100) put("depth", depth)
                else put("movetimeMs", moveTimeMs)
            }.toString()

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonInput)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(
                    "Server returned $responseCode${if (errorBody.isNotBlank()) ": $errorBody" else ""}"
                )
            }

            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(responseString)

            if (requestId != currentRequestId) {
                throw CancellationException("Stockfish response is stale")
            }

            val responseRequestId = jsonResponse.optString("requestId")
            if (responseRequestId.isNotBlank() && responseRequestId != requestId) {
                throw CancellationException("Stockfish request ID does not match")
            }

            val responseFen = jsonResponse.optString("fen")
            if (responseFen != fen) {
                throw CancellationException("Stockfish FEN does not match")
            }

            val bestMove = jsonResponse.optString("bestMove").trim()
            if (bestMove.isBlank()) {
                throw IllegalStateException("Stockfish returned no best move")
            }

            val ponder = jsonResponse.optString("ponder")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val evaluation = jsonResponse.optDouble("evaluation", 0.0)
            val mate = if (!jsonResponse.isNull("mate")) jsonResponse.optInt("mate") else null
            val responseDepth = jsonResponse.optInt("depth", 0)
            val evaluationText = if (mate != null) {
                "M$mate"
            } else {
                String.format(Locale.US, "%.2f", evaluation)
            }

            ChessAnalysisResult(
                bestMove = bestMove,
                ponderMove = ponder,
                evaluation = evaluationText,
                depth = responseDepth,
                isLocalFallback = false
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
            if (useFallback && localFallbackEngine != null) {
                localFallbackEngine.analyze(fen, depth).copy(isLocalFallback = true)
            } else {
                throw error
            }
        } finally {
            if (currentConnection === connection) currentConnection = null
            connection?.disconnect()
        }
    }

    override fun stopAnalysis() {
        currentRequestId = ""
        currentConnection?.disconnect()
        currentConnection = null
        localFallbackEngine?.stopAnalysis()
    }

    override fun close() {
        stopAnalysis()
        localFallbackEngine?.close()
    }
}
