package com.example.chess.capture

import com.example.chess.detection.BoardOrientation

/**
 * Treats the pieces shown at the bottom of the board as the user's pieces.
 * The color is inferred only from the locked board orientation; users do not
 * need to choose white or black manually.
 */
object BottomSidePolicy {
    fun isBottomSideTurn(fen: String, orientation: BoardOrientation): Boolean {
        val activeColor = fen.trim().split(Regex("\\s+")).getOrNull(1) ?: return false
        val bottomColor = when (orientation) {
            BoardOrientation.WHITE_BOTTOM -> "w"
            BoardOrientation.BLACK_BOTTOM -> "b"
        }
        return activeColor == bottomColor
    }

    fun bottomSideName(orientation: BoardOrientation): String = when (orientation) {
        BoardOrientation.WHITE_BOTTOM -> "sisi bawah"
        BoardOrientation.BLACK_BOTTOM -> "sisi bawah"
    }
}
