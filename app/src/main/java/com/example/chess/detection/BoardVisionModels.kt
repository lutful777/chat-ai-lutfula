package com.example.chess.detection

import android.graphics.PointF
import com.example.chess.domain.ChessColor

data class BoardGeometry(
    val left: Int,
    val top: Int,
    val size: Int,
    val confidence: Float
) {
    val squareSize: Float get() = size / 8f

    fun centerForUciSquare(file: Int, rank: Int, whiteAtBottom: Boolean): PointF {
        val screenColumn = if (whiteAtBottom) file else 7 - file
        val screenRow = if (whiteAtBottom) 7 - rank else rank
        return PointF(
            left + (screenColumn + 0.5f) * squareSize,
            top + (screenRow + 0.5f) * squareSize
        )
    }
}

data class SquareVisual(
    val occupied: Boolean,
    val pieceColor: ChessColor?,
    val confidence: Float,
    val foregroundLuma: Float
)

data class BoardVisualObservation(
    val geometry: BoardGeometry,
    val squares: Array<Array<SquareVisual>>,
    val whiteAtBottom: Boolean?,
    val confidence: Float
)
