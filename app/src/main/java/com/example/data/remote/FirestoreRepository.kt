package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.model.ConversationDoc
import com.example.model.MemoryDoc
import com.example.model.MessageDoc
import com.example.model.SaathiLanguage
import com.example.model.UserProfileDoc
import com.example.model.VoiceGender
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Authenticated Firebase user required before accessing Firestore.")

    fun observeProfile(userId: String): Flow<UserProfileDoc?> = flow {
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(UserProfileDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.GET, docRef.path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureProfileExists(
        displayName: String,
        photoUrl: String?,
        language: SaathiLanguage,
        voiceId: String,
        voiceGender: VoiceGender
    ): Result<UserProfileDoc> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val existing = try {
            docRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path, auth)
            throw e
        }
        if (existing.exists()) {
            existing.toObject(UserProfileDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: UserProfileDoc(id = uid, userId = uid, displayName = displayName)
        } else {
            val newDoc = UserProfileDoc(
                id = uid,
                userId = uid,
                displayName = displayName.ifBlank { "Friend" }.take(80),
                preferredName = null,
                photoUrl = photoUrl?.take(500),
                preferredLanguage = language.name,
                selectedVoiceId = voiceId,
                selectedVoiceGender = voiceGender.name
            )
            try {
                docRef.set(newDoc.toCreateMap()).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
                throw e
            }
            newDoc
        }
    }

    suspend fun updateProfileFields(fields: Map<String, Any>): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val payload = fields.toMutableMap().apply {
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            docRef.update(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    fun observeMemories(userId: String): Flow<List<MemoryDoc>> = flow {
        val colRef = db.collection("users").document(userId).collection("memories")
        emitAll(
            colRef.orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { qs ->
                    qs.toObjects(MemoryDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, colRef.path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun saveMemory(memoryId: String, category: String, content: String, isNew: Boolean): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("memories").document(memoryId)
        try {
            if (isNew) {
                val payload = mapOf(
                    "userId" to uid,
                    "category" to category.take(40),
                    "content" to content.take(500),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val payload = mapOf(
                    "category" to category.take(40),
                    "content" to content.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (isNew) OperationType.CREATE else OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteMemory(memoryId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("memories").document(memoryId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    fun observeConversations(userId: String): Flow<List<ConversationDoc>> = flow {
        val colRef = db.collection("users").document(userId).collection("conversations")
        emitAll(
            colRef.orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { qs ->
                    qs.toObjects(ConversationDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, colRef.path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertConversation(
        conversationId: String,
        title: String,
        language: SaathiLanguage,
        lastMessagePreview: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("conversations").document(conversationId)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(
                    mapOf(
                        "title" to title.ifBlank { "SAATHI Chat" }.take(120),
                        "languageCode" to language.name,
                        "lastMessagePreview" to lastMessagePreview.take(300),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "title" to title.ifBlank { "SAATHI Chat" }.take(120),
                        "languageCode" to language.name,
                        "lastMessagePreview" to lastMessagePreview.take(300),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun addMessage(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
        isVoice: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("conversations").document(conversationId)
            .collection("messages").document(messageId)
        try {
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "role" to role,
                    "text" to text.take(8000),
                    "isVoice" to isVoice,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun getMessagesOnce(conversationId: String): Result<List<MessageDoc>> = runCatching {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid)
            .collection("conversations").document(conversationId)
            .collection("messages")
        try {
            colRef.orderBy("createdAt", Query.Direction.ASCENDING)
                .get()
                .await()
                .toObjects(MessageDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colRef.path, auth)
            throw e
        }
    }

    suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val convRef = db.collection("users").document(uid)
            .collection("conversations").document(conversationId)
        try {
            val messages = convRef.collection("messages").get().await()
            for (msg in messages.documents) {
                msg.reference.delete().await()
            }
            convRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, convRef.path, auth)
            throw e
        }
    }

    suspend fun deleteAllUserData(): Result<Unit> = runCatching {
        val uid = requireUserId()
        val userDoc = db.collection("users").document(uid)
        try {
            val memories = userDoc.collection("memories").get().await()
            for (doc in memories.documents) {
                doc.reference.delete().await()
            }
            val conversations = userDoc.collection("conversations").get().await()
            for (conv in conversations.documents) {
                val msgs = conv.reference.collection("messages").get().await()
                for (msg in msgs.documents) {
                    msg.reference.delete().await()
                }
                conv.reference.delete().await()
            }
            userDoc.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, userDoc.path, auth)
            throw e
        }
    }
}
