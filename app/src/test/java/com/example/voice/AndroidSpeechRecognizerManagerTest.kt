package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesState
import com.example.model.SaathiLanguage
import com.example.model.VoiceSessionState
import com.example.ui.screens.VoiceCompanionScreen
import com.example.ui.theme.SaathiTheme
import com.example.viewmodel.CompanionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidSpeechRecognizerManagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSpeechRecognizerClient(val useOnDevice: Boolean) : SpeechRecognizerClient {
        var listener: RecognitionListener? = null
        var startedIntent: Intent? = null
        var startCount = 0
        var stopCount = 0
        var cancelCount = 0
        var destroyCount = 0

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(recognizerIntent: Intent) {
            startedIntent = recognizerIntent
            startCount++
        }

        override fun stopListening() {
            stopCount++
        }

        override fun cancel() {
            cancelCount++
        }

        override fun destroy() {
            destroyCount++
        }
    }

    private class FakeEnvironmentChecker(
        var hasPermission: Boolean = true,
        var recognitionAvailable: Boolean = true,
        var onDeviceAvailable: Boolean = false,
        var networkAvailable: Boolean = true
    ) : SpeechEnvironmentChecker {
        override fun hasRecordAudioPermission(context: Context): Boolean = hasPermission
        override fun isRecognitionAvailable(context: Context): Boolean = recognitionAvailable
        override fun isOnDeviceRecognitionAvailable(context: Context): Boolean = onDeviceAvailable
        override fun isNetworkAvailable(context: Context): Boolean = networkAvailable
    }

    private fun resultsBundle(vararg items: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(items.toList()))
    }

    @Test
    fun `ERROR_SERVER_DISCONNECTED recreates recognizer retries with backoff and recovers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = mutableListOf<FakeSpeechRecognizerClient>()
        val env = FakeEnvironmentChecker()
        val manager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, useOnDevice ->
                FakeSpeechRecognizerClient(useOnDevice).also { created.add(it) }
            },
            envChecker = env
        )

        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val started = manager.startListening(
            language = SaathiLanguage.TELUGU,
            onReady = {},
            onRmsChanged = {},
            onPartialResult = {},
            onFinalResult = { finals.add(it) },
            onError = { errors.add(it) }
        )

        assertTrue(started)
        assertEquals(1, created.size)
        val firstClient = created[0]

        // Simulate Android 12+ ERROR_SERVER_DISCONNECTED (11)
        firstClient.listener?.onError(AndroidSpeechRecognizerManager.ERROR_SERVER_DISCONNECTED_CODE)

        // First recognizer must be cleanly cancelled & destroyed before retry
        assertEquals(1, firstClient.cancelCount)
        assertEquals(1, firstClient.destroyCount)
        assertTrue(errors.isEmpty())
        assertTrue(manager.isSessionActive())

        // Advance past first backoff delay (350ms)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

        assertEquals(2, created.size)
        val secondClient = created[1]
        assertEquals(1, secondClient.startCount)

        // Recovered second instance delivers Telugu transcript
        secondClient.listener?.onResults(resultsBundle("హాయ్ సాథీ ఎలా ఉన్నావ్"))

        assertEquals(listOf("హాయ్ సాథీ ఎలా ఉన్నావ్"), finals)
        assertTrue(errors.isEmpty())
        assertFalse(manager.isSessionActive())
        assertEquals(1, secondClient.destroyCount)
    }

    @Test
    fun `exhausted ERROR_SERVER_DISCONNECTED emits friendly message without raw error codes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = mutableListOf<FakeSpeechRecognizerClient>()
        val env = FakeEnvironmentChecker(onDeviceAvailable = true)
        val manager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, useOnDevice ->
                FakeSpeechRecognizerClient(useOnDevice).also { created.add(it) }
            },
            envChecker = env
        )

        val errors = mutableListOf<String>()
        val finals = mutableListOf<String>()

        manager.startListening(
            language = SaathiLanguage.ENGLISH,
            onReady = {},
            onRmsChanged = {},
            onPartialResult = {},
            onFinalResult = { finals.add(it) },
            onError = { errors.add(it) }
        )

        // Attempt 0 fails with code 11 -> waits 350ms -> Attempt 1 fails with code 11 -> waits 800ms -> Attempt 2 (falls back to on-device) fails with code 11
        created[0].listener?.onError(AndroidSpeechRecognizerManager.ERROR_SERVER_DISCONNECTED_CODE)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(2, created.size)
        assertFalse(created[1].useOnDevice)

        created[1].listener?.onError(AndroidSpeechRecognizerManager.ERROR_SERVER_DISCONNECTED_CODE)
        shadowOf(Looper.getMainLooper()).idleFor(850, TimeUnit.MILLISECONDS)
        assertEquals(3, created.size)
        assertTrue(created[2].useOnDevice)

        created[2].listener?.onError(AndroidSpeechRecognizerManager.ERROR_SERVER_DISCONNECTED_CODE)

        assertTrue(finals.isEmpty())
        assertEquals(1, errors.size)
        val errorMsg = errors.first()
        assertFalse("Error message must never expose raw code 11: $errorMsg", errorMsg.contains("11"))
        assertFalse("Error message must never contain 'code': $errorMsg", errorMsg.contains("code", ignoreCase = true))
        assertTrue(errorMsg.contains("Text Chat", ignoreCase = true))
        assertFalse(manager.isSessionActive())
    }

    @Test
    fun `partial transcripts before failure never get faked as final results`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = mutableListOf<FakeSpeechRecognizerClient>()
        val manager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, useOnDevice ->
                FakeSpeechRecognizerClient(useOnDevice).also { created.add(it) }
            },
            envChecker = FakeEnvironmentChecker()
        )

        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()

        manager.startListening(
            language = SaathiLanguage.HINDI,
            onReady = {},
            onRmsChanged = {},
            onPartialResult = { partials.add(it) },
            onFinalResult = { finals.add(it) },
            onError = { errors.add(it) }
        )

        created[0].listener?.onPartialResults(resultsBundle("हाय"))
        created[0].listener?.onError(SpeechRecognizer.ERROR_AUDIO)

        assertEquals(listOf("हाय"), partials)
        assertTrue("Failed session must never promote partial transcript to final", finals.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test
    fun `concurrent startListening calls are prevented and duplicate onResults are suppressed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = mutableListOf<FakeSpeechRecognizerClient>()
        val manager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, useOnDevice ->
                FakeSpeechRecognizerClient(useOnDevice).also { created.add(it) }
            },
            envChecker = FakeEnvironmentChecker()
        )

        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val firstStarted = manager.startListening(
            language = SaathiLanguage.ENGLISH,
            onReady = {},
            onRmsChanged = {},
            onPartialResult = {},
            onFinalResult = { finals.add(it) },
            onError = { errors.add(it) }
        )
        val secondStarted = manager.startListening(
            language = SaathiLanguage.ENGLISH,
            onReady = {},
            onRmsChanged = {},
            onPartialResult = {},
            onFinalResult = { finals.add(it) },
            onError = { errors.add(it) }
        )

        assertTrue(firstStarted)
        assertFalse(secondStarted)
        assertEquals(1, created.size)

        // Simulate buggy SpeechRecognizer firing onResults twice + a trailing error 11
        val listener = created[0].listener!!
        listener.onResults(resultsBundle("Tell me a story"))
        listener.onResults(resultsBundle("Tell me a story"))
        listener.onError(AndroidSpeechRecognizerManager.ERROR_SERVER_DISCONNECTED_CODE)

        assertEquals(listOf("Tell me a story"), finals)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `stale callbacks from cancelled session are ignored when new session starts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = mutableListOf<FakeSpeechRecognizerClient>()
        val manager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, useOnDevice ->
                FakeSpeechRecognizerClient(useOnDevice).also { created.add(it) }
            },
            envChecker = FakeEnvironmentChecker()
        )

        val session1Finals = mutableListOf<String>()
        val session1Errors = mutableListOf<String>()
        manager.startListening(
            language = SaathiLanguage.ENGLISH,
            onReady = {},
            onRmsChanged = {},
            onPartialResult = {},
            onFinalResult = { session1Finals.add(it) },
            onError = { session1Errors.add(it) }
        )
        manager.cancel()

        val session2Finals = mutableListOf<String>()
        val session2Errors = mutableListOf<String>()
        manager.startListening(
            language = SaathiLanguage.TELUGU,
            onReady = {},
            onRmsChanged = {},
            onPartialResult = {},
            onFinalResult = { session2Finals.add(it) },
            onError = { session2Errors.add(it) }
        )

        // Delayed callbacks arrive on stale first recognizer
        created[0].listener?.onError(AndroidSpeechRecognizerManager.ERROR_SERVER_DISCONNECTED_CODE)
        created[0].listener?.onResults(resultsBundle("stale transcript"))

        assertTrue(session1Finals.isEmpty())
        assertTrue(session1Errors.isEmpty())
        assertTrue(session2Errors.isEmpty())

        created[1].listener?.onResults(resultsBundle("కొత్త సందేశం"))
        assertEquals(listOf("కొత్త సందేశం"), session2Finals)
    }

    @Test
    fun `telugu hindi and english intents set exact locale tags and calling package`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SaathiLanguage.entries.forEach { language ->
            val created = mutableListOf<FakeSpeechRecognizerClient>()
            val manager = AndroidSpeechRecognizerManager(
                context = context,
                recognizerFactory = { _, useOnDevice ->
                    FakeSpeechRecognizerClient(useOnDevice).also { created.add(it) }
                },
                envChecker = FakeEnvironmentChecker()
            )
            manager.startListening(
                language = language,
                onReady = {},
                onRmsChanged = {},
                onPartialResult = {},
                onFinalResult = {},
                onError = {}
            )
            val intent = created.single().startedIntent
            assertNotNull(intent)
            assertEquals(language.localeTag, intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertEquals(language.localeTag, intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
            assertEquals(true, intent?.getBooleanExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false))
            assertEquals(true, intent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            assertEquals(context.packageName, intent?.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
        }
    }

    @Test
    fun `missing permission offline without onDevice and unavailable service fail gracefully`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Missing permission
        val noPermEnv = FakeEnvironmentChecker(hasPermission = false)
        var createdClient: FakeSpeechRecognizerClient? = null
        val noPermErrors = mutableListOf<String>()
        val noPermManager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, d -> FakeSpeechRecognizerClient(d).also { createdClient = it } },
            envChecker = noPermEnv
        )
        assertFalse(
            noPermManager.startListening(SaathiLanguage.ENGLISH, {}, {}, {}, {}, { noPermErrors.add(it) })
        )
        assertNull(createdClient)
        assertTrue(noPermErrors.single().contains("permission", ignoreCase = true))

        // 2. Offline & no on-device support
        val offlineErrors = mutableListOf<String>()
        val offlineManager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, d -> FakeSpeechRecognizerClient(d).also { createdClient = it } },
            envChecker = FakeEnvironmentChecker(networkAvailable = false, onDeviceAvailable = false)
        )
        assertFalse(
            offlineManager.startListening(SaathiLanguage.HINDI, {}, {}, {}, {}, { offlineErrors.add(it) })
        )
        assertNull(createdClient)
        assertTrue(offlineErrors.single().contains("offline", ignoreCase = true))

        // 3. Offline WITH on-device support uses on-device recognizer
        val onDeviceManager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, d -> FakeSpeechRecognizerClient(d).also { createdClient = it } },
            envChecker = FakeEnvironmentChecker(networkAvailable = false, onDeviceAvailable = true)
        )
        assertTrue(onDeviceManager.startListening(SaathiLanguage.ENGLISH, {}, {}, {}, {}, {}))
        assertTrue(createdClient?.useOnDevice == true)
        assertEquals(true, createdClient?.startedIntent?.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    @Test
    fun `backgrounding releases recognizer cancels retry and blocks background listening`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = mutableListOf<FakeSpeechRecognizerClient>()
        val manager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, d -> FakeSpeechRecognizerClient(d).also { created.add(it) } },
            envChecker = FakeEnvironmentChecker()
        )

        val errors = mutableListOf<String>()
        assertTrue(manager.startListening(SaathiLanguage.TELUGU, {}, {}, {}, {}, { errors.add(it) }))
        // Trigger error 11 so a delayed retry runnable is pending
        created[0].listener?.onError(AndroidSpeechRecognizerManager.ERROR_SERVER_DISCONNECTED_CODE)

        // App goes to background during backoff window
        manager.onAppBackgrounded()
        shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS)

        // Pending retry must NOT spawn a second recognizer in background
        assertEquals(1, created.size)
        assertTrue(errors.isEmpty())
        assertFalse(manager.isSessionActive())

        // Foreground return re-enables voice listening cleanly
        manager.onAppForegrounded()
        assertTrue(manager.startListening(SaathiLanguage.TELUGU, {}, {}, {}, {}, { errors.add(it) }))
        assertEquals(2, created.size)
    }

    @Test
    fun `VoiceCompanionScreen displays friendly error controls including Try Again and Text Chat fallback`() {
        var textChatClicks = 0
        var dismissClicks = 0

        composeRule.setContent {
            SaathiTheme {
                VoiceCompanionScreen(
                    uiState = CompanionUiState(
                        voiceSessionState = VoiceSessionState.ERROR,
                        errorMessage = "The device voice service disconnected. Tap the mic to try again, or switch to Text Chat anytime."
                    ),
                    preferences = UserPreferencesState(),
                    onMicClick = {},
                    onLanguageSelected = {},
                    onEndConversation = {},
                    onToggleMute = {},
                    onOpenTextChat = { textChatClicks++ },
                    onOpenVoiceSelect = {},
                    onOpenSettings = {},
                    onOpenMemories = {},
                    onOpenHistory = {},
                    onAcceptMemory = {},
                    onDismissMemory = {},
                    onRetryError = {},
                    onDismissError = { dismissClicks++ }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("retry_mic_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("error_text_chat_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("dismiss_error_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Tap to talk").performScrollTo().assertIsDisplayed()

        assertEquals(1, textChatClicks)
        assertEquals(1, dismissClicks)
    }

    @Test
    fun `silence timeout, no match, and network errors emit friendly messages and release recognizer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases = listOf(
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to "hear anything",
            SpeechRecognizer.ERROR_NO_MATCH to "catch that",
            SpeechRecognizer.ERROR_NETWORK to "internet connection"
        )
        for ((errorCode, expectedPhrase) in cases) {
            val created = mutableListOf<FakeSpeechRecognizerClient>()
            val errors = mutableListOf<String>()
            val manager = AndroidSpeechRecognizerManager(
                context = context,
                recognizerFactory = { _, d -> FakeSpeechRecognizerClient(d).also { created.add(it) } },
                envChecker = FakeEnvironmentChecker()
            )
            assertTrue(manager.startListening(SaathiLanguage.TELUGU, {}, {}, {}, {}, { errors.add(it) }))
            created.single().listener?.onError(errorCode)

            assertFalse(manager.isSessionActive())
            assertEquals(1, created.single().destroyCount)
            assertEquals(1, errors.size)
            assertTrue(
                "Expected error message to mention '$expectedPhrase', got '${errors.single()}'",
                errors.single().contains(expectedPhrase, ignoreCase = true)
            )
        }
    }

    @Test
    fun `switching language cancels previous session and restarts with exact new locale tag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = mutableListOf<FakeSpeechRecognizerClient>()
        val manager = AndroidSpeechRecognizerManager(
            context = context,
            recognizerFactory = { _, d -> FakeSpeechRecognizerClient(d).also { created.add(it) } },
            envChecker = FakeEnvironmentChecker()
        )

        val teluguPartials = mutableListOf<String>()
        val hindiFinals = mutableListOf<String>()
        val englishFinals = mutableListOf<String>()

        assertTrue(manager.startListening(SaathiLanguage.TELUGU, {}, {}, { teluguPartials.add(it) }, {}, {}))
        assertEquals("te-IN", created[0].startedIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

        // Switch Telugu -> Hindi
        manager.cancel()
        assertEquals(1, created[0].cancelCount)
        assertEquals(1, created[0].destroyCount)

        assertTrue(manager.startListening(SaathiLanguage.HINDI, {}, {}, {}, { hindiFinals.add(it) }, {}))
        assertEquals("hi-IN", created[1].startedIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

        // Late partial from old Telugu recognizer must be ignored
        created[0].listener?.onPartialResults(resultsBundle("ఎలా ఉన్నావ్"))
        assertTrue(teluguPartials.isEmpty())

        created[1].listener?.onResults(resultsBundle("कैसे हो दोस्त"))
        assertEquals(listOf("कैसे हो दोस्त"), hindiFinals)

        // Switch Hindi -> English
        assertTrue(manager.startListening(SaathiLanguage.ENGLISH, {}, {}, {}, { englishFinals.add(it) }, {}))
        assertEquals("en-IN", created[2].startedIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        created[2].listener?.onResults(resultsBundle("Hey Saathi"))
        assertEquals(listOf("Hey Saathi"), englishFinals)
    }

    @Test
    fun `VoiceCompanionScreen displays live Roman Telugu transcript and triggers disposal cleanup`() {
        var disposedCount = 0
        val showScreen = androidx.compose.runtime.mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            SaathiTheme {
                if (showScreen.value) {
                    VoiceCompanionScreen(
                        uiState = CompanionUiState(
                            voiceSessionState = VoiceSessionState.LISTENING,
                            liveTranscript = "Ela unnav ra? Baagunnava?"
                        ),
                        preferences = UserPreferencesState(preferredLanguage = SaathiLanguage.TELUGU),
                        onMicClick = {},
                        onLanguageSelected = {},
                        onEndConversation = {},
                        onToggleMute = {},
                        onOpenTextChat = {},
                        onOpenVoiceSelect = {},
                        onOpenSettings = {},
                        onOpenMemories = {},
                        onOpenHistory = {},
                        onAcceptMemory = {},
                        onDismissMemory = {},
                        onRetryError = {},
                        onDismissError = {},
                        onVoiceScreenDisposed = { disposedCount++ }
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(120L)

        composeRule.onNodeWithText("I'm listening...").assertIsDisplayed()
        composeRule.onNodeWithText("Ela unnav ra? Baagunnava?").assertIsDisplayed()

        composeRule.runOnUiThread { showScreen.value = false }
        composeRule.mainClock.advanceTimeBy(120L)
        assertEquals(1, disposedCount)
    }
}
