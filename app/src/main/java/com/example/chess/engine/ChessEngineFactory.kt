package com.example.chess.engine

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
