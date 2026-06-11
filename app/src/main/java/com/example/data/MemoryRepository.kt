package com.example.data

import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val appwriteMemoryRepository: AppwriteMemoryRepository? = null
) {
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> {
        syncCloudToLocal()
        return memoryDao.getAllMemories()
    }

    suspend fun searchMemories(query: String): List<MemoryEntity> {
        syncCloudToLocal()
        return memoryDao.searchMemories(query)
    }

    suspend fun insertMemory(content: String, category: String, source: String = "user") {
        val memory = MemoryEntity(
            content = content,
            category = category,
            source = source
        )

        memoryDao.insertMemory(memory)
        appwriteMemoryRepository?.saveMemory(memory)
    }

    suspend fun testCloudMemory(): String {
        return appwriteMemoryRepository?.testCloudMemory()
            ?: "Appwrite cloud memory is not connected."
    }

    suspend fun deleteMemoryById(id: Int) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun deleteAllMemories() {
        memoryDao.deleteAllMemories()
        appwriteMemoryRepository?.deleteAllMemories()
    }
    
    suspend fun deleteMemoryByContent(query: String) {
        memoryDao.deleteMemoryByContent(query)
        appwriteMemoryRepository?.deleteMemoriesByContent(query)
    }

    private suspend fun syncCloudToLocal() {
        val cloudMemories = appwriteMemoryRepository?.getAllMemories().orEmpty()
        if (cloudMemories.isEmpty()) return

        val localMemories = memoryDao.getAllMemories()
        val localContents = localMemories.map { it.content.trim().lowercase() }.toSet()

        cloudMemories.forEach { memory ->
            val normalizedContent = memory.content.trim().lowercase()
            if (normalizedContent.isNotBlank() && normalizedContent !in localContents) {
                memoryDao.insertMemory(memory.copy(id = 0, source = memory.source.ifBlank { "appwrite" }))
            }
        }
    }
}
