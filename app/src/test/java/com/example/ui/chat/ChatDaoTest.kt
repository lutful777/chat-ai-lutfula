package com.example.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.data.MessageEntity

class ChatDaoTest {
    
    @Test
    fun testMessageEntityPreservesArticleImageUrl() {
        val entity = MessageEntity(
            id = 1,
            sessionId = 100L,
            role = "assistant",
            content = "News article summary",
            articleImageUrl = "https://example.com/image.jpg"
        )
        
        assertEquals("https://example.com/image.jpg", entity.articleImageUrl)
        assertEquals("News article summary", entity.content)
    }
}
