package com.example.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickableMessageTextTest {
    @Test
    fun parsesRawLink() {
        val segments = parseMessageLinks("Buka https://openai.com sekarang.")
        val link = segments.first { it.url != null }
        assertEquals("https://openai.com", link.url)
    }

    @Test
    fun parsesMarkdownLink() {
        val segments = parseMessageLinks("[Buka situs](https://openai.com)")
        val link = segments.first { it.url != null }
        assertEquals("Buka situs", link.text)
    }

    @Test
    fun normalizesWwwLink() {
        assertEquals("https://www.openai.com", normalizeSafeWebUrl("www.openai.com"))
    }
}
