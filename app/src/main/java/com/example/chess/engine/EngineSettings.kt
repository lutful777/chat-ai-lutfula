package com.example.chess.engine

data class EngineSettings(
    val onlineEnabled: Boolean = true,
    val endpointUrl: String = ChessApiConfig.DEFAULT_ENDPOINT_URL,
    val localFallback: Boolean = false,
    val showEval: Boolean = true,
    val showArrow: Boolean = true
)
