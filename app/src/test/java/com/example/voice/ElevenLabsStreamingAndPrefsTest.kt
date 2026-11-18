package com.example.voice

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.UserPreferencesState
import com.example.data.remote.TtsAudioSource
import com.example.model.ElevenLabsVoiceOption
import com.example.model.VoiceGender
import com.example.ui.screens.VoiceSelectionScreen
import com.example.ui.theme.SaathiTheme
import com.example.viewmodel.CompanionUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ElevenLabsStreamingAndPrefsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun dummyOkHttpCallAndResponse(): Pair<okhttp3.Call, Response> {
        val client = OkHttpClient()
        val request = Request.Builder().url("http://127.0.0.1:1/v1/tts/stream").build()
        val call = client.newCall(request)
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()
        return call to response
    }

    @Test
    fun `ProgressiveStreamMediaDataSource streams chunked audio, persists at EOF, and skips abort on post-EOF close`() {
        val (call, response) = dummyOkHttpCallAndResponse()
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut)

        val initialChunk = byteArrayOf(0x49, 0x44, 0x33, 0x04)
        val secondChunk = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)

        val persistedLatch = CountDownLatch(1)
        val persistedBytes = AtomicReference<ByteArray?>()
        val aborted = AtomicBoolean(false)

        val streamSource = TtsAudioSource.ProgressiveStream(
            call = call,
            response = response,
            initialBytes = initialChunk,
            byteStream = pipedIn,
            cacheKey = "stream_key_eof",
            onStreamPersisted = { _, bytes ->
                persistedBytes.set(bytes)
                persistedLatch.countDown()
            },
            onStreamAborted = { aborted.set(true) }
        )

        val dataSource = ProgressiveStreamMediaDataSource(
            streamSource = streamSource,
            onStreamFailure = {}
        )

        assertEquals(-1L, dataSource.size)

        // Read initial chunk immediately before remaining bytes arrive
        val firstReadBuf = ByteArray(4)
        val n1 = dataSource.readAt(0L, firstReadBuf, 0, 4)
        assertEquals(4, n1)
        assertArrayEquals(initialChunk, firstReadBuf)

        // Write delayed second chunk and close pipe for EOF
        pipedOut.write(secondChunk)
        pipedOut.close()

        assertTrue("Expected stream pump to persist completed bytes", persistedLatch.await(3, TimeUnit.SECONDS))

        val secondReadBuf = ByteArray(5)
        val n2 = dataSource.readAt(4L, secondReadBuf, 0, 5)
        assertEquals(5, n2)
        assertArrayEquals(secondChunk, secondReadBuf)

        // EOF read returns -1
        assertEquals(-1, dataSource.readAt(9L, ByteArray(4), 0, 4))
        assertArrayEquals(initialChunk + secondChunk, persistedBytes.get())

        // Closing after clean EOF must not fire onStreamAborted
        dataSource.close()
        assertFalse(aborted.get())
    }

    @Test
    fun `ProgressiveStreamMediaDataSource close interrupts active stream and triggers onStreamAborted`() {
        val (call, response) = dummyOkHttpCallAndResponse()
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut)

        val persisted = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)

        val streamSource = TtsAudioSource.ProgressiveStream(
            call = call,
            response = response,
            initialBytes = byteArrayOf(1, 2, 3),
            byteStream = pipedIn,
            cacheKey = "stream_key_abort",
            onStreamPersisted = { _, _ -> persisted.set(true) },
            onStreamAborted = { aborted.set(true) }
        )

        val dataSource = ProgressiveStreamMediaDataSource(
            streamSource = streamSource,
            onStreamFailure = {}
        )

        // Interrupt mid-stream
        dataSource.close()

        assertTrue(call.isCanceled())
        assertTrue(aborted.get())
        assertFalse(persisted.get())
        assertEquals(-1, dataSource.readAt(0L, ByteArray(3), 0, 3))
        runCatching { pipedOut.close() }
    }

    @Test
    fun `UserPreferencesRepository preserves custom selected VOICE_ID across reads`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = UserPreferencesRepository(context)
        repo.clearAll()

        repo.setVoice(voiceId = "indian_female_custom_42", gender = VoiceGender.FEMALE)
        val femalePrefs = repo.preferencesFlow.first()
        assertEquals("indian_female_custom_42", femalePrefs.selectedVoiceId)
        assertEquals(VoiceGender.FEMALE, femalePrefs.selectedVoiceGender)

        repo.setVoice(voiceId = "indian_male_custom_88", gender = VoiceGender.MALE)
        val malePrefs = repo.preferencesFlow.first()
        assertEquals("indian_male_custom_88", malePrefs.selectedVoiceId)
        assertEquals(VoiceGender.MALE, malePrefs.selectedVoiceGender)

        repo.clearAll()
    }

    @Test
    fun `VoiceAudioPlayer stops cleanly and triggers onInterrupted on audio focus loss`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowAudioManager = shadowOf(audioManager)
        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val tempMp3 = java.io.File.createTempFile("saathi_focus_test_", ".mp3", context.cacheDir).apply {
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00))
        }
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(tempMp3.absolutePath),
            ShadowMediaPlayer.MediaInfo(1200, 0)
        )

        val player = VoiceAudioPlayer(context)
        var interruptedCount = 0
        var errorMsg: String? = null
        var firstAudioReady = false

        player.playFile(
            file = tempMp3,
            speed = 1.0f,
            volume = 0.8f,
            onFirstAudioReady = { firstAudioReady = true },
            onCompletion = {},
            onError = { errorMsg = it },
            onInterrupted = { interruptedCount++ },
            deleteAfterPlay = false
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("Unexpected MediaPlayer error: $errorMsg", errorMsg)
        assertTrue(firstAudioReady)
        assertTrue(player.isPlaying())

        val lastRequest = shadowAudioManager.lastAudioFocusRequest
        assertTrue(lastRequest != null)

        // Simulate incoming phone call / another assistant stealing transient audio focus
        lastRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(1, interruptedCount)
        assertFalse(player.isPlaying())
        tempMp3.delete()
    }

    @Test
    fun `VoiceSelectionScreen switches Male and Female voices with configured Voice IDs and previews`() {
        val selectedCalls = mutableListOf<Pair<String, VoiceGender>>()
        val previewCalls = mutableListOf<String>()

        val femaleOpt = ElevenLabsVoiceOption(
            id = "female",
            slot = "female",
            name = "Female Voice",
            gender = VoiceGender.FEMALE,
            tone = "Warm",
            description = "Female companion",
            configuredVoiceId = "VoiceFemaleTest99",
            configured = true,
            multilingualNote = "te, hi, en"
        )
        val maleOpt = ElevenLabsVoiceOption(
            id = "male",
            slot = "male",
            name = "Male Voice",
            gender = VoiceGender.MALE,
            tone = "Grounded",
            description = "Male companion",
            configuredVoiceId = "VoiceMaleTest88",
            configured = true,
            multilingualNote = "te, hi, en"
        )

        composeRule.setContent {
            SaathiTheme {
                VoiceSelectionScreen(
                    preferences = UserPreferencesState(selectedVoiceGender = VoiceGender.FEMALE),
                    uiState = CompanionUiState(availableVoices = listOf(femaleOpt, maleOpt)),
                    onBack = {},
                    onSelectVoice = { id, gender -> selectedCalls.add(id to gender) },
                    onPreviewVoice = { slot -> previewCalls.add(slot) },
                    onSpeedChange = {},
                    onVolumeChange = {},
                    onAutoPlayChange = {},
                    onAutoTurnTakingChange = {},
                    onSttProviderChange = {},
                    onTestBackend = {},
                    onSearchLibrary = { _, _ -> },
                    onConfigureBackendVoice = { _, _, _ -> }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_card_male").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("voice_card_female").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("preview_voice_male").performScrollTo().performClick()
        composeRule.onNodeWithTag("preview_voice_female").performScrollTo().performClick()

        assertEquals(
            listOf("VoiceMaleTest88" to VoiceGender.MALE, "VoiceFemaleTest99" to VoiceGender.FEMALE),
            selectedCalls
        )
        assertEquals(listOf("male", "female"), previewCalls)
    }

    @Test
    fun `VoiceAudioPlayer configures USAGE_MEDIA and CONTENT_TYPE_SPEECH for reliable speaker and Bluetooth A2DP routing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = VoiceAudioPlayer(context)
        val attrs = player.buildSpeechAudioAttributes()
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, attrs.usage)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_SPEECH, attrs.contentType)
    }
}
