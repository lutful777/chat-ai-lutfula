package com.example.chess.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val PLAYER_SIDE = stringPreferencesKey("player_side")
    }

    val enabled: Flow<Boolean> = context.chessDataStore.data.map { it[ENABLED] ?: true }
    val depth: Flow<Int> = context.chessDataStore.data.map {
        (it[DEPTH] ?: 3).coerceIn(1, 3)
    }
    val multiPv: Flow<Int> = context.chessDataStore.data.map { it[MULTI_PV] ?: 1 }
    val fps: Flow<Int> = context.chessDataStore.data.map {
        (it[FPS] ?: 1).coerceIn(1, 3)
    }
    val minConfidence: Flow<Float> = context.chessDataStore.data.map {
        (it[MIN_CONFIDENCE] ?: 0.15f).coerceIn(0.05f, 0.50f)
    }
    val showEval: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_EVAL] ?: true }
    val showArrow: Flow<Boolean> = context.chessDataStore.data.map { it[SHOW_ARROW] ?: true }
    val playerSide: Flow<String> = context.chessDataStore.data.map { it[PLAYER_SIDE] ?: "AUTO" }

    suspend fun updateEnabled(value: Boolean) {
        context.chessDataStore.edit { it[ENABLED] = value }
    }

    suspend fun updateDepth(value: Int) {
        context.chessDataStore.edit { it[DEPTH] = value.coerceIn(1, 3) }
    }

    suspend fun updateMultiPv(value: Int) {
        context.chessDataStore.edit { it[MULTI_PV] = value.coerceAtLeast(1) }
    }

    suspend fun updateFps(value: Int) {
        context.chessDataStore.edit { it[FPS] = value.coerceIn(1, 3) }
    }

    suspend fun updateMinConfidence(value: Float) {
        context.chessDataStore.edit {
            it[MIN_CONFIDENCE] = value.coerceIn(0.05f, 0.50f)
        }
    }

    suspend fun updateShowEval(value: Boolean) {
        context.chessDataStore.edit { it[SHOW_EVAL] = value }
    }

    suspend fun updateShowArrow(value: Boolean) {
        context.chessDataStore.edit { it[SHOW_ARROW] = value }
    }

    suspend fun updatePlayerSide(value: String) {
        context.chessDataStore.edit { it[PLAYER_SIDE] = value }
    }
}
