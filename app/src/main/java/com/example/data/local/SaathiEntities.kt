package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val languageCode: String,
    val lastMessagePreview: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userId: String,
    val role: String, // "user" or "model"
    val text: String,
    val isVoice: Boolean,
    val timestampEpochMs: Long
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val category: String,
    val content: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
