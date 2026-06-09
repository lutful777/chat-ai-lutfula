package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(private val chatDao: ChatDao) {
    val allMessages: Flow<List<MessageEntity>> = chatDao.getAllMessages()

    suspend fun insertMessage(message: MessageEntity) = chatDao.insertMessage(message)

    suspend fun clearHistory() = chatDao.clearHistory()
}
