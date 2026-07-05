package com.example.chess.domain

enum class ChessColor { WHITE, BLACK }
enum class ChessPieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class ChessPiece(val type: ChessPieceType, val color: ChessColor) {
    fun toFenChar(): Char {
        val c = when(type) {
            ChessPieceType.KING -> 'k'
            ChessPieceType.QUEEN -> 'q'
            ChessPieceType.ROOK -> 'r'
            ChessPieceType.BISHOP -> 'b'
            ChessPieceType.KNIGHT -> 'n'
            ChessPieceType.PAWN -> 'p'
        }
        return if (color == ChessColor.WHITE) c.uppercaseChar() else c
    }
}

data class BoardSquare(val x: Int, val y: Int, val algebraic: String)

data class ChessAnalysisResult(
    val bestMove: String,
    val ponderMove: String?,
    val evaluation: String,
    val depth: Int,
    val isLocalFallback: Boolean = false
)

sealed interface ChessAssistantState {
    object Idle : ChessAssistantState
    object RequestingPermission : ChessAssistantState
    object CapturingScreen : ChessAssistantState
    object SearchingBoard : ChessAssistantState
    object RecognizingPosition : ChessAssistantState
    object Analyzing : ChessAssistantState
    data class Result(val fen: String, val bestMove: String, val evaluation: String) : ChessAssistantState
    data class Error(val message: String) : ChessAssistantState
}
