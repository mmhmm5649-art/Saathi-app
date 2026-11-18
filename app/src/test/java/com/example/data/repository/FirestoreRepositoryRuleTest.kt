package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.remote.FirestoreRepository
import com.example.model.SaathiLanguage
import com.example.model.VoiceGender
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class FirestoreRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureProfileExists_authenticatedUser_createsAndObservesProfile() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = FirestoreRepository(firestore)

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureProfileExists(
                displayName = "Alice",
                photoUrl = null,
                language = SaathiLanguage.TELUGU,
                voiceId = "21m00Tcm4TlvDq8ikWAM",
                voiceGender = VoiceGender.FEMALE
            )
        }
        assertTrue(result.isSuccess)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeProfile(uid).first { it != null }
        }
        assertEquals("Alice", observed?.displayName)
        assertEquals("TELUGU", observed?.preferredLanguage)
    }

    @Test
    fun saveAndObserveMemories_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = FirestoreRepository(firestore)
        val memId = "mem_${UUID.randomUUID().toString().replace("-", "")}"

        val saveRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveMemory(memId, "Interest", "Loves Telugu music", isNew = true)
        }
        assertTrue(saveRes.isSuccess)

        val memories = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMemories(uid).first { list -> list.any { it.id == memId } }
        }
        assertTrue(memories.any { it.id == memId && it.content == "Loves Telugu music" })
    }

    @Test
    fun observeMemories_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = FirestoreRepository(firestore)
        val memId = "mem_${UUID.randomUUID().toString().replace("-", "")}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.saveMemory(memId, "Context", "Alice private memory", isNew = true).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = FirestoreRepository(firestore)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeMemories(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED for cross-user memory observation")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException()) {
                "Expected FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError.code)
        }
    }

    @Test
    fun observeMemories_unauthenticatedCaller_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = FirestoreRepository(firestore)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeMemories("alice_uid").first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated memory observation")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException()) {
                "Expected FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_saathi@test.com"
        const val BOB_EMAIL = "bob_saathi@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3500L
    }
}
