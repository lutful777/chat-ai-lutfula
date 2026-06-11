package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class, ChatSessionEntity::class, MemoryEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
}
