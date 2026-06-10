package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Long = 0,
    val role: String,
    val content: String,
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
