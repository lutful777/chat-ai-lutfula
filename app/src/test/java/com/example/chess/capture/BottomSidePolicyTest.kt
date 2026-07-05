package com.example.chess.capture

import com.example.chess.detection.BoardOrientation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomSidePolicyTest {
    @Test
    fun whiteBottomOnlyReceivesGuidanceOnWhiteTurn() {
        assertTrue(
            BottomSidePolicy.isBottomSideTurn(
                "8/8/8/8/8/8/8/8 w - - 0 1",
                BoardOrientation.WHITE_BOTTOM
            )
        )
        assertFalse(
            BottomSidePolicy.isBottomSideTurn(
                "8/8/8/8/8/8/8/8 b - - 0 1",
                BoardOrientation.WHITE_BOTTOM
            )
        )
    }

    @Test
    fun blackBottomOnlyReceivesGuidanceOnBlackTurn() {
        assertTrue(
            BottomSidePolicy.isBottomSideTurn(
                "8/8/8/8/8/8/8/8 b - - 0 1",
                BoardOrientation.BLACK_BOTTOM
            )
        )
        assertFalse(
            BottomSidePolicy.isBottomSideTurn(
                "8/8/8/8/8/8/8/8 w - - 0 1",
                BoardOrientation.BLACK_BOTTOM
            )
        )
    }

    @Test
    fun invalidFenNeverReceivesGuidance() {
        assertFalse(
            BottomSidePolicy.isBottomSideTurn(
                "invalid",
                BoardOrientation.WHITE_BOTTOM
            )
        )
    }
}
