package com.example.chess.engine

import com.example.chess.capture.ProcessorState
import com.example.chess.capture.ScreenFrameProcessor
import com.example.chess.domain.ChessAnalysisResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
        val result = UciParser.parseBestMove("bestmove 0000")
        assertNull(result)
        val result2 = UciParser.parseBestMove("bestmove (none)")
        assertNull(result2)
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
    
    class FakeEngine : ChessEngine {
        var analyzedFen: String? = null
        var returnMove: String = "e2e4"
        override suspend fun analyze(fen: String, depth: Int): ChessAnalysisResult {
            analyzedFen = fen
            return ChessAnalysisResult(returnMove, null, "1.0", 10)
        }
        override fun stopAnalysis() {}
        override fun close() {}
    }

    @Test
    fun `processor locks WHITE_BOTTOM on start fen and analyzes only w`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine()
        val processor = ScreenFrameProcessor(engine, testDispatcher)
        
        processor.processFrame("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        advanceUntilIdle()
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", engine.analyzedFen)
        
        processor.processFrame("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        advanceUntilIdle()
        assertTrue(processor.resultFlow.value is ProcessorState.WaitingForOpponent)
    }
    
    @Test
    fun `processor locks BLACK_BOTTOM on start fen and analyzes only b`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine()
        engine.returnMove = "e7e5" // valid for black
        val processor = ScreenFrameProcessor(engine, testDispatcher)
        
        processor.processFrame("RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr b KQkq - 0 1")
        advanceUntilIdle()
        assertEquals("RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr b KQkq - 0 1", engine.analyzedFen)
        
        processor.processFrame("RNBQKBNR/PPPPPPPP/8/8/4p3/8/pppp1ppp/rnbqkbnr w KQkq e6 0 1")
        advanceUntilIdle()
        assertTrue(processor.resultFlow.value is ProcessorState.WaitingForOpponent)
    }
    
    @Test
    fun `processor cancels old analysis`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine()
        val processor = ScreenFrameProcessor(engine, testDispatcher)
        
        processor.processFrame("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        advanceTimeBy(100)
        processor.processFrame("rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 1")
        advanceUntilIdle()
        
        assertEquals("rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 1", engine.analyzedFen)
    }
    
    @Test
    fun `processor ignores invalid move from engine`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine()
        engine.returnMove = "0000"
        val processor = ScreenFrameProcessor(engine, testDispatcher)
        
        processor.processFrame("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        advanceUntilIdle()
        
        assertTrue(processor.resultFlow.value is ProcessorState.Error)
    }
    
    @Test
    fun `processor ignores move for opponent piece`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeEngine()
        engine.returnMove = "e7e5" // Black move while White is locked
        val processor = ScreenFrameProcessor(engine, testDispatcher)
        
        processor.processFrame("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        advanceUntilIdle()
        
        assertTrue(processor.resultFlow.value is ProcessorState.Error)
    }
}
