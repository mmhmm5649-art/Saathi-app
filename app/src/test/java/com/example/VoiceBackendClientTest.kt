package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.TtsAudioSource
import com.example.data.remote.VoiceBackendClient
import com.example.data.remote.VoiceBackendResult
import com.example.model.SaathiLanguage
import com.example.model.SaathiVoiceCatalog
import com.example.model.VoiceGender
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceBackendClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val capturedTtsRequests = CopyOnWriteArrayList<JSONObject>()
    private val capturedTtsPaths = CopyOnWriteArrayList<String>()
    private val capturedClientHeaders = CopyOnWriteArrayList<String>()
    private val capturedClientTokens = CopyOnWriteArrayList<String>()
    private val scriptedTtsStatuses = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, String?>>()
    private val scriptedSttStatuses = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, String?>>()
    private val ttsRequestCounter = AtomicInteger(0)
    private val sttRequestCounter = AtomicInteger(0)
    private val sttArtificialDelayMs = AtomicInteger(0)
    @Volatile
    private var sttStartedLatch: java.util.concurrent.CountDownLatch? = null
    private var configuredFemaleId = "indian_female_lib_01"
    private var configuredMaleId = "indian_male_lib_02"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        baseUrl = "http://127.0.0.1:${server.address.port}"

        server.createContext("/health") { exchange ->
            val json = JSONObject().apply {
                put("ok", true)
                put("geminiConfigured", true)
                put("elevenLabsConfigured", true)
                put("ttsModel", "eleven_v4_turbo")
                put("sttModel", "scribe_v3_live")
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }

        server.createContext("/v1/voices") { exchange ->
            exchange.requestHeaders.getFirst("X-Saathi-Client")?.let { capturedClientHeaders.add(it) }
            exchange.requestHeaders.getFirst("X-Saathi-Client-Token")?.let { capturedClientTokens.add(it) }
            val path = exchange.requestURI.path
            if (path.endsWith("/config") && exchange.requestMethod == "POST") {
                val reqBody = JSONObject(exchange.requestBody.bufferedReader().readText())
                if (reqBody.has("femaleVoiceId")) configuredFemaleId = reqBody.getString("femaleVoiceId")
                if (reqBody.has("maleVoiceId")) configuredMaleId = reqBody.getString("maleVoiceId")
            }

            val voices = JSONArray().apply {
                put(JSONObject().apply {
                    put("slot", "female")
                    put("gender", "FEMALE")
                    put("voiceId", configuredFemaleId)
                    put("name", "Ananya (Indian Female)")
                    put("accent", "Indian")
                    put("tone", "Warm & Expressive")
                    put("description", "Natural Indian female voice")
                    put("modelId", "eleven_v4_turbo")
                    put("configured", true)
                    put("source", "voice_library")
                })
                put(JSONObject().apply {
                    put("slot", "male")
                    put("gender", "MALE")
                    put("voiceId", configuredMaleId)
                    put("name", "Arjun (Indian Male)")
                    put("accent", "Indian")
                    put("tone", "Grounded & Friendly")
                    put("description", "Natural Indian male voice")
                    put("modelId", "eleven_v4_turbo")
                    put("configured", true)
                    put("source", "voice_library")
                })
            }
            val payload = JSONObject().apply {
                put("ttsModel", "eleven_v4_turbo")
                put("sttModel", "scribe_v3_live")
                put("voices", voices)
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val ttsHandler: (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
            exchange.requestHeaders.getFirst("X-Saathi-Client")?.let { capturedClientHeaders.add(it) }
            exchange.requestHeaders.getFirst("X-Saathi-Client-Token")?.let { capturedClientTokens.add(it) }
            ttsRequestCounter.incrementAndGet()
            capturedTtsPaths.add(exchange.requestURI.path)
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            capturedTtsRequests.add(body)

            val scripted = scriptedTtsStatuses.poll()
            if (scripted != null) {
                val (statusCode, retryAfter) = scripted
                if (retryAfter != null) {
                    exchange.responseHeaders.add("Retry-After", retryAfter)
                }
                val errPayload = JSONObject().put("error", "Rate limited").toString().toByteArray()
                exchange.sendResponseHeaders(statusCode, errPayload.size.toLong())
                exchange.responseBody.use { it.write(errPayload) }
            } else {
                val langCode = body.optString("languageCode", "en")
                val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04) + langCode.toByteArray() + ByteArray(64) { 0x2A }
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.sendResponseHeaders(200, fakeMp3.size.toLong())
                exchange.responseBody.use { it.write(fakeMp3) }
            }
        }
        server.createContext("/v1/tts/stream", ttsHandler)
        server.createContext("/v1/tts/preview", ttsHandler)

        server.createContext("/v1/stt") { exchange ->
            exchange.requestHeaders.getFirst("X-Saathi-Client")?.let { capturedClientHeaders.add(it) }
            exchange.requestHeaders.getFirst("X-Saathi-Client-Token")?.let { capturedClientTokens.add(it) }
            sttRequestCounter.incrementAndGet()
            sttStartedLatch?.countDown()
            val delayMs = sttArtificialDelayMs.get()
            if (delayMs > 0) {
                runCatching { Thread.sleep(delayMs.toLong()) }
            }
            val raw = exchange.requestBody.readBytes().decodeToString()
            val scripted = scriptedSttStatuses.poll()
            if (scripted != null) {
                val (statusCode, retryAfter) = scripted
                if (retryAfter != null) {
                    exchange.responseHeaders.add("Retry-After", retryAfter)
                }
                val errPayload = JSONObject().put("error", "Scribe busy").toString().toByteArray()
                exchange.sendResponseHeaders(statusCode, errPayload.size.toLong())
                exchange.responseBody.use { it.write(errPayload) }
                return@createContext
            }
            val detectedLang = Regex("name=\"languageCode\"[\\s\\S]*?\\r\\n\\r\\n(te|hi|en)\\b")
                .find(raw)
                ?.groupValues
                ?.get(1)
            val text = when (detectedLang) {
                "te" -> SaathiLanguage.TELUGU.sampleUserPrompt
                "hi" -> SaathiLanguage.HINDI.sampleUserPrompt
                else -> SaathiLanguage.ENGLISH.sampleUserPrompt
            }
            val payload = JSONObject().put("text", text).toString().toByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `sample conversations in Telugu, Hindi, and English preview both voices and preserve selected voice identity`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = VoiceBackendClient(context = context, clientTokenProvider = { "test_saathi_client_token" })

        val health = client.checkHealth(baseUrl)
        assertTrue(health is VoiceBackendResult.Success)
        assertTrue((health as VoiceBackendResult.Success).data.contains("eleven_v4_turbo"))
        assertTrue(health.data.contains("Gemini"))

        // Fetch configurable Indian Male & Female voices from backend
        val voicesRes = client.fetchConfiguredVoices(baseUrl)
        assertTrue(voicesRes is VoiceBackendResult.Success)
        val voices = (voicesRes as VoiceBackendResult.Success).data.voices
        assertEquals(2, voices.size)

        // Preview both Female and Male voices before selecting
        val previewFemale = client.synthesizeSpeechToCacheFile(
            baseUrl = baseUrl,
            text = SaathiLanguage.TELUGU.sampleReply,
            voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
            voiceId = configuredFemaleId,
            modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
            language = SaathiLanguage.TELUGU,
            speed = 1.0f,
            isPreview = true
        )
        val previewMale = client.synthesizeSpeechToCacheFile(
            baseUrl = baseUrl,
            text = SaathiLanguage.TELUGU.sampleReply,
            voiceSlot = SaathiVoiceCatalog.SLOT_MALE,
            voiceId = configuredMaleId,
            modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
            language = SaathiLanguage.TELUGU,
            speed = 1.0f,
            isPreview = true
        )
        assertTrue(previewFemale is VoiceBackendResult.Success)
        assertTrue(previewMale is VoiceBackendResult.Success)
        (previewFemale as VoiceBackendResult.Success).data.delete()
        (previewMale as VoiceBackendResult.Success).data.delete()

        // Configure a new Indian Female voice ID via backend settings
        val configUpdate = client.updateBackendVoiceConfig(
            baseUrl = baseUrl,
            gender = VoiceGender.FEMALE,
            voiceId = "custom_telugu_female_777",
            voiceName = "Kavya (Telugu/Hindi/English)"
        )
        assertTrue(configUpdate is VoiceBackendResult.Success)
        assertEquals(
            "custom_telugu_female_777",
            (configUpdate as VoiceBackendResult.Success).data.voices.first { it.gender == VoiceGender.FEMALE }.configuredVoiceId
        )

        // Sample 3-language conversation maintaining the exact same Female voice identity across te, hi, en
        capturedTtsRequests.clear()
        capturedTtsPaths.clear()

        for (lang in listOf(SaathiLanguage.TELUGU, SaathiLanguage.HINDI, SaathiLanguage.ENGLISH)) {
            val sttRes = client.transcribeWithScribe(baseUrl, "RIFFWAVE".toByteArray(), lang)
            assertTrue(sttRes is VoiceBackendResult.Success)
            assertEquals(lang.sampleUserPrompt, (sttRes as VoiceBackendResult.Success).data)

            val ttsRes = client.synthesizeSpeechToCacheFile(
                baseUrl = baseUrl,
                text = lang.sampleReply,
                voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
                voiceId = "custom_telugu_female_777",
                modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
                language = lang,
                speed = 1.0f,
                isPreview = false
            )
            assertTrue(ttsRes is VoiceBackendResult.Success)
            (ttsRes as VoiceBackendResult.Success).data.delete()
        }

        assertEquals(3, capturedTtsRequests.size)
        assertEquals(listOf("te", "hi", "en"), capturedTtsRequests.map { it.getString("languageCode") })
        assertTrue(capturedTtsRequests.all { it.getString("voiceSlot") == "female" })
        assertTrue(capturedTtsRequests.all { it.getString("voiceId") == "custom_telugu_female_777" })
        assertTrue(capturedTtsRequests.all { it.getString("modelId") == "eleven_v4_turbo" })
        assertTrue(capturedTtsPaths.all { it == "/v1/tts/stream" })
    }

    @Test
    fun `openTtsAudioSource returns ProgressiveStream immediately with initialBytes for Telugu Hindi English`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = VoiceBackendClient(context = context, clientTokenProvider = { "test_saathi_client_token" })

        for (lang in listOf(SaathiLanguage.TELUGU, SaathiLanguage.HINDI, SaathiLanguage.ENGLISH)) {
            val res = client.openTtsAudioSource(
                baseUrl = baseUrl,
                text = lang.sampleReply,
                voiceSlot = SaathiVoiceCatalog.SLOT_MALE,
                voiceId = configuredMaleId,
                modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
                language = lang,
                speed = 1.0f,
                isPreview = false
            )
            assertTrue(res is VoiceBackendResult.Success)
            val source = (res as VoiceBackendResult.Success).data
            assertTrue(source is TtsAudioSource.ProgressiveStream)
            val stream = source as TtsAudioSource.ProgressiveStream
            assertTrue(stream.initialBytes.isNotEmpty())
            val remaining = stream.byteStream.readBytes()
            val all = stream.initialBytes + remaining
            assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33, 0x04), all.copyOfRange(0, 4))
            stream.closeQuietly()
            stream.onStreamAborted(stream.cacheKey)
        }
    }

    @Test
    fun `openTtsAudioSource retries HTTP 429 with Retry-After and HTTP 503 then succeeds`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = VoiceBackendClient(context = context, clientTokenProvider = { "test_saathi_client_token" })

        scriptedTtsStatuses.add(429 to "0.3")
        scriptedTtsStatuses.add(503 to null)

        val res = client.synthesizeSpeechToCacheFile(
            baseUrl = baseUrl,
            text = "Rate limit recovery test",
            voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
            voiceId = configuredFemaleId,
            modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
            language = SaathiLanguage.ENGLISH,
            speed = 1.0f,
            isPreview = false
        )

        assertTrue(res is VoiceBackendResult.Success)
        assertEquals(3, ttsRequestCounter.get())
        (res as VoiceBackendResult.Success).data.delete()
    }

    @Test
    fun `duplicate concurrent and sequential TTS requests coalesce and hit cache then invalidate on voice update`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = VoiceBackendClient(context = context, clientTokenProvider = { "test_saathi_client_token" })

        // Concurrent identical synthesis calls must coalesce onto a single backend HTTP request
        val results = listOf(
            async {
                client.synthesizeSpeechToCacheFile(
                    baseUrl = baseUrl,
                    text = "Hey! I'm SAATHI",
                    voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
                    voiceId = configuredFemaleId,
                    modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
                    language = SaathiLanguage.ENGLISH,
                    speed = 1.0f,
                    isPreview = true
                )
            },
            async {
                client.synthesizeSpeechToCacheFile(
                    baseUrl = baseUrl,
                    text = "Hey! I'm SAATHI",
                    voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
                    voiceId = configuredFemaleId,
                    modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
                    language = SaathiLanguage.ENGLISH,
                    speed = 1.0f,
                    isPreview = true
                )
            }
        ).awaitAll()

        assertTrue(results.all { it is VoiceBackendResult.Success })
        assertEquals(1, ttsRequestCounter.get())

        // Subsequent identical request hits short-lived TTS cache cleanly
        val cachedHit = client.openTtsAudioSource(
            baseUrl = baseUrl,
            text = "Hey! I'm SAATHI",
            voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
            voiceId = configuredFemaleId,
            modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
            language = SaathiLanguage.ENGLISH,
            speed = 1.0f,
            isPreview = true
        )
        assertTrue(cachedHit is VoiceBackendResult.Success)
        assertTrue((cachedHit as VoiceBackendResult.Success).data is TtsAudioSource.CachedFile)
        assertEquals(1, ttsRequestCounter.get())

        // Changing backend voice config invalidates cached audio so fresh synthesis uses updated voice
        val update = client.updateBackendVoiceConfig(
            baseUrl = baseUrl,
            gender = VoiceGender.FEMALE,
            voiceId = "updated_female_voice_99",
            voiceName = "Meera"
        )
        assertTrue(update is VoiceBackendResult.Success)

        val afterConfigChange = client.synthesizeSpeechToCacheFile(
            baseUrl = baseUrl,
            text = "Hey! I'm SAATHI",
            voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
            voiceId = configuredFemaleId,
            modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
            language = SaathiLanguage.ENGLISH,
            speed = 1.0f,
            isPreview = true
        )
        assertTrue(afterConfigChange is VoiceBackendResult.Success)
        assertEquals(2, ttsRequestCounter.get())
    }

    @Test
    fun `transcribeWithScribe retries transient HTTP 429 and recovers cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = VoiceBackendClient(context = context, clientTokenProvider = { "test_saathi_client_token" })

        scriptedSttStatuses.add(429 to "0.3")
        val res = client.transcribeWithScribe(baseUrl, "RIFFWAVE".toByteArray(), SaathiLanguage.TELUGU)

        assertTrue(res is VoiceBackendResult.Success)
        assertEquals(SaathiLanguage.TELUGU.sampleUserPrompt, (res as VoiceBackendResult.Success).data)
        assertEquals(2, sttRequestCounter.get())
    }

    @Test
    fun `cancelActiveSttRequest cancels in-flight Scribe call cleanly`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = VoiceBackendClient(context = context, clientTokenProvider = { "test_saathi_client_token" })
        val latch = java.util.concurrent.CountDownLatch(1)
        sttStartedLatch = latch
        sttArtificialDelayMs.set(800)

        var thrown: Throwable? = null
        val job = async(kotlinx.coroutines.Dispatchers.Default) {
            try {
                client.transcribeWithScribe(baseUrl, "RIFFWAVE".toByteArray(), SaathiLanguage.HINDI)
            } catch (t: Throwable) {
                thrown = t
                null
            }
        }
        assertTrue("Expected STT request to reach local test server", latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
        client.cancelActiveSttRequest()
        job.await()

        assertTrue(
            "Expected CancellationException when cancelActiveSttRequest() aborts in-flight Scribe upload, got $thrown",
            thrown is kotlinx.coroutines.CancellationException
        )
    }

    @Test
    fun `voice backend works standalone with client headers and enforces HTTPS for remote hosts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = VoiceBackendClient(
            context = context,
            clientTokenProvider = { "standalone_client_token_xyz" }
        )

        val voicesRes = client.fetchConfiguredVoices(baseUrl)
        assertTrue(voicesRes is VoiceBackendResult.Success)

        val ttsRes = client.synthesizeSpeechToCacheFile(
            baseUrl = baseUrl,
            text = "Hello from standalone SAATHI companion",
            voiceSlot = SaathiVoiceCatalog.SLOT_FEMALE,
            voiceId = configuredFemaleId,
            modelId = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
            language = SaathiLanguage.ENGLISH,
            speed = 1.0f,
            isPreview = false
        )
        assertTrue(ttsRes is VoiceBackendResult.Success)
        (ttsRes as VoiceBackendResult.Success).data.delete()

        val sttRes = client.transcribeWithScribe(baseUrl, "RIFFWAVE".toByteArray(), SaathiLanguage.ENGLISH)
        assertTrue(sttRes is VoiceBackendResult.Success)

        assertEquals(3, capturedClientHeaders.size)
        assertTrue(capturedClientHeaders.all { it == "saathi-android" })
        assertEquals(3, capturedClientTokens.size)
        assertTrue(capturedClientTokens.all { it == "standalone_client_token_xyz" })

        val cleartextCheck = client.checkHealth("http://insecure-remote.example.com")
        assertTrue(cleartextCheck is VoiceBackendResult.Error)
        assertTrue((cleartextCheck as VoiceBackendResult.Error).message.contains("HTTPS", ignoreCase = true))
    }
}
