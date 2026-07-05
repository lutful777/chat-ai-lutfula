package com.example.chess.engine

import com.example.chess.domain.ChessAnalysisResult

interface ChessEngine {
    suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult
    fun stopAnalysis()
    fun close()
}
