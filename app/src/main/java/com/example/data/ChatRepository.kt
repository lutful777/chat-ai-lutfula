package com.example.data

import android.util.Base64
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

class ChatRepository(private val chatDao: ChatDao) {
    val allSessions: Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: Long): Flow<List<MessageEntity>> =
        chatDao.getMessagesForSession(sessionId)

    suspend fun createNewSession(title: String): Long {
        val session = ChatSessionEntity(title = title)
        return chatDao.insertSession(session)
    }

    suspend fun insertMessage(message: MessageEntity) {
        val encodedBatch = message.articleImageUrl
            ?.takeIf { message.role == "assistant" && it.startsWith(NEWS_BATCH_PREFIX) }
            ?.removePrefix(NEWS_BATCH_PREFIX)

        if (encodedBatch != null && insertNewsBatch(message, encodedBatch)) {
            return
        }

        // Never pass an internal batch marker to Coil when decoding fails.
        val safeMessage = if (message.articleImageUrl?.startsWith(NEWS_BATCH_PREFIX) == true) {
            message.copy(articleImageUrl = null)
        } else {
            message
        }
        chatDao.insertMessage(safeMessage)
    }

    private suspend fun insertNewsBatch(template: MessageEntity, encodedBatch: String): Boolean {
        return try {
            val paddingLength = (4 - encodedBatch.length % 4) % 4
            val padded = encodedBatch + "=".repeat(paddingLength)
            val json = String(
                Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val articles = JSONArray(json)
            var inserted = 0

            for (index in 0 until minOf(MAX_NEWS_CARDS, articles.length())) {
                val article = articles.optJSONObject(index) ?: continue
                val title = article.optString("title").trim()
                val description = article.optString("description").trim()
                val source = article.optString("source").trim()
                val publishedAt = article.optString("publishedAt").trim()
                val imageUrl = article.optString("imageUrl")
                    .trim()
                    .takeIf { it.startsWith("https://") || it.startsWith("http://") }

                if (title.isBlank() || description.isBlank() || imageUrl == null) continue

                val content = buildString {
                    append(title)
                    append("\n\n")
                    append(description)
                    if (source.isNotBlank()) {
                        append("\n\nSumber: ").append(source)
                    }
                    if (publishedAt.isNotBlank()) {
                        append("\nTerbit: ").append(publishedAt)
                    }
                }

                chatDao.insertMessage(
                    template.copy(
                        id = 0,
                        content = content,
                        imageUri = null,
                        articleImageUrl = imageUrl,
                        timestamp = template.timestamp + index
                    )
                )
                inserted++
            }

            inserted > 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun clearHistoryForSession(sessionId: Long) =
        chatDao.clearHistoryForSession(sessionId)

    suspend fun deleteSession(sessionId: Long) {
        chatDao.clearHistoryForSession(sessionId)
        chatDao.deleteSession(sessionId)
    }

    private companion object {
        const val NEWS_BATCH_PREFIX = "news-batch:"
        const val MAX_NEWS_CARDS = 5
    }
}
