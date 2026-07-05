package com.example.chess.capture

import com.example.chess.detection.BoardOrientation

object BottomSidePolicy {
    fun isBottomSideTurn(fen: String, orientation: BoardOrientation): Boolean {
        val activeColor = fen.trim().split(Regex("\\s+")).getOrNull(1) ?: return false
        val bottomColor = when (orientation) {
            BoardOrientation.WHITE_BOTTOM -> "w"
            BoardOrientation.BLACK_BOTTOM -> "b"
        }
        return activeColor == bottomColor
    }
}
