package com.example.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchDetectorTest {

    @Test
    fun explicitNewsCommandUsesNewsMode() {
        val (query, mode) = SearchDetector.detectSearchModeAndQuery("#berita Cristiano Ronaldo menangis?")

        assertEquals("Cristiano Ronaldo menangis?", query)
        assertEquals("berita", mode)
    }

    @Test
    fun namedCurrentEventIsDetectedAsNews() {
        val (query, mode) = SearchDetector.detectSearchModeAndQuery("Cristiano Ronaldo menangis?")

        assertEquals("Cristiano Ronaldo menangis?", query)
        assertEquals("berita", mode)
    }

    @Test
    fun genericQuestionIsNotDetectedAsNews() {
        val (query, mode) = SearchDetector.detectSearchModeAndQuery("Kenapa manusia menangis?")

        assertNull(query)
        assertEquals("cari", mode)
    }

    @Test
    fun latestNewsPhraseIsDetectedAsNews() {
        val (query, mode) = SearchDetector.detectSearchModeAndQuery("Apa kabar terbaru tentang Portugal?")

        assertEquals("Apa kabar terbaru tentang Portugal?", query)
        assertEquals("berita", mode)
    }

    @Test
    fun browserCommandRemainsGeneralSearch() {
        val (query, mode) = SearchDetector.detectSearchModeAndQuery("#browser sejarah Portugal")

        assertEquals("sejarah Portugal", query)
        assertEquals("cari", mode)
    }
}
