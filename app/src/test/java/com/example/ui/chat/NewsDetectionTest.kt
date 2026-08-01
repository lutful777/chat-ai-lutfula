package com.example.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewsDetectionTest {
    
    @Test
    fun testExplicitNewsCommand() {
        val result = SearchDetector.detectSearchModeAndQuery("#berita Cristiano Ronaldo menangis")
        assertEquals("Cristiano Ronaldo menangis", result.first)
        assertEquals("berita", result.second)
    }

    @Test
    fun testImplicitNewsQuestion() {
        val result = SearchDetector.detectSearchModeAndQuery("apakah ada berita terbaru tentang Cristiano Ronaldo?")
        assertEquals("apakah ada berita terbaru tentang Cristiano Ronaldo?", result.first)
        assertEquals("berita", result.second)
    }

    @Test
    fun testImplicitNewsWithQuestionMark() {
        val result = SearchDetector.detectSearchModeAndQuery("Cristiano Ronaldo menangis?")
        assertEquals("Cristiano Ronaldo menangis?", result.first)
        assertEquals("berita", result.second)
    }

    @Test
    fun testNonNewsQuestion() {
        val result = SearchDetector.detectSearchModeAndQuery("siapa Cristiano Ronaldo?")
        assertNull(result.first)
        assertEquals("cari", result.second)
    }
}
