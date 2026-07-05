package com.example.chess.detection

import com.example.chess.domain.ChessColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessPositionTrackerTest {
    @Test
    fun tracksOpeningMovesAndProducesFen() {
        val tracker = ChessPositionTracker()
        val board = startingBoard()

        assertTrue(tracker.update(observation(board)) is PositionTrackingResult.Waiting)
        val initial = tracker.update(observation(board)) as PositionTrackingResult.Ready
        assertEquals(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            initial.fen
        )

        board[1][4] = '.'
        board[3][4] = 'P'
        assertTrue(tracker.update(observation(board)) is PositionTrackingResult.Waiting)
        val afterE4 = tracker.update(observation(board)) as PositionTrackingResult.Ready
        assertEquals(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            afterE4.fen
        )

        board[6][4] = '.'
        board[4][4] = 'p'
        assertTrue(tracker.update(observation(board)) is PositionTrackingResult.Waiting)
        val afterE5 = tracker.update(observation(board)) as PositionTrackingResult.Ready
        assertEquals(
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
            afterE5.fen
        )
    }

    private fun observation(board: Array<CharArray>): BoardVisualObservation {
        val squares = Array(8) { screenRow ->
            Array(8) { screenColumn ->
                val rank = 7 - screenRow
                val piece = board[rank][screenColumn]
                SquareVisual(
                    occupied = piece != '.',
                    pieceColor = when {
                        piece == '.' -> null
                        piece.isUpperCase() -> ChessColor.WHITE
                        else -> ChessColor.BLACK
                    },
                    confidence = 1f,
                    foregroundLuma = if (piece.isUpperCase()) 220f else 40f
                )
            }
        }
        return BoardVisualObservation(
            geometry = BoardGeometry(0, 0, 800, 1f),
            squares = squares,
            whiteAtBottom = true,
            confidence = 1f
        )
    }

    private fun startingBoard(): Array<CharArray> = arrayOf(
        charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        CharArray(8) { '.' },
        CharArray(8) { '.' },
        CharArray(8) { '.' },
        CharArray(8) { '.' },
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
    )
}
