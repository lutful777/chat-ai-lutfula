package com.example.data

import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun insertMemory(content: String, category: String, source: String = "user") {
        memoryDao.insertMemory(
            MemoryEntity(
                content = content,
                category = category,
                source = source
            )
        )
    }

    suspend fun deleteMemoryById(id: Int) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun deleteAllMemories() {
        memoryDao.deleteAllMemories()
    }
    
    suspend fun deleteMemoryByContent(query: String) {
        memoryDao.deleteMemoryByContent(query)
    }
}
