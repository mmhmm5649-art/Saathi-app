package com.example.data.remote

import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.model.SaathiLanguage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SaathiAiRepositoryTest {

    @Test
    fun `primary model timeout before first token falls back to fast flash lite and streams chunks`() = runTest {
        val requests = mutableListOf<CompanionStreamRequest>()
        val repo = SaathiAiRepository(
            ioDispatcher = Dispatchers.Default,
            primaryAttemptTimeoutMs = 150L,
            fallbackAttemptTimeoutMs = 2_000L,
            totalTimeoutMs = 5_000L,
            customTransport = CompanionStreamTransport { req ->
                requests.add(req)
                flow {
                    if (req.modelName == SaathiAiRepository.PRIMARY_MODEL_NAME) {
                        delay(1_000L) // Exceeds primaryAttemptTimeoutMs
                    } else {
                        emit("Hey! ")
                        emit("I'm right here.")
                    }
                }
            }
        )

        val chunks = repo.streamCompanionReply(
            userMessage = "Hey Saathi, how are you?",
            history = emptyList(),
            language = SaathiLanguage.ENGLISH,
            preferredName = "Usman",
            memories = emptyList(),
            memoryEnabled = true,
            isVoiceMode = true,
            backendUrl = "https://saathi-backend.example.com"
        ).toList()

        assertEquals(2, requests.size)
        assertEquals(SaathiAiRepository.PRIMARY_MODEL_NAME, requests[0].modelName)
        assertEquals(CompanionThinkingLevel.LOW, requests[0].thinkingLevel)
        assertEquals(SaathiAiRepository.FAST_MODEL_NAME, requests[1].modelName)
        assertEquals(CompanionThinkingLevel.MINIMAL, requests[1].thinkingLevel)
        assertEquals(listOf("Hey! ", "Hey! I'm right here."), chunks)
    }

    @Test
    fun `both primary and fallback timing out surfaces actionable IllegalStateException`() = runTest {
        val repo = SaathiAiRepository(
            ioDispatcher = Dispatchers.Default,
            primaryAttemptTimeoutMs = 100L,
            fallbackAttemptTimeoutMs = 100L,
            totalTimeoutMs = 500L,
            customTransport = CompanionStreamTransport { _ ->
                flow {
                    delay(5_000L)
                }
            }
        )

        try {
            repo.streamCompanionReply(
                userMessage = "Hello",
                history = emptyList(),
                language = SaathiLanguage.ENGLISH,
                preferredName = "",
                memories = emptyList(),
                memoryEnabled = false,
                isVoiceMode = false,
                backendUrl = "https://saathi-backend.example.com"
            ).toList()
            fail("Expected IllegalStateException on timeout")
        } catch (e: IllegalStateException) {
            assertEquals(SaathiAiRepository.TIMEOUT_USER_MESSAGE, e.message)
        }
    }

    @Test
    fun `cancellation exception propagates cooperatively without fallback`() = runTest {
        var calls = 0
        val repo = SaathiAiRepository(
            ioDispatcher = Dispatchers.Default,
            customTransport = CompanionStreamTransport { _ ->
                calls++
                flow {
                    throw CancellationException("User interrupted")
                }
            }
        )

        try {
            repo.streamCompanionReply(
                userMessage = "Stop",
                history = emptyList(),
                language = SaathiLanguage.HINDI,
                preferredName = "",
                memories = emptyList(),
                memoryEnabled = false,
                isVoiceMode = true,
                backendUrl = "https://saathi-backend.example.com"
            ).toList()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `normalizeHistoryAndPrompt merges consecutive turns and deduplicates trailing retried user message`() {
        val repo = SaathiAiRepository()
        val history = listOf(
            MessageEntity("1", "c1", "u1", "model", "Ignored leading model", false, 1L),
            MessageEntity("2", "c1", "u1", "user", "First question", false, 2L),
            MessageEntity("3", "c1", "u1", "user", "Follow up", false, 3L),
            MessageEntity("4", "c1", "u1", "model", "Sure, here you go.", false, 4L),
            MessageEntity("5", "c1", "u1", "user", "Retry this prompt", false, 5L)
        )
        val memories = listOf(
            MemoryEntity("m1", "u1", "Interest", "Loves filter coffee", 1L, 1L)
        )

        val normalized = repo.normalizeHistoryAndPrompt(
            userMessage = "Retry this prompt",
            history = history,
            preferredName = "Usman",
            memories = memories
        )

        // Expected alternating turns:
        // [0] user session context -> [1] model ack -> [2] merged user -> [3] model reply
        assertEquals(4, normalized.historyTurns.size)
        assertEquals("user", normalized.historyTurns[0].role)
        assertEquals("model", normalized.historyTurns[1].role)
        assertEquals("user", normalized.historyTurns[2].role)
        assertEquals("model", normalized.historyTurns[3].role)
        assertEquals("Retry this prompt", normalized.effectivePrompt)
    }

    @Test
    fun `extractMemoryCandidate parses valid json suggestion cleanly`() = runTest {
        val repo = SaathiAiRepository(
            ioDispatcher = Dispatchers.Default,
            customMemoryExtractor = {
                """{"shouldSuggest":true,"category":"Interest","content":"Enjoys late night coding sessions"}"""
            }
        )

        val suggestion = repo.extractMemoryCandidate(
            userMessage = "I love coding late at night with lo-fi music.",
            existingMemories = emptyList()
        )

        assertNotNull(suggestion)
        assertEquals("Interest", suggestion?.category)
        assertTrue(suggestion?.content?.contains("coding") == true)
    }

    @Test
    fun `Telugu streaming enforces Roman Telugu display and system instructions configure person-to-person personality`() = runTest {
        val capturedRequests = mutableListOf<CompanionStreamRequest>()
        val repo = SaathiAiRepository(
            ioDispatcher = Dispatchers.Default,
            customTransport = CompanionStreamTransport { req ->
                capturedRequests.add(req)
                flow {
                    // Simulate model accidentally outputting Telugu Unicode mid-stream
                    emit("అరే! ")
                    emit("బాగున్నా రా 😄 ")
                    emit("ఈరోజు ఏం చేస్తున్నావు?")
                }
            }
        )

        val chunks = repo.streamCompanionReply(
            userMessage = "Ela unnav ra?",
            history = emptyList(),
            language = SaathiLanguage.TELUGU,
            preferredName = "Usman",
            memories = emptyList(),
            memoryEnabled = false,
            isVoiceMode = false,
            backendUrl = "https://saathi-backend.example.com"
        ).toList()

        assertEquals(1, capturedRequests.size)
        val sys = capturedRequests[0].systemInstruction
        assertTrue(sys.contains("NATURAL ROMAN TELUGU ONLY"))
        assertTrue(sys.contains("Nenu baane unna ra"))
        assertTrue(sys.contains("Avunaa?"))
        assertTrue(sys.contains("NEVER start or end messages with \"How can I help you?\""))

        val finalReply = chunks.last()
        assertTrue(
            "Final Telugu reply must not contain any Telugu Unicode script: $finalReply",
            !com.example.model.SaathiVoiceCatalog.containsTeluguUnicode(finalReply)
        )
        assertTrue(finalReply.contains("baagunna", ignoreCase = true))
        assertTrue(finalReply.contains("em chestunnav", ignoreCase = true))
    }

    @Test
    fun `default HTTP SSE transport streams replies and extracts memory via standalone backend`() = runTest {
        val capturedChatBodies = CopyOnWriteArrayList<JSONObject>()
        val capturedClientHeaders = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        server.createContext("/v1/chat/stream") { exchange ->
            exchange.requestHeaders.getFirst("X-Saathi-Client")?.let { capturedClientHeaders.add(it) }
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            capturedChatBodies.add(body)
            val sse = buildString {
                append("data: {\"text\":\"Nenu baane \"}\n\n")
                append("data: {\"text\":\"unna ra!\"}\n\n")
                append("data: {\"done\":true,\"modelUsed\":\"gemini-2.5-flash\"}\n\n")
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.sendResponseHeaders(200, sse.size.toLong())
            exchange.responseBody.use { it.write(sse) }
        }

        server.createContext("/v1/memory/extract") { exchange ->
            exchange.requestHeaders.getFirst("X-Saathi-Client")?.let { capturedClientHeaders.add(it) }
            val payload = JSONObject().apply {
                put("shouldSuggest", true)
                put("category", "Preference")
                put("content", "Drinks Irani chai in the evening")
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            val repo = SaathiAiRepository(
                backendUrlProvider = { baseUrl },
                clientTokenProvider = { "optional_token_123" }
            )

            val chunks = repo.streamCompanionReply(
                userMessage = "Ela unnav ra?",
                history = listOf(MessageEntity("m0", "c1", "u1", "model", "Arey, ela unnav ra? Baagunnava? Eeroju em chestunnav?", true, 1L)),
                language = SaathiLanguage.TELUGU,
                preferredName = "Usman",
                memories = listOf(MemoryEntity("mem1", "u1", "City", "Lives in Hyderabad", 1L, 1L)),
                memoryEnabled = true,
                isVoiceMode = true,
                backendUrl = baseUrl
            ).toList()

            assertEquals(listOf("Nenu baane ", "Nenu baane unna ra!"), chunks)
            assertEquals(1, capturedChatBodies.size)
            assertEquals("gemini-2.5-flash", capturedChatBodies[0].getString("modelName"))
            assertEquals("te", capturedChatBodies[0].getString("languageCode"))
            assertEquals("Ela unnav ra?", capturedChatBodies[0].getString("userMessage"))
            assertTrue(capturedChatBodies[0].getJSONArray("history").length() >= 2)

            val suggestion = repo.extractMemoryCandidate(
                userMessage = "I love drinking Irani chai every evening.",
                existingMemories = emptyList(),
                backendUrl = baseUrl
            )
            assertNotNull(suggestion)
            assertEquals("Preference", suggestion?.category)
            assertEquals("Drinks Irani chai in the evening", suggestion?.content)
            assertEquals(listOf("saathi-android", "saathi-android"), capturedClientHeaders)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cleartext remote http backend URL is rejected to enforce HTTPS`() = runTest {
        val repo = SaathiAiRepository(ioDispatcher = Dispatchers.Default)
        try {
            repo.streamCompanionReply(
                userMessage = "Hello",
                history = emptyList(),
                language = SaathiLanguage.ENGLISH,
                preferredName = "",
                memories = emptyList(),
                memoryEnabled = false,
                isVoiceMode = false,
                backendUrl = "http://insecure.example.com"
            ).toList()
            fail("Expected IllegalStateException for remote cleartext HTTP backend URL")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("HTTPS", ignoreCase = true) == true)
        }
    }
}
