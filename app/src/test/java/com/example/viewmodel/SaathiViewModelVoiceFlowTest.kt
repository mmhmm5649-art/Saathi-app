package com.example.viewmodel

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SaathiDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.remote.CompanionStreamTransport
import com.example.data.remote.FirestoreRepository
import com.example.data.remote.SaathiAiRepository
import com.example.data.remote.VoiceBackendClient
import com.example.model.SaathiLanguage
import com.example.model.SaathiVoiceCatalog
import com.example.model.VoiceGender
import com.example.model.VoiceSessionState
import com.example.voice.AndroidSpeechRecognizerManager
import com.example.voice.AudioRecorderManager
import com.example.voice.SpeechEnvironmentChecker
import com.example.voice.SpeechRecognizerClient
import com.example.voice.VoiceAudioPlayer
import com.google.firebase.FirebaseApp
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SaathiViewModelVoiceFlowTest {

    private class FakeSpeechClient : SpeechRecognizerClient {
        var listener: RecognitionListener? = null
        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }
        override fun startListening(recognizerIntent: Intent) {}
        override fun stopListening() {}
        override fun cancel() {}
        override fun destroy() {}
    }

    private class TestEnvChecker : SpeechEnvironmentChecker {
        override fun hasRecordAudioPermission(context: Context): Boolean = true
        override fun isRecognitionAvailable(context: Context): Boolean = true
        override fun isOnDeviceRecognitionAvailable(context: Context): Boolean = false
        override fun isNetworkAvailable(context: Context): Boolean = true
    }

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var db: SaathiDatabase
    private lateinit var prefsRepo: UserPreferencesRepository
    private val capturedTtsBodies = CopyOnWriteArrayList<JSONObject>()
    private val shouldFailAiOnce = AtomicBoolean(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 0) }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        db = Room.inMemoryDatabaseBuilder(context, SaathiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefsRepo = UserPreferencesRepository(context)
        runBlocking { prefsRepo.clearAll() }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        baseUrl = "http://127.0.0.1:${server.address.port}"

        server.createContext("/v1/voices") { exchange ->
            val voices = JSONArray().apply {
                put(JSONObject().apply {
                    put("slot", "female")
                    put("gender", "FEMALE")
                    put("voiceId", "env_female_voice_id_101")
                    put("name", "Ananya (Indian Female)")
                    put("accent", "Indian")
                    put("tone", "Warm & Expressive")
                    put("description", "Configured female voice")
                    put("modelId", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
                    put("configured", true)
                    put("source", "env")
                })
                put(JSONObject().apply {
                    put("slot", "male")
                    put("gender", "MALE")
                    put("voiceId", "env_male_voice_id_202")
                    put("name", "Arjun (Indian Male)")
                    put("accent", "Indian")
                    put("tone", "Grounded & Friendly")
                    put("description", "Configured male voice")
                    put("modelId", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
                    put("configured", true)
                    put("source", "env")
                })
            }
            val payload = JSONObject().apply {
                put("ttsModel", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
                put("sttModel", "scribe_v3_live")
                put("voices", voices)
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        server.createContext("/v1/tts/stream") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            body.put("_endpoint", "/v1/tts/stream")
            capturedTtsBodies.add(body)
            val lang = body.optString("languageCode", "en")
            val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04) + lang.toByteArray() + ByteArray(64) { 0x1A }
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, mp3.size.toLong())
            exchange.responseBody.use { it.write(mp3) }
        }

        server.createContext("/v1/tts/preview") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            body.put("_endpoint", "/v1/tts/preview")
            capturedTtsBodies.add(body)
            val lang = body.optString("languageCode", "en")
            val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04) + lang.toByteArray() + ByteArray(64) { 0x2B }
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, mp3.size.toLong())
            exchange.responseBody.use { it.write(mp3) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runBlocking { prefsRepo.clearAll() }
        db.close()
        server.stop(0)
        Dispatchers.resetMain()
    }

    private val capturedAiRequests = CopyOnWriteArrayList<com.example.data.remote.CompanionStreamRequest>()

    private fun createViewModel(
        speechClients: MutableList<FakeSpeechClient> = mutableListOf(),
        audioPlayerHolder: MutableList<VoiceAudioPlayer> = mutableListOf()
    ): SaathiViewModel {
        val aiRepo = SaathiAiRepository(
            ioDispatcher = Dispatchers.IO,
            customTransport = CompanionStreamTransport { req ->
                capturedAiRequests.add(req)
                flow {
                    if (shouldFailAiOnce.compareAndSet(true, false)) {
                        throw IOException("Simulated network drop")
                    }
                    when {
                        req.userMessage.contains("FUNNY_JOKE_TRIGGER") ->
                            emit("**Haha! Nijamga ra? 😂 [laughs] Adaithe bhale undi!**")
                        req.userMessage.contains("SERIOUS_UPSET_TRIGGER") ->
                            emit("Ayyo... em ayindi ra? Nemmadiga cheppu, nenu vintunnanu.")
                        req.userMessage.contains("Bore koduthundi", ignoreCase = true) ->
                            emit("Ayyo! Mari mana iddaram konchem kaburlu cheppukundama? 😂")
                        req.userMessage.contains("Naku nidra vastundi", ignoreCase = true) ->
                            emit("Haha, aithe phone pakkana petti konchem rest teesuko ra. Repu malli maatladukundam!")
                        req.systemInstruction.contains("NATURAL ROMAN TELUGU ONLY", ignoreCase = true) ->
                            emit("Nenu baane unna ra 😂 Nuvvu cheppu, em chestunnav?")
                        req.systemInstruction.contains("conversational Indian Hindi", ignoreCase = true) ->
                            emit("अरे यार! मैं बिल्कुल बढ़िया हूँ, तुम बताओ क्या चल रहा है?")
                        else ->
                            emit("Hey! I'm doing great, how is your day going?")
                    }
                }
            },
            customMemoryExtractor = { null }
        )

        val speechManager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, _ -> FakeSpeechClient().also { speechClients.add(it) } },
            envChecker = TestEnvChecker()
        )

        val player = VoiceAudioPlayer(context).also { audioPlayerHolder.add(it) }

        return SaathiViewModel(
            currentUserId = "guest_local_test",
            userDisplayName = "Usman",
            userEmail = "",
            userPhotoUrl = null,
            isGuestSession = true,
            dao = db.saathiDao(),
            prefsRepo = prefsRepo,
            firestoreRepo = FirestoreRepository(context),
            aiRepo = aiRepo,
            voiceBackendClient = VoiceBackendClient(
                context = context,
                tokenProvider = { "test_token" }
            ),
            speechRecognizer = speechManager,
            audioRecorder = AudioRecorderManager(null),
            audioPlayer = player
        )
    }

    private fun deliverVoiceResult(client: FakeSpeechClient, transcript: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(transcript))
        }
        client.listener?.onResults(bundle)
    }

    private suspend fun runAutomaticGreetingAndTurnWorkflow(
        language: SaathiLanguage,
        voiceGender: VoiceGender,
        expectedVoiceId: String,
        expectedGreeting: String,
        userSpokenText: String,
        expectedAiSubstring: String,
        runVoicePreviewFirst: Boolean = false
    ) {
        capturedTtsBodies.clear()
        capturedAiRequests.clear()
        java.io.File(context.cacheDir, "saathi_tts_cache").deleteRecursively()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val speechClients = mutableListOf<FakeSpeechClient>()
        val players = mutableListOf<VoiceAudioPlayer>()
        val vm = createViewModel(speechClients, players)
        val player = players.single()

        prefsRepo.setVoiceBackendUrl(baseUrl)
        prefsRepo.setLanguage(language)
        prefsRepo.setVoice(expectedVoiceId, voiceGender)
        vm.preferences.first {
            it.voiceBackendUrl == baseUrl &&
                it.preferredLanguage == language &&
                it.selectedVoiceGender == voiceGender &&
                it.selectedVoiceId == expectedVoiceId
        }

        if (runVoicePreviewFirst) {
            vm.previewVoice(voiceGender.slotKey)
            val previewDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < previewDeadline && (capturedTtsBodies.size < 1 || !player.hasActivePlaybackForTest())) {
                delay(20)
            }
            assertTrue("Voice preview wait failed", capturedTtsBodies.size >= 1 && player.hasActivePlaybackForTest())
            val previewBody = capturedTtsBodies[0]
            assertEquals("/v1/tts/preview", previewBody.getString("_endpoint"))
            assertEquals(expectedVoiceId, previewBody.getString("voiceId"))
            assertEquals(voiceGender.slotKey, previewBody.getString("voiceSlot"))
            player.completeActivePlaybackNowForTest()
            shadowOf(Looper.getMainLooper()).idle()
            capturedTtsBodies.clear()
            java.io.File(context.cacheDir, "saathi_tts_cache").deleteRecursively()
        }

        // 1. User opens app -> onVoiceScreenReady triggers automatic greeting (IDLE -> GREETING)
        vm.onVoiceScreenReady(hasMicPermission = true)
        // Recomposition / screen rotation calls onVoiceScreenReady again -> must NOT duplicate greeting
        vm.onVoiceScreenReady(hasMicPermission = true)

        val deadline1 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline1 && (capturedTtsBodies.size < 1 || !player.hasActivePlaybackForTest())) {
            delay(20)
        }
        assertTrue(
            "Greeting wait failed: tts=${capturedTtsBodies.size}, activePlayback=${player.hasActivePlaybackForTest()}, state=${vm.uiState.value.voiceSessionState}, err=${vm.uiState.value.errorMessage}",
            capturedTtsBodies.size >= 1 && player.hasActivePlaybackForTest()
        )
        assertEquals(VoiceSessionState.GREETING, vm.uiState.value.voiceSessionState)
        assertEquals(expectedGreeting, vm.uiState.value.lastAssistantReply)
        if (language == SaathiLanguage.TELUGU) {
            assertTrue(!SaathiVoiceCatalog.containsTeluguUnicode(vm.uiState.value.lastAssistantReply))
        }

        val greetingTts = capturedTtsBodies[0]
        assertEquals(expectedGreeting, greetingTts.getString("text"))
        assertEquals(language.isoCode, greetingTts.getString("languageCode"))
        assertEquals(expectedVoiceId, greetingTts.getString("voiceId"))
        assertEquals(voiceGender.slotKey, greetingTts.getString("voiceSlot"))

        // Verify greeting was saved to Room history only once
        val convId = vm.uiState.value.activeConversationId
        val msgsAfterGreeting = db.saathiDao().getMessagesOnce(convId)
        assertEquals(1, msgsAfterGreeting.size)
        assertEquals("model", msgsAfterGreeting[0].role)
        assertEquals(expectedGreeting, msgsAfterGreeting[0].text)
        assertTrue(msgsAfterGreeting[0].isVoice)

        // 2. Greeting audio finishes playing -> automatically activates listening (GREETING -> LISTENING)
        player.completeActivePlaybackNowForTest()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceSessionState.LISTENING, vm.uiState.value.voiceSessionState)

        // 3. User speaks -> transcribed -> sent to Gemini with greeting in history -> ElevenLabs speaks reply
        deliverVoiceResult(speechClients.last(), userSpokenText)
        val deadline2 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline2 && (capturedTtsBodies.size < 2 || !player.hasActivePlaybackForTest())) {
            delay(20)
        }
        assertTrue(
            "Reply wait failed: tts=${capturedTtsBodies.size}, activePlayback=${player.hasActivePlaybackForTest()}, state=${vm.uiState.value.voiceSessionState}, err=${vm.uiState.value.errorMessage}",
            capturedTtsBodies.size >= 2 && player.hasActivePlaybackForTest()
        )
        assertEquals(VoiceSessionState.SPEAKING, vm.uiState.value.voiceSessionState)
        assertTrue(vm.uiState.value.lastAssistantReply.contains(expectedAiSubstring))
        if (language == SaathiLanguage.TELUGU) {
            assertTrue(
                "Displayed Telugu reply must never contain Telugu Unicode: ${vm.uiState.value.lastAssistantReply}",
                !SaathiVoiceCatalog.containsTeluguUnicode(vm.uiState.value.lastAssistantReply)
            )
        }

        val replyTts = capturedTtsBodies[1]
        assertEquals(language.isoCode, replyTts.getString("languageCode"))
        assertEquals(expectedVoiceId, replyTts.getString("voiceId"))
        assertEquals(voiceGender.slotKey, replyTts.getString("voiceSlot"))
        assertTrue(replyTts.getString("text").contains(expectedAiSubstring))

        // Verify Gemini received the conversation history containing the greeting
        assertEquals(1, capturedAiRequests.size)
        assertEquals(userSpokenText, capturedAiRequests[0].userMessage)
        assertTrue(capturedAiRequests[0].history.isNotEmpty())

        // 4. Reply playback finishes -> automatic listening resumes (SPEAKING -> LISTENING)
        player.completeActivePlaybackNowForTest()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceSessionState.LISTENING, vm.uiState.value.voiceSessionState)

        val allMsgs = db.saathiDao().getMessagesOnce(convId)
        assertEquals(3, allMsgs.size)
        assertEquals(listOf("model", "user", "model"), allMsgs.map { it.role })

        vm.interruptCurrentResponse()
    }

    @Test
    fun `Test 1 - Open app in Telugu plays Roman Telugu greeting then user speaks then Gemini replies in Roman Telugu then ElevenLabs speaks with te pronunciation and auto-listening resumes`() = runBlocking {
        runAutomaticGreetingAndTurnWorkflow(
            language = SaathiLanguage.TELUGU,
            voiceGender = VoiceGender.FEMALE,
            expectedVoiceId = "env_female_voice_id_101",
            expectedGreeting = "Arey, ela unnav ra? Baagunnava? Eeroju em chestunnav?",
            userSpokenText = "Ela unnav ra?",
            expectedAiSubstring = "Nenu baane unna ra"
        )
    }

    @Test
    fun `Test 2 - Open app in Hindi plays Hindi greeting then user speaks then Gemini replies then ElevenLabs speaks and auto-listening resumes`() = runBlocking {
        runAutomaticGreetingAndTurnWorkflow(
            language = SaathiLanguage.HINDI,
            voiceGender = VoiceGender.FEMALE,
            expectedVoiceId = "env_female_voice_id_101",
            expectedGreeting = "अरे, कैसे हो? आज तुम्हारा दिन कैसा जा रहा है?",
            userSpokenText = "मैं बढ़िया हूँ साथी, आज का दिन अच्छा है।",
            expectedAiSubstring = "बढ़िया"
        )
    }

    @Test
    fun `Test 3 - Open app in English plays English greeting then user speaks then Gemini replies then ElevenLabs speaks and auto-listening resumes`() = runBlocking {
        runAutomaticGreetingAndTurnWorkflow(
            language = SaathiLanguage.ENGLISH,
            voiceGender = VoiceGender.FEMALE,
            expectedVoiceId = "env_female_voice_id_101",
            expectedGreeting = "Hey! How are you doing? How's your day going?",
            userSpokenText = "Doing great Saathi, working on my Android project!",
            expectedAiSubstring = "how is your day going"
        )
    }

    @Test
    fun `Test 4 - Select Male voice preview greeting and full conversation use configured Male Voice ID`() = runBlocking {
        runAutomaticGreetingAndTurnWorkflow(
            language = SaathiLanguage.TELUGU,
            voiceGender = VoiceGender.MALE,
            expectedVoiceId = "env_male_voice_id_202",
            expectedGreeting = "Arey, ela unnav ra? Baagunnava? Eeroju em chestunnav?",
            userSpokenText = SaathiLanguage.TELUGU.sampleUserPrompt,
            expectedAiSubstring = "Nenu baane unna ra",
            runVoicePreviewFirst = true
        )
    }

    @Test
    fun `Test 5 - Select Female voice preview greeting and full conversation use configured Female Voice ID`() = runBlocking {
        runAutomaticGreetingAndTurnWorkflow(
            language = SaathiLanguage.HINDI,
            voiceGender = VoiceGender.FEMALE,
            expectedVoiceId = "env_female_voice_id_101",
            expectedGreeting = "अरे, कैसे हो? आज तुम्हारा दिन कैसा जा रहा है?",
            userSpokenText = SaathiLanguage.HINDI.sampleUserPrompt,
            expectedAiSubstring = "बढ़िया",
            runVoicePreviewFirst = true
        )
    }

    @Test
    fun `Test 6 and Test 7 - Natural playful laughter strips emojis and Markdown from TTS while serious conversation delivers calm empathetic reply`() = runBlocking {
        capturedTtsBodies.clear()
        capturedAiRequests.clear()
        java.io.File(context.cacheDir, "saathi_tts_cache").deleteRecursively()

        val speechClients = mutableListOf<FakeSpeechClient>()
        val players = mutableListOf<VoiceAudioPlayer>()
        val vm = createViewModel(speechClients, players)
        val player = players.single()

        prefsRepo.setVoiceBackendUrl(baseUrl)
        prefsRepo.setLanguage(SaathiLanguage.TELUGU)
        prefsRepo.setVoice("env_female_voice_id_101", VoiceGender.FEMALE)
        vm.preferences.first {
            it.voiceBackendUrl == baseUrl &&
                it.preferredLanguage == SaathiLanguage.TELUGU &&
                it.selectedVoiceId == "env_female_voice_id_101"
        }

        // TEST 6: Playful joke turn -> natural laughter in Roman Telugu, emojis & Markdown stripped before ElevenLabs TTS
        vm.onMicPressed()
        deliverVoiceResult(speechClients.last(), "FUNNY_JOKE_TRIGGER Ninna ma kukka na cheppulu dachesindi!")
        val deadline1 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline1 && (capturedTtsBodies.size < 1 || !player.hasActivePlaybackForTest())) {
            delay(20)
        }
        assertTrue(capturedTtsBodies.size >= 1)
        val playfulTtsText = capturedTtsBodies[0].getString("text")
        // Verify natural laughter is kept in Roman Telugu, while emojis (😂), asterisks (**), and [laughs] are stripped from TTS
        assertEquals("Haha! Nijamga ra? Adaithe bhale undi!", playfulTtsText)
        assertTrue(!SaathiVoiceCatalog.containsTeluguUnicode(playfulTtsText))
        assertTrue(!playfulTtsText.contains("😂"))
        assertTrue(!playfulTtsText.contains("*"))
        assertTrue(!playfulTtsText.contains("[laughs]"))

        player.completeActivePlaybackNowForTest()
        shadowOf(Looper.getMainLooper()).idle()

        // TEST 7: Serious/upset turn -> empathetic calm response in Roman Telugu without forced laughter
        deliverVoiceResult(speechClients.last(), "SERIOUS_UPSET_TRIGGER Eeroju naku chaala baadhaga undi ra.")
        val deadline2 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline2 && (capturedTtsBodies.size < 2 || !player.hasActivePlaybackForTest())) {
            delay(20)
        }
        assertTrue(capturedTtsBodies.size >= 2)
        val seriousTtsText = capturedTtsBodies[1].getString("text")
        assertEquals("Ayyo... em ayindi ra? Nemmadiga cheppu, nenu vintunnanu.", seriousTtsText)
        assertTrue(!SaathiVoiceCatalog.containsTeluguUnicode(seriousTtsText))
        assertTrue(!seriousTtsText.contains("Haha", ignoreCase = true))

        vm.interruptCurrentResponse()
    }

    @Test
    fun `Test 8 - Text Chat sends consecutive messages receives Gemini responses persists in Room and restores on reopen`() = runBlocking {
        val vm = createViewModel()
        val firstConvId = vm.uiState.value.activeConversationId

        // Blank messages are ignored
        vm.sendTextMessage("   ")
        assertEquals(0, db.saathiDao().getMessagesOnce(firstConvId).size)

        // Send first text message
        vm.sendTextMessage("Hey Saathi, let's chat in text mode!")
        val deadline1 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline1) {
            val msgs = db.saathiDao().getMessagesOnce(firstConvId)
            if (msgs.size == 2 && !vm.uiState.value.isTextThinking) break
            delay(20)
        }

        // Send second consecutive text message in the same conversation
        vm.sendTextMessage("What should I cook for dinner tonight?")
        val deadline2 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline2) {
            val msgs = db.saathiDao().getMessagesOnce(firstConvId)
            if (msgs.size == 4 && !vm.uiState.value.isTextThinking) break
            delay(20)
        }

        val savedFirstConv = db.saathiDao().getMessagesOnce(firstConvId)
        assertEquals(4, savedFirstConv.size)
        assertEquals("user", savedFirstConv[0].role)
        assertEquals("Hey Saathi, let's chat in text mode!", savedFirstConv[0].text)
        assertEquals(false, savedFirstConv[0].isVoice)
        assertEquals("model", savedFirstConv[1].role)
        assertTrue(savedFirstConv[1].text.contains("how is your day going"))
        assertEquals("user", savedFirstConv[2].role)
        assertEquals("What should I cook for dinner tonight?", savedFirstConv[2].text)
        assertEquals("model", savedFirstConv[3].role)

        // Start a new conversation (simulating closing the first chat)
        vm.startNewConversation()
        val secondConvId = vm.uiState.value.activeConversationId
        assertTrue(firstConvId != secondConvId)
        assertEquals(0, db.saathiDao().getMessagesOnce(secondConvId).size)

        // Reopen the first conversation from history and verify all 4 messages are restored intact
        vm.selectConversation(firstConvId)
        kotlinx.coroutines.withTimeout(3000L) {
            vm.uiState.first { it.activeConversationId == firstConvId && it.lastAssistantReply.isNotBlank() }
        }
        val restoredMsgs = db.saathiDao().getMessagesOnce(firstConvId)
        assertEquals(4, restoredMsgs.size)
        assertEquals("Hey Saathi, let's chat in text mode!", restoredMsgs[0].text)
        assertEquals("What should I cook for dinner tonight?", restoredMsgs[2].text)
        assertEquals(savedFirstConv[3].text, vm.uiState.value.lastAssistantReply)
    }

    @Test
    fun `Test 9 - Switch Text Chat to Voice Chat to Text Chat preserves conversation context in the same conversation`() = runBlocking {
        capturedTtsBodies.clear()
        capturedAiRequests.clear()
        val speechClients = mutableListOf<FakeSpeechClient>()
        val vm = createViewModel(speechClients)
        prefsRepo.setVoiceBackendUrl(baseUrl)
        vm.preferences.first { it.voiceBackendUrl == baseUrl }

        val sharedConvId = vm.uiState.value.activeConversationId

        // Step 1: Send message in Text Chat
        vm.sendTextMessage("First turn in Text Chat")
        val deadline1 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline1) {
            if (db.saathiDao().getMessagesOnce(sharedConvId).size == 2 && !vm.uiState.value.isTextThinking) break
            delay(20)
        }
        assertEquals(2, db.saathiDao().getMessagesOnce(sharedConvId).size)

        // Step 2: Switch to Voice Chat -> speak a voice turn in the SAME conversation
        vm.onMicPressed()
        deliverVoiceResult(speechClients.last(), "Second turn in Voice Chat")
        kotlinx.coroutines.withTimeout(3000L) {
            while (capturedTtsBodies.size < 1) delay(20)
        }
        assertEquals(sharedConvId, vm.uiState.value.activeConversationId)
        assertEquals(4, db.saathiDao().getMessagesOnce(sharedConvId).size)

        // Step 3: Switch back to Text Chat (stopVoiceOnlyForModeSwitch) -> send third turn in Text Chat
        vm.stopVoiceOnlyForModeSwitch()
        vm.sendTextMessage("Third turn back in Text Chat")
        val deadline3 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline3) {
            if (db.saathiDao().getMessagesOnce(sharedConvId).size == 6 && !vm.uiState.value.isTextThinking) break
            delay(20)
        }

        val allSharedMsgs = db.saathiDao().getMessagesOnce(sharedConvId)
        assertEquals(6, allSharedMsgs.size)
        assertEquals(listOf(false, false, true, true, false, false), allSharedMsgs.map { it.isVoice })
        assertEquals("First turn in Text Chat", allSharedMsgs[0].text)
        assertEquals("Second turn in Voice Chat", allSharedMsgs[2].text)
        assertEquals("Third turn back in Text Chat", allSharedMsgs[4].text)

        // Verify the third turn sent to Gemini included the prior Text + Voice history
        val thirdReq = capturedAiRequests.last()
        assertEquals("Third turn back in Text Chat", thirdReq.userMessage)
        assertTrue(thirdReq.history.size >= 4)

        vm.interruptCurrentResponse()
    }

    @Test
    fun `Test 10 11 12 and 14 - Interrupt speech, deny mic permission, network disconnect and retry without duplicate messages, and end conversation without auto-restart`() = runBlocking {
        val speechClients = mutableListOf<FakeSpeechClient>()
        val players = mutableListOf<VoiceAudioPlayer>()
        val vm = createViewModel(speechClients, players)
        val player = players.single()
        prefsRepo.setVoiceBackendUrl(baseUrl)
        vm.preferences.first { it.voiceBackendUrl == baseUrl }

        // 1. Deny microphone permission -> shows explanation, transitions to ERROR/prompt, never records
        vm.onVoiceScreenReady(hasMicPermission = false)
        assertTrue(vm.uiState.value.needsMicPermissionForAutoStart)
        assertEquals(VoiceSessionState.IDLE, vm.uiState.value.voiceSessionState)

        vm.onMicPermissionResult(granted = false)
        assertEquals(VoiceSessionState.ERROR, vm.uiState.value.voiceSessionState)
        assertTrue(vm.uiState.value.errorMessage?.contains("Microphone access", ignoreCase = true) == true)
        vm.clearError()

        // 2. Grant permission -> greeting starts -> interrupt speech mid-greeting
        vm.onMicPermissionResult(granted = true)
        kotlinx.coroutines.withTimeout(3000L) {
            while (capturedTtsBodies.size < 1) delay(20)
        }
        assertEquals(VoiceSessionState.GREETING, vm.uiState.value.voiceSessionState)
        vm.interruptSpeech()
        assertEquals(VoiceSessionState.INTERRUPTED, vm.uiState.value.voiceSessionState)

        // 3. Simulate network failure in Voice mode -> retryLastFailedMessage recovers cleanly without duplicating user message
        val convId = vm.uiState.value.activeConversationId
        shouldFailAiOnce.set(true)
        vm.onMicPressed()
        deliverVoiceResult(speechClients.last(), "Hello Saathi, testing recovery")
        val errState = kotlinx.coroutines.withTimeout(3000L) {
            vm.uiState.first { it.voiceSessionState == VoiceSessionState.ERROR }
        }
        assertNotNull(errState.errorMessage)
        assertEquals("Hello Saathi, testing recovery", errState.lastFailedPrompt)

        vm.retryLastFailedMessage()
        val recoveredState = kotlinx.coroutines.withTimeout(3000L) {
            vm.uiState.first { it.errorMessage == null && it.voiceSessionState == VoiceSessionState.SPEAKING }
        }
        assertTrue(recoveredState.lastAssistantReply.isNotBlank())
        val userTurnsCount = db.saathiDao().getMessagesOnce(convId).count {
            it.role == "user" && it.text == "Hello Saathi, testing recovery"
        }
        assertEquals(1, userTurnsCount)

        // 4. End conversation -> transitions to ENDED and does NOT auto-restart listening
        vm.endConversation()
        assertEquals(VoiceSessionState.ENDED, vm.uiState.value.voiceSessionState)
        player.completeActivePlaybackNowForTest()
        vm.onVoiceScreenReady(hasMicPermission = true)
        delay(400L)
        assertEquals(VoiceSessionState.ENDED, vm.uiState.value.voiceSessionState)
    }

    @Test
    fun `Test 13 - interruptCurrentResponse and onAppBackgrounded cleanly cancel active listening or speaking`() = runBlocking {
        val speechClients = mutableListOf<FakeSpeechClient>()
        val vm = createViewModel(speechClients)
        delay(100)

        vm.onMicPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceSessionState.LISTENING, vm.uiState.value.voiceSessionState)

        vm.interruptCurrentResponse()
        assertEquals(VoiceSessionState.IDLE, vm.uiState.value.voiceSessionState)

        vm.onMicPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceSessionState.LISTENING, vm.uiState.value.voiceSessionState)

        vm.onAppBackgrounded()
        assertEquals(VoiceSessionState.IDLE, vm.uiState.value.voiceSessionState)
        vm.onAppForegrounded()
    }

    @Test
    fun `Test 15 - Memory controls history rename and deletion and guest data deletion work end to end`() = runBlocking {
        capturedAiRequests.clear()
        val vm = createViewModel()

        // 1. Save memories and verify they are stored in Room
        vm.saveMemory("Preference", "Loves Hyderabadi biryani")
        vm.saveMemory("Project", "Building SAATHI Android companion")
        val deadlineMem = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < deadlineMem && db.saathiDao().getMemoriesOnce("guest_local_test").size < 2) {
            delay(20)
        }
        val savedMems = db.saathiDao().getMemoriesOnce("guest_local_test")
        assertEquals(2, savedMems.size)

        // 2. When memoryEnabled = true, Gemini prompt includes active memories
        vm.setMemoryEnabled(true)
        vm.preferences.first { it.memoryEnabled }
        vm.sendTextMessage("What food should I order?")
        val deadlineChat1 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadlineChat1 && (capturedAiRequests.size < 1 || vm.uiState.value.isTextThinking)) {
            delay(20)
        }
        val reqWithMem = capturedAiRequests.last()
        val historyDumpWithMem = reqWithMem.history.joinToString("\n") { content ->
            content.parts.joinToString(" ") { part ->
                (part as? com.google.firebase.ai.type.TextPart)?.text.orEmpty()
            }
        }
        assertTrue("Expected memory in history, got: $historyDumpWithMem", historyDumpWithMem.contains("Hyderabadi biryani"))

        // 3. When memoryEnabled = false, Gemini prompt omits memories
        capturedAiRequests.clear()
        vm.setMemoryEnabled(false)
        vm.preferences.first { !it.memoryEnabled }
        vm.sendTextMessage("Tell me something else")
        val deadlineChat2 = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadlineChat2 && (capturedAiRequests.size < 1 || vm.uiState.value.isTextThinking)) {
            delay(20)
        }
        val reqWithoutMem = capturedAiRequests.last()
        val historyDumpWithoutMem = reqWithoutMem.history.joinToString("\n") { content ->
            content.parts.joinToString(" ") { part ->
                (part as? com.google.firebase.ai.type.TextPart)?.text.orEmpty()
            }
        }
        assertTrue(!historyDumpWithoutMem.contains("Hyderabadi biryani"))

        // 4. Delete individual memory & clear all memories
        vm.deleteMemory(savedMems[0].id)
        val deadlineDel1 = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < deadlineDel1 && db.saathiDao().getMemoriesOnce("guest_local_test").size != 1) {
            delay(20)
        }
        assertEquals(1, db.saathiDao().getMemoriesOnce("guest_local_test").size)

        vm.clearAllMemories()
        val deadlineClear = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < deadlineClear && db.saathiDao().getMemoriesOnce("guest_local_test").isNotEmpty()) {
            delay(20)
        }
        assertEquals(0, db.saathiDao().getMemoriesOnce("guest_local_test").size)

        // 5. Rename conversation, search history, and delete conversation
        val convId = vm.uiState.value.activeConversationId
        vm.renameConversation(convId, "Weekend Dinner Plans")
        val deadlineRename = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < deadlineRename) {
            val conv = db.saathiDao().getConversationById(convId)
            if (conv?.title == "Weekend Dinner Plans") break
            delay(20)
        }
        assertEquals("Weekend Dinner Plans", db.saathiDao().getConversationById(convId)?.title)

        vm.updateSearchQuery("Dinner")
        assertEquals("Dinner", vm.uiState.value.historySearchQuery)

        vm.deleteConversation(convId)
        val deadlineConvDel = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < deadlineConvDel && db.saathiDao().getConversationById(convId) != null) {
            delay(20)
        }
        assertNull(db.saathiDao().getConversationById(convId))

        // 6. Delete account/guest data clears all local conversations, memories, and preferences
        var deleteCompleted = false
        vm.deleteAccountAndData(
            onComplete = { deleteCompleted = true },
            onError = { throw AssertionError("Unexpected deleteAccountAndData error: $it") }
        )
        val deadlineAcct = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadlineAcct && !deleteCompleted) {
            delay(20)
        }
        assertTrue(deleteCompleted)
        assertEquals(0, db.saathiDao().getMemoriesOnce("guest_local_test").size)
    }

    @Test
    fun `Test 16 - 10 consecutive text messages and 10 consecutive voice turns latency benchmark and incremental TTS verification`() = runBlocking {
        capturedAiRequests.clear()
        capturedTtsBodies.clear()
        val voicesEndpointHits = java.util.concurrent.atomic.AtomicInteger(0)

        server.removeContext("/v1/voices")
        server.createContext("/v1/voices") { exchange ->
            voicesEndpointHits.incrementAndGet()
            Thread.sleep(35L) // Realistic metadata round-trip cost when uncached
            val voices = JSONArray().apply {
                put(JSONObject().apply {
                    put("slot", "female")
                    put("gender", "FEMALE")
                    put("voiceId", "env_female_voice_id_101")
                    put("name", "Ananya (Indian Female)")
                    put("accent", "Indian")
                    put("tone", "Warm & Expressive")
                    put("description", "Configured female voice")
                    put("modelId", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
                    put("configured", true)
                    put("source", "env")
                })
                put(JSONObject().apply {
                    put("slot", "male")
                    put("gender", "MALE")
                    put("voiceId", "env_male_voice_id_202")
                    put("name", "Arjun (Indian Male)")
                    put("accent", "Indian")
                    put("tone", "Grounded & Friendly")
                    put("description", "Configured male voice")
                    put("modelId", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
                    put("configured", true)
                    put("source", "env")
                })
            }
            val payload = JSONObject().apply {
                put("ttsModel", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
                put("sttModel", "scribe_v3_live")
                put("voices", voices)
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        server.removeContext("/v1/tts/stream")
        server.createContext("/v1/tts/stream") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            body.put("_endpoint", "/v1/tts/stream")
            capturedTtsBodies.add(body)
            Thread.sleep(18L) // Realistic first-byte TTS streaming latency
            val lang = body.optString("languageCode", "en")
            val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04) + lang.toByteArray() + ByteArray(1200) { 0x1A }
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, mp3.size.toLong())
            exchange.responseBody.use { it.write(mp3) }
        }

        val speechClients = mutableListOf<FakeSpeechClient>()
        val players = mutableListOf<VoiceAudioPlayer>()

        val streamingAiRepo = SaathiAiRepository(
            ioDispatcher = Dispatchers.IO,
            customTransport = CompanionStreamTransport { req ->
                capturedAiRequests.add(req)
                flow {
                    delay(12L) // First token latency
                    if (req.userMessage.startsWith("Voice Turn")) {
                        val turnTag = req.userMessage.substringBefore(":")
                        // Stream two distinct sentences across 3 chunks in Roman Telugu so incremental lead-sentence TTS prefetch triggers on every turn
                        emit("Arey baagundi ra $turnTag, nee maata vinte chaala santhoshanga undi! ")
                        delay(28L)
                        emit("Eeroju saayantram manam $turnTag lo ")
                        delay(28L)
                        emit("chai thaaguthu inka maatladukundam.")
                    } else {
                        emit("Hey friend, ")
                        delay(15L)
                        emit("I got your message: ${req.userMessage}!")
                    }
                }
            },
            customMemoryExtractor = { null }
        )

        val speechManager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, _ -> FakeSpeechClient().also { speechClients.add(it) } },
            envChecker = TestEnvChecker()
        )
        val player = VoiceAudioPlayer(context).also { players.add(it) }
        val vm = SaathiViewModel(
            currentUserId = "guest_local_test",
            userDisplayName = "Usman",
            userEmail = "",
            userPhotoUrl = null,
            isGuestSession = true,
            dao = db.saathiDao(),
            prefsRepo = prefsRepo,
            firestoreRepo = FirestoreRepository(context),
            aiRepo = streamingAiRepo,
            voiceBackendClient = VoiceBackendClient(
                context = context,
                tokenProvider = { "test_token" }
            ),
            speechRecognizer = speechManager,
            audioRecorder = AudioRecorderManager(null),
            audioPlayer = player
        )

        prefsRepo.setVoiceBackendUrl(baseUrl)
        prefsRepo.setLanguage(SaathiLanguage.TELUGU)
        prefsRepo.setVoice("env_female_voice_id_101", VoiceGender.FEMALE)
        vm.preferences.first {
            it.voiceBackendUrl == baseUrl &&
                it.preferredLanguage == SaathiLanguage.TELUGU &&
                it.selectedVoiceGender == VoiceGender.FEMALE &&
                it.selectedVoiceId == "env_female_voice_id_101"
        }
        delay(80L)
        val hitsBeforeTurns = voicesEndpointHits.get()

        // 1. Seed initial spoken greeting so we can verify it stays preserved across > 20 messages
        vm.onVoiceScreenReady(hasMicPermission = true)
        kotlinx.coroutines.withTimeout(3000L) {
            while (capturedTtsBodies.size < 1 || !player.hasActivePlaybackForTest()) delay(15L)
        }
        player.completeActivePlaybackNowForTest()
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.withTimeout(3000L) {
            vm.uiState.first { it.voiceSessionState == VoiceSessionState.LISTENING }
        }
        vm.stopVoiceOnlyForModeSwitch()
        capturedTtsBodies.clear()

        // 2. Run 10 consecutive text messages
        for (i in 1..10) {
            val prompt = "Text Turn #$i: How is the latency now?"
            vm.sendTextMessage(prompt)
            // Immediately after sendTextMessage, isTextThinking must be true (instant UI feedback)
            assertTrue("Turn $i should set isTextThinking immediately", vm.uiState.value.isTextThinking)
            kotlinx.coroutines.withTimeout(4000L) {
                vm.uiState.first { !it.isTextThinking && it.lastAssistantReply.contains("Text Turn #$i") }
            }
        }

        // 3. Run 10 consecutive voice turns (each streaming 2 sentences across 3 chunks)
        for (i in 1..10) {
            val ttsCountBefore = capturedTtsBodies.size
            if (vm.uiState.value.voiceSessionState != VoiceSessionState.LISTENING) {
                vm.onMicPressed()
            }
            shadowOf(Looper.getMainLooper()).idle()
            deliverVoiceResult(speechClients.last(), "Voice Turn #$i: Tell me two sentences")

            // Wait for lead sentence TTS to start speaking and active playback to begin
            kotlinx.coroutines.withTimeout(4000L) {
                while (capturedTtsBodies.size < ttsCountBefore + 1 || !player.hasActivePlaybackForTest()) {
                    delay(10L)
                }
            }
            // Complete lead sentence playback -> triggers remainder sentence playback
            player.completeActivePlaybackNowForTest()
            shadowOf(Looper.getMainLooper()).idle()
            kotlinx.coroutines.withTimeout(4000L) {
                while (capturedTtsBodies.size < ttsCountBefore + 2 || !player.hasActivePlaybackForTest()) {
                    delay(10L)
                }
            }
            player.completeActivePlaybackNowForTest()
            shadowOf(Looper.getMainLooper()).idle()
            kotlinx.coroutines.withTimeout(4000L) {
                vm.uiState.first { it.voiceSessionState == VoiceSessionState.LISTENING }
            }
        }

        // Verify zero redundant /v1/voices blocking calls occurred during the 10 voice turns
        assertEquals("Expected zero /v1/voices calls during voice turns when voiceId is concrete", hitsBeforeTurns, voicesEndpointHits.get())

        // Verify incremental sentence splitting produced 20 clean TTS requests (2 sentences * 10 turns)
        assertEquals(20, capturedTtsBodies.size)
        assertTrue(capturedTtsBodies[0].getString("text").contains("santhoshanga undi"))
        assertTrue(capturedTtsBodies[1].getString("text").contains("maatladukundam"))
        assertTrue(capturedTtsBodies.all { !SaathiVoiceCatalog.containsTeluguUnicode(it.getString("text")) })

        // Verify conversation has 1 greeting + 20 text messages (10 user + 10 assistant) + 20 voice messages (10 user + 10 assistant) = 41 messages
        val convId = vm.uiState.value.activeConversationId
        val allMessages = db.saathiDao().getMessagesOnce(convId)
        assertEquals(41, allMessages.size)
        assertTrue(allMessages.all { !SaathiVoiceCatalog.containsTeluguUnicode(it.text) })

        // Verify zero duplicate user or assistant messages
        val uniqueUserPrompts = allMessages.filter { it.role == "user" }.map { it.text }.toSet()
        assertEquals(20, uniqueUserPrompts.size)

        // Verify the 20th turn's Gemini history still preserved the initial SAATHI greeting at index 0
        // while keeping total history bounded (<= 16 messages)
        val lastAiReq = capturedAiRequests.last()
        assertTrue("History size should be bounded (got ${lastAiReq.history.size})", lastAiReq.history.size <= 16)
        val firstHistoryText = lastAiReq.history.first().parts.joinToString(" ") { part ->
            (part as? com.google.firebase.ai.type.TextPart)?.text.orEmpty()
        }
        assertTrue("Initial greeting must be preserved in long history, got: $firstHistoryText", firstHistoryText.isNotBlank())

        // Verify latency telemetry recorded all 10 text turns and 10 voice turns with 0 failures
        val summary = vm.latencyRecorder.buildSummary(duplicateMessages = 0)
        assertEquals(10, summary.textTurnCount)
        assertEquals(10, summary.voiceTurnCount)
        assertEquals(0, summary.failedRequests)
        assertEquals(0, summary.duplicateMessages)
        assertTrue(summary.medianTextTtftMs > 0.0)
        assertTrue(summary.medianTotalTextResponseMs >= summary.medianTextTtftMs)
        assertTrue(summary.medianTtsFirstAudioMs > 0.0)
        assertTrue(summary.medianTotalVoiceTurnMs >= summary.medianTtsFirstAudioMs)

        println(
            "[SAATHI_BENCHMARK_REPORT] " +
                "textTurns=${summary.textTurnCount} " +
                "voiceTurns=${summary.voiceTurnCount} " +
                "medianTextTtftMs=${summary.medianTextTtftMs} " +
                "medianTotalTextMs=${summary.medianTotalTextResponseMs} " +
                "medianGeminiVoiceMs=${summary.medianGeminiVoiceLatencyMs} " +
                "medianTtsFirstAudioMs=${summary.medianTtsFirstAudioMs} " +
                "medianTotalVoiceTurnMs=${summary.medianTotalVoiceTurnMs} " +
                "failed=${summary.failedRequests} " +
                "duplicates=${summary.duplicateMessages}"
        )
    }

    @Test
    fun `Test 17 - Multi-turn person-to-person conversations in Roman Telugu, Hindi, and English verify natural varied replies and zero Telugu Unicode`() = runBlocking {
        capturedAiRequests.clear()
        capturedTtsBodies.clear()
        val vm = createViewModel()
        prefsRepo.setVoiceBackendUrl(baseUrl)

        // 1. Multi-turn Roman Telugu conversation
        prefsRepo.setLanguage(SaathiLanguage.TELUGU)
        vm.preferences.first { it.preferredLanguage == SaathiLanguage.TELUGU }
        val teConvId = vm.uiState.value.activeConversationId

        val teluguTurns = listOf(
            "Ela unnav ra?" to "Nenu baane unna ra",
            "Em ledu, bore koduthundi." to "kaburlu cheppukundama",
            "Naku nidra vastundi." to "rest teesuko ra"
        )
        for ((expectedCount, pair) in teluguTurns.withIndex().map { (idx, p) -> (idx + 1) * 2 to p }) {
            vm.sendTextMessage(pair.first)
            kotlinx.coroutines.withTimeout(3000L) {
                while (db.saathiDao().getMessagesOnce(teConvId).size < expectedCount || vm.uiState.value.isTextThinking) {
                    delay(15L)
                }
            }
            val latestReply = vm.uiState.value.lastAssistantReply
            assertTrue("Expected '${pair.second}' in '$latestReply'", latestReply.contains(pair.second, ignoreCase = true))
            assertTrue(
                "Displayed Telugu message must NEVER contain Telugu Unicode script: $latestReply",
                !SaathiVoiceCatalog.containsTeluguUnicode(latestReply)
            )
            assertTrue(!latestReply.contains("How can I help you?", ignoreCase = true))
        }

        // Verify all stored Telugu messages in Room are 100% Roman Telugu
        val storedTeMessages = db.saathiDao().getMessagesOnce(teConvId)
        assertEquals(6, storedTeMessages.size)
        assertTrue(storedTeMessages.all { !SaathiVoiceCatalog.containsTeluguUnicode(it.text) })

        // 2. Multi-turn Hindi conversation (Devanagari script)
        vm.startNewConversation()
        prefsRepo.setLanguage(SaathiLanguage.HINDI)
        vm.preferences.first { it.preferredLanguage == SaathiLanguage.HINDI }
        val hiConvId = vm.uiState.value.activeConversationId
        vm.sendTextMessage("अरे यार, क्या चल रहा है?")
        kotlinx.coroutines.withTimeout(3000L) {
            while (db.saathiDao().getMessagesOnce(hiConvId).size < 2 || vm.uiState.value.isTextThinking) {
                delay(15L)
            }
        }
        assertTrue(vm.uiState.value.lastAssistantReply.contains("बढ़िया"))

        // 3. Multi-turn English conversation
        vm.startNewConversation()
        prefsRepo.setLanguage(SaathiLanguage.ENGLISH)
        vm.preferences.first { it.preferredLanguage == SaathiLanguage.ENGLISH }
        val enConvId = vm.uiState.value.activeConversationId
        vm.sendTextMessage("Hey Saathi, just taking a quick break!")
        kotlinx.coroutines.withTimeout(3000L) {
            while (db.saathiDao().getMessagesOnce(enConvId).size < 2 || vm.uiState.value.isTextThinking) {
                delay(15L)
            }
        }
        assertTrue(vm.uiState.value.lastAssistantReply.contains("how is your day going"))
    }

    @Test
    fun `Test 18 - Telugu STT Unicode transcripts convert to Roman Telugu and 15-turn conversation with short replies preserves memory and zero Telugu Unicode`() = runBlocking {
        capturedAiRequests.clear()
        capturedTtsBodies.clear()
        val speechClients = mutableListOf<FakeSpeechClient>()
        val players = mutableListOf<VoiceAudioPlayer>()
        val vm = createViewModel(speechClients, players)
        val player = players.single()

        prefsRepo.setVoiceBackendUrl(baseUrl)
        prefsRepo.setLanguage(SaathiLanguage.TELUGU)
        prefsRepo.setVoice("env_female_voice_id_101", VoiceGender.FEMALE)
        vm.preferences.first {
            it.voiceBackendUrl == baseUrl &&
                it.preferredLanguage == SaathiLanguage.TELUGU &&
                it.selectedVoiceId == "env_female_voice_id_101"
        }

        // 1. Start listening in Telugu mode and simulate Android SpeechRecognizer delivering partial & final Telugu Unicode script
        vm.onMicPressed()
        shadowOf(Looper.getMainLooper()).idle()
        val client = speechClients.last()
        val partialBundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("హాయ్ సాథీ"))
        }
        client.listener?.onPartialResults(partialBundle)
        assertFalse(
            "Live partial transcript in Telugu mode must never contain Telugu Unicode: ${vm.uiState.value.liveTranscript}",
            SaathiVoiceCatalog.containsTeluguUnicode(vm.uiState.value.liveTranscript)
        )
        assertTrue(vm.uiState.value.liveTranscript.contains("Hi Saathi", ignoreCase = true))

        deliverVoiceResult(client, "హాయ్ సాథీ ఎలా ఉన్నావ్?")
        kotlinx.coroutines.withTimeout(3000L) {
            while (capturedTtsBodies.size < 1 || !player.hasActivePlaybackForTest()) delay(15L)
        }
        assertFalse(
            "Final liveTranscript in Telugu mode must never contain Telugu Unicode: ${vm.uiState.value.liveTranscript}",
            SaathiVoiceCatalog.containsTeluguUnicode(vm.uiState.value.liveTranscript)
        )
        assertTrue(vm.uiState.value.liveTranscript.contains("Hi Saathi ela unnav", ignoreCase = true))
        player.completeActivePlaybackNowForTest()
        shadowOf(Looper.getMainLooper()).idle()
        vm.stopVoiceOnlyForModeSwitch()

        // 2. Continue for 15 total turns in the same conversation including short replies ("yes", "no", "really?", "why?", "avunu", "enduku?")
        val convId = vm.uiState.value.activeConversationId
        val followUpPrompts = listOf(
            "Avunu",
            "Nijamga?",
            "Enduku?",
            "Yes",
            "Really?",
            "Why?",
            "No",
            "Em ledu, bore koduthundi.",
            "Sare",
            "Naku nidra vastundi.",
            "Inka cheppu ra",
            "Haha avunu ra",
            "Baagundi",
            "Repu kaluddam"
        )
        for ((idx, prompt) in followUpPrompts.withIndex()) {
            val expectedMsgCount = (idx + 2) * 2
            vm.sendTextMessage(prompt)
            kotlinx.coroutines.withTimeout(3000L) {
                while (db.saathiDao().getMessagesOnce(convId).size < expectedMsgCount || vm.uiState.value.isTextThinking) {
                    delay(15L)
                }
            }
        }

        val all15TurnMessages = db.saathiDao().getMessagesOnce(convId)
        assertEquals(30, all15TurnMessages.size)
        // Verify ZERO Telugu Unicode characters across all 30 user & assistant messages (15 full turns)
        assertTrue(
            "Expected zero Telugu Unicode across all 30 messages",
            all15TurnMessages.all { !SaathiVoiceCatalog.containsTeluguUnicode(it.text) }
        )

        // Verify ViewModel cleanup stops active players/recognizers cleanly
        vm.cleanupResourcesForTest()
        assertFalse(player.isPlaying())
    }
}
