package com.example.chess.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChessEngineTest {
    @Test
    fun `parse bestmove with ponder`() {
        val result = UciParser.parseBestMove("bestmove e2e4 ponder e7e5")
        assertNotNull(result)
        assertEquals("e2e4", result?.first)
        assertEquals("e7e5", result?.second)
    }

    @Test
    fun `parse bestmove only`() {
        val result = UciParser.parseBestMove("bestmove e2e4")
        assertNotNull(result)
        assertEquals("e2e4", result?.first)
        assertNull(result?.second)
    }

    @Test
    fun `parse bestmove invalid`() {
        assertNull(UciParser.parseBestMove("bestmove 0000"))
        assertNull(UciParser.parseBestMove("bestmove (none)"))
    }

    @Test
    fun `parse info cp`() {
        val info = UciParser.parseInfo("info depth 12 score cp 42 time 123 pv e2e4 e7e5")
        assertNotNull(info)
        assertEquals(12, info?.depth)
        assertEquals("0.42", info?.evaluation)
        assertEquals(123L, info?.timeMs)
        assertEquals("e2e4 e7e5", info?.principalVariation)
    }

    @Test
    fun `parse info mate`() {
        val info = UciParser.parseInfo("info depth 12 score mate 3 pv e2e4")
        assertNotNull(info)
        assertEquals("M3", info?.evaluation)
    }
}
