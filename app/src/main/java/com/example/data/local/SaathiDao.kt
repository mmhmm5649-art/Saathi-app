package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SaathiDao {
    @Query("SELECT * FROM conversations WHERE userId = :userId ORDER BY updatedAtEpochMs DESC")
    fun observeConversations(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE userId = :userId AND (title LIKE '%' || :query || '%' OR lastMessagePreview LIKE '%' || :query || '%') ORDER BY updatedAtEpochMs DESC")
    fun searchConversations(userId: String, query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAtEpochMs = :updatedAt WHERE id = :conversationId")
    suspend fun renameConversation(conversationId: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET languageCode = :languageCode, lastMessagePreview = :preview, updatedAtEpochMs = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationHeaderFast(
        conversationId: String,
        languageCode: String,
        preview: String,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversations WHERE userId = :userId")
    suspend fun clearConversationsForUser(userId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampEpochMs ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampEpochMs ASC")
    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampEpochMs DESC LIMIT :limit) ORDER BY timestampEpochMs ASC")
    suspend fun getRecentMessagesOnce(conversationId: String, limit: Int = 16): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampEpochMs ASC LIMIT 1")
    suspend fun getFirstMessageOnce(conversationId: String): MessageEntity?

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT * FROM memories WHERE userId = :userId ORDER BY updatedAtEpochMs DESC")
    fun observeMemories(userId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE userId = :userId ORDER BY updatedAtEpochMs DESC")
    suspend fun getMemoriesOnce(userId: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE userId = :userId ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    suspend fun getRecentMemoriesOnce(userId: String, limit: Int = 15): List<MemoryEntity>

    @Upsert
    suspend fun upsertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :memoryId")
    suspend fun deleteMemory(memoryId: String)

    @Query("DELETE FROM memories WHERE userId = :userId")
    suspend fun clearMemoriesForUser(userId: String)
}
