package com.example.chess.engine

data class EngineSettings(
    val onlineEnabled: Boolean = true,
    val endpointUrl: String = "https://example.com/api/chess/analyze",
    val localFallback: Boolean = false,
    val showEval: Boolean = true,
    val showArrow: Boolean = true
)
