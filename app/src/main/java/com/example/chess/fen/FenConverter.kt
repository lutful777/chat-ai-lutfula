package com.example.chess.fen

import com.example.chess.domain.ChessPiece
import com.example.chess.domain.ChessColor

object FenConverter {
    fun toFen(board: Array<Array<ChessPiece?>>, isWhiteTurn: Boolean = true): String {
        val sb = java.lang.StringBuilder()
        for (y in 0 until 8) {
            var emptyCount = 0
            for (x in 0 until 8) {
                val piece = board[y][x]
                if (piece == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(piece.toFenChar())
                }
            }
            if (emptyCount > 0) {
                sb.append(emptyCount)
            }
            if (y < 7) sb.append('/')
        }
        val turn = if (isWhiteTurn) "w" else "b"
        // safe default values for MVP
        sb.append(" $turn - - 0 1") 
        return sb.toString()
    }
}
