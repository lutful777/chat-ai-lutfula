package com.example.chess.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chessDataStore by preferencesDataStore(name = "chess_settings")

class ChessSettingsRepository(private val context: Context) {
    companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val DEPTH = intPreferencesKey("depth")
        val MULTI_PV = intPreferencesKey("multi_pv")
        val FPS = intPreferencesKey("fps")
        val MIN_CONFIDENCE = floatPreferencesKey("min_confidence")
        val SHOW_EVAL = booleanPreferencesKey("show_eval")
        val SHOW_ARROW = booleanPreferencesKey("show_arrow")
        val PLAYER_SIDE = stringPreferencesKey("player_side") // AUTO, WHITE, BLACK
    }

    val enabled: Flow<Boolean> = context.chessDataStore.data.map { it[ENABLED] ?: true }
    val depth: Flow<Int> = context.chessDataStore.data.map { it[DEPTH] ?: 10 }
    val multiPv: Flow<Int> = context.chessDataStore.data.map { it[MULTI_PV] ?: 1 }
    val fps: Flow<Int> = context.chessDataStore.data.map { it[FPS] ?: 1 }
    val minConfidence: Flow<Float> = context.chessDataStore.data.map { it[MIN_CONFIDENCE] ?: 0.7f }
    val showEval: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_EVAL] ?: true }
    val showArrow: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_ARROW] ?: true }
    val playerSide: Flow<String> = context.chessDataStore.data.map { it[PLAYER_SIDE] ?: "AUTO" }

    suspend fun updateEnabled(value: Boolean) { context.chessDataStore.edit { it[ENABLED] = value } }
    suspend fun updateDepth(value: Int) { context.chessDataStore.edit { it[DEPTH] = value } }
    suspend fun updateMultiPv(value: Int) { context.chessDataStore.edit { it[MULTI_PV] = value } }
    suspend fun updateFps(value: Int) { context.chessDataStore.edit { it[FPS] = value } }
    suspend fun updateMinConfidence(value: Float) { context.chessDataStore.edit { it[MIN_CONFIDENCE] = value } }
    suspend fun updateShowEval(value: Boolean) { context.chessDataStore.edit { it[SHOW_EVAL] = value } }
    suspend fun updateShowArrow(value: Boolean) { context.chessDataStore.edit { it[SHOW_ARROW] = value } }
    suspend fun updatePlayerSide(value: String) { context.chessDataStore.edit { it[PLAYER_SIDE] = value } }
}
