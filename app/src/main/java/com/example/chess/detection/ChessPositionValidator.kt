package com.example.chess.detection

class ChessPositionValidator {
    fun validate(board: Array<Array<com.example.chess.domain.ChessPiece?>>): Boolean {
        var whiteKings = 0
        var blackKings = 0
        for (row in board) {
            for (piece in row) {
                if (piece != null && piece.type == com.example.chess.domain.ChessPieceType.KING) {
                    if (piece.color == com.example.chess.domain.ChessColor.WHITE) whiteKings++
                    else blackKings++
                }
            }
        }
        return whiteKings == 1 && blackKings == 1
    }
}
