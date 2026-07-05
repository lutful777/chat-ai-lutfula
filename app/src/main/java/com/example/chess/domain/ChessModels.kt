package com.example.chess.domain

enum class ChessColor { WHITE, BLACK }

enum class ChessPieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class ChessPiece(val type: ChessPieceType, val color: ChessColor) {
    fun toFenChar(): Char {
        val c = when (type) {
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
    val depth: Int
)

sealed interface ChessAssistantState {
    data object Idle : ChessAssistantState
    data object RequestingPermission : ChessAssistantState
    data object CapturingScreen : ChessAssistantState
    data object SearchingBoard : ChessAssistantState
    data object RecognizingPosition : ChessAssistantState
    data object Analyzing : ChessAssistantState

    data class Result(
        val fen: String,
        val bestMove: String,
        val evaluation: String,
        val depth: Int = 0,
        val boardConfidence: Float = 0f
    ) : ChessAssistantState

    data class Error(val message: String) : ChessAssistantState
}
