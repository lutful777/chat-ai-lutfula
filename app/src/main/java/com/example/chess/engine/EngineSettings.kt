package com.example.chess.engine

import com.example.chess.data.ChessSettingsRepository

data class EngineSettings(
    val onlineEnabled: Boolean = true,
    val endpointUrl: String = ChessSettingsRepository.DEFAULT_STOCKFISH_ENDPOINT,
    val localFallback: Boolean = false,
    val showEval: Boolean = true,
    val showArrow: Boolean = true
)
