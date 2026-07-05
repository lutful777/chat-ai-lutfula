package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

class RemoteStockfishEngine(
    private var endpointUrl: String,
    private val localFallbackEngine: ChessEngine?,
    private var useFallback: Boolean
) : ChessEngine {

    @Volatile
    private var currentRequestId: String = ""

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun updateSettings(url: String, fallback: Boolean) {
        endpointUrl = url.trim()
        useFallback = fallback
    }

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        var connection: HttpURLConnection? = null

        try {
            coroutineContext.ensureActive()
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 30_000
                doOutput = true
                useCaches = false
            }
            activeConnection = connection

            val requestBody = JSONObject().apply {
                put("fen", fen)
                put("requestId", requestId)
            }.toString()

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            coroutineContext.ensureActive()
            val responseCode = connection.responseCode
            val responseText = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(
                    "Server returned $responseCode${if (responseText.isNotBlank()) ": ${responseText.take(300)}" else ""}"
                )
            }

            coroutineContext.ensureActive()
            if (requestId != currentRequestId) throw CancellationException("Stale Stockfish response")

            val json = JSONObject(responseText)
            if (json.optString("requestId") != requestId || json.optString("fen") != fen) {
                throw CancellationException("Stockfish response does not match the current position")
            }

            val bestMove = json.optString("bestMove", "").lowercase(Locale.US)
            val ponder = if (json.isNull("ponder")) null else json.optString("ponder").takeIf { it.isNotBlank() }
            val mate = if (json.isNull("mate")) null else json.optInt("mate")
            val evaluation = if (json.isNull("evaluation")) null else json.optDouble("evaluation")
            val responseDepth = json.optInt("depth", 0)
            val evaluationText = when {
                mate != null -> "M$mate"
                evaluation != null && evaluation.isFinite() -> String.format(Locale.US, "%.2f", evaluation)
                else -> "0.00"
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
            if (useFallback && localFallbackEngine != null) {
                localFallbackEngine.analyze(fen, depth).copy(isLocalFallback = true)
            } else {
                throw error
            }
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection?.disconnect()
        }
    }

    override fun stopAnalysis() {
        currentRequestId = ""
        activeConnection?.disconnect()
        activeConnection = null
        localFallbackEngine?.stopAnalysis()
    }

    override fun close() {
        stopAnalysis()
        localFallbackEngine?.close()
    }
}
