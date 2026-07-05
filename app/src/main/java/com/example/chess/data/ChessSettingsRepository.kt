package com.example.chess.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chessDataStore by preferencesDataStore(name = "chess_settings")

class ChessSettingsRepository(private val context: Context) {
    companion object {
        const val DEFAULT_STOCKFISH_ENDPOINT =
            "https://chat-ai-lutfula-git-fix-stockfish-verc-a772c9-lutful-s-projects.vercel.app/api/chess/analyze"

        val ENABLED = booleanPreferencesKey("enabled")
        val ONLINE_ENABLED = booleanPreferencesKey("online_enabled")
        val ENDPOINT_URL = stringPreferencesKey("endpoint_url")
        val LOCAL_FALLBACK = booleanPreferencesKey("local_fallback")
        val FPS = intPreferencesKey("fps")
        val SHOW_EVAL = booleanPreferencesKey("show_eval")
        val SHOW_ARROW = booleanPreferencesKey("show_arrow")
    }

    val enabled: Flow<Boolean> = context.chessDataStore.data.map { it[ENABLED] ?: true }
    val onlineEnabled: Flow<Boolean> = context.chessDataStore.data.map { it[ONLINE_ENABLED] ?: true }
    val endpointUrl: Flow<String> = context.chessDataStore.data.map {
        it[ENDPOINT_URL]
            ?.takeIf { value -> value.startsWith("https://") }
            ?: DEFAULT_STOCKFISH_ENDPOINT
    }
    val localFallback: Flow<Boolean> = context.chessDataStore.data.map { it[LOCAL_FALLBACK] ?: false }
    val fps: Flow<Int> = context.chessDataStore.data.map { it[FPS] ?: 1 }
    val showEval: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_EVAL] ?: true }
    val showArrow: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_ARROW] ?: true }

    suspend fun updateEnabled(value: Boolean) { context.chessDataStore.edit { it[ENABLED] = value } }
    suspend fun updateOnlineEnabled(value: Boolean) { context.chessDataStore.edit { it[ONLINE_ENABLED] = value } }
    suspend fun updateEndpointUrl(value: String) { context.chessDataStore.edit { it[ENDPOINT_URL] = value.trim() } }
    suspend fun updateLocalFallback(value: Boolean) { context.chessDataStore.edit { it[LOCAL_FALLBACK] = value } }
    suspend fun updateFps(value: Int) { context.chessDataStore.edit { it[FPS] = value.coerceIn(1, 3) } }
    suspend fun updateShowEval(value: Boolean) { context.chessDataStore.edit { it[SHOW_EVAL] = value } }
    suspend fun updateShowArrow(value: Boolean) { context.chessDataStore.edit { it[SHOW_ARROW] = value } }
}
