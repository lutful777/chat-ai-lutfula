package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.delay

interface ChessEngine {
    suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult
    fun stopAnalysis()
    fun close()
}

// Dummy Engine for MVP
class SimpleChessEngine : ChessEngine {
    private var isAnalyzing = false

    override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult {
        isAnalyzing = true
        delay(2000) // Simulate analysis time
        isAnalyzing = false
        // Just return a dummy move for testing
        return ChessAnalysisResult("e2e4", "e7e5", "+1.2", depth)
    }

    override fun stopAnalysis() {
        isAnalyzing = false
    }

    override fun close() {
        stopAnalysis()
    }
}
