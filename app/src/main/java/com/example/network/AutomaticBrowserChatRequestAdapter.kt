package com.example.network

import com.example.ui.chat.AutomaticBrowserRepository
import com.example.ui.chat.resolveBrowserSearchMode
import com.example.ui.chat.resolveBrowserUrls
import com.example.ui.chat.shouldAutomaticallySearchWeb
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AutomaticBrowserChatRequestAdapter : JsonAdapter<ChatRequest>() {
    private val browserRepository = AutomaticBrowserRepository(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    private val delegate: JsonAdapter<SerializableChatRequest> = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(SerializableChatRequest::class.java)

    override fun toJson(writer: JsonWriter, value: ChatRequest?) {
        if (value == null) {
            writer.nullValue()
            return
        }

        val enrichedMessages = try {
            enrichMessages(value.messages)
        } catch (_: Exception) {
            value.messages
        }

        delegate.toJson(
            writer,
            SerializableChatRequest(
                model = value.model,
                messages = enrichedMessages,
                stream = value.stream,
                reasoning = value.reasoning
            )
        )
    }

    override fun fromJson(reader: JsonReader): ChatRequest? {
        val decoded = delegate.fromJson(reader) ?: return null
        return ChatRequest(
            model = decoded.model,
            messages = decoded.messages,
            stream = decoded.stream,
            reasoning = decoded.reasoning
        )
    }

    private fun enrichMessages(
        messages: List<ChatRequestMessage>
    ): List<ChatRequestMessage> {
        if (messages.isEmpty()) return messages

        val systemText = messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .flatMap { it.content }
            .mapNotNull { it.text }
            .joinToString("\n")

        if (containsExistingBrowserContext(systemText)) return messages

        val latestUserIndex = messages.indexOfLast {
            it.role.equals("user", ignoreCase = true)
        }
        if (latestUserIndex < 0) return messages

        val latestUserText = messages[latestUserIndex].content
            .mapNotNull { it.text }
            .joinToString("\n")
        val userQuestion = extractUserQuestion(latestUserText)
        if (userQuestion.isBlank()) return messages

        val previousTexts = messages
            .take(latestUserIndex)
            .flatMap { message -> message.content.mapNotNull { it.text } }

        val referencedUrls = resolveBrowserUrls(
            currentMessage = userQuestion,
            previousMessages = previousTexts
        )
        val hasSpecializedContext = containsSpecializedRealtimeContext(systemText)

        val browserResult = when {
            referencedUrls.isNotEmpty() -> {
                browserRepository.readUrl(referencedUrls.first())
            }
            !hasSpecializedContext && shouldAutomaticallySearchWeb(userQuestion) -> {
                browserRepository.search(
                    query = userQuestion,
                    mode = resolveBrowserSearchMode(userQuestion)
                )
            }
            else -> null
        } ?: return messages

        val browserContext = buildString {
            append(browserResult.promptContext)
            append(browserResult.sourceLinks)
        }

        val updated = messages.toMutableList()
        val systemIndex = updated.indexOfFirst {
            it.role.equals("system", ignoreCase = true)
        }
        val browserPart = VisionContent(type = "text", text = browserContext)

        if (systemIndex >= 0) {
            val systemMessage = updated[systemIndex]
            updated[systemIndex] = systemMessage.copy(
                content = systemMessage.content + browserPart
            )
        } else {
            updated.add(
                index = 0,
                element = ChatRequestMessage(
                    role = "system",
                    content = listOf(browserPart)
                )
            )
        }

        return updated
    }

    private fun extractUserQuestion(value: String): String {
        val marker = "PERTANYAAN USER:"
        return if (value.contains(marker)) {
            value.substringAfterLast(marker).trim()
        } else {
            value.trim()
        }
    }

    private fun containsExistingBrowserContext(systemText: String): Boolean {
        return systemText.contains("WEB_BROWSER_CONTEXT", ignoreCase = true) ||
            systemText.contains("scraped web content", ignoreCase = true) ||
            systemText.contains("Use the following scraped", ignoreCase = true)
    }

    private fun containsSpecializedRealtimeContext(systemText: String): Boolean {
        return systemText.contains("Holiday API Result", ignoreCase = true) ||
            systemText.contains("Realtime Price API Data", ignoreCase = true)
    }
}

private data class SerializableChatRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    val stream: Boolean = false,
    val reasoning: ReasoningConfig? = null
)
