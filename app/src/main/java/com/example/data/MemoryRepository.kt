package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val cloudMemoryClient: CloudMemoryClient? = null
) {
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> {
        val local = memoryDao.getAllMemories()
        val cloud = withContext(Dispatchers.IO) {
            cloudMemoryClient?.searchMemories("") ?: emptyList()
        }
        return mergeMemories(local, cloud)
    }

    suspend fun searchMemories(query: String): List<MemoryEntity> {
        val local = memoryDao.searchMemories(query)
        val cloud = withContext(Dispatchers.IO) {
            cloudMemoryClient?.searchMemories(query) ?: emptyList()
        }
        return mergeMemories(local, cloud)
    }

    suspend fun insertMemory(content: String, category: String, source: String = "user") {
        memoryDao.insertMemory(
            MemoryEntity(
                content = content,
                category = category,
                source = source
            )
        )

        withContext(Dispatchers.IO) {
            cloudMemoryClient?.saveMemory(content, category, source)
        }
    }

    suspend fun syncLocalMemoriesToCloud(limit: Int = 100) {
        val memories = memoryDao.getAllMemories().take(limit)
        withContext(Dispatchers.IO) {
            memories.forEach { memory ->
                cloudMemoryClient?.saveMemory(memory.content, memory.category, memory.source)
            }
        }
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

    private fun mergeMemories(local: List<MemoryEntity>, cloud: List<MemoryEntity>): List<MemoryEntity> {
        if (cloud.isEmpty()) return local
        val seen = linkedSetOf<String>()
        return (local + cloud).filter { memory ->
            val key = memory.content.trim().lowercase()
            key.isNotEmpty() && seen.add(key)
        }
    }
}
