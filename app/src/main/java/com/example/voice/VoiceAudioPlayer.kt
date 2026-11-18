package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import com.example.data.remote.TtsAudioSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe progressive MediaDataSource that feeds MediaPlayer as audio/mpeg chunks arrive
 * from ElevenLabs without waiting for the full stream download to complete.
 */
class ProgressiveStreamMediaDataSource(
    private val streamSource: TtsAudioSource.ProgressiveStream,
    private val onStreamFailure: (String) -> Unit
) : MediaDataSource() {

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val buffer = ByteArrayOutputStream().apply {
        write(streamSource.initialBytes)
    }
    private var snapshot = streamSource.initialBytes
    private var streamCompleted = false
    private var closed = false
    private var failureMessage: String? = null

    private val readerThread = Thread({
        val tempBuf = ByteArray(8192)
        try {
            while (true) {
                val isClosedNow = lock.withLock { closed }
                if (isClosedNow) break

                val read = streamSource.byteStream.read(tempBuf)
                if (read == -1) {
                    val allBytes = lock.withLock {
                        streamCompleted = true
                        val bytes = buffer.toByteArray()
                        snapshot = bytes
                        dataAvailable.signalAll()
                        bytes
                    }
                    runCatching { streamSource.byteStream.close() }
                    runCatching { streamSource.response.close() }
                    streamSource.onStreamPersisted(streamSource.cacheKey, allBytes)
                    return@Thread
                }
                if (read > 0) {
                    lock.withLock {
                        buffer.write(tempBuf, 0, read)
                        snapshot = buffer.toByteArray()
                        dataAvailable.signalAll()
                    }
                }
            }
        } catch (e: Exception) {
            val wasClosed = lock.withLock { closed }
            if (!wasClosed && !streamSource.call.isCanceled()) {
                Log.w(TAG, "Progressive audio stream interrupted: ${e.message}")
                val err = "Voice audio stream was interrupted. Please try again."
                lock.withLock {
                    failureMessage = err
                    streamCompleted = true
                    dataAvailable.signalAll()
                }
                streamSource.onStreamAborted(streamSource.cacheKey)
                onStreamFailure(err)
                return@Thread
            }
        }
        streamSource.onStreamAborted(streamSource.cacheKey)
    }, "saathi-tts-stream-pump").apply {
        isDaemon = true
        start()
    }

    override fun readAt(position: Long, outBuffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (position < 0L) return -1

        lock.withLock {
            while (!closed && position >= snapshot.size && !streamCompleted) {
                dataAvailable.await(150, TimeUnit.MILLISECONDS)
            }
            if (closed || failureMessage != null) return -1
            val current = snapshot
            if (position >= current.size) {
                return -1 // EOF
            }
            val posInt = position.toInt()
            val available = current.size - posInt
            val toCopy = minOf(size, available)
            System.arraycopy(current, posInt, outBuffer, offset, toCopy)
            return toCopy
        }
    }

    override fun getSize(): Long = -1L

    override fun close() {
        val alreadyCompleted = lock.withLock {
            if (closed) return
            closed = true
            dataAvailable.signalAll()
            streamCompleted
        }
        streamSource.closeQuietly()
        if (!alreadyCompleted) {
            streamSource.onStreamAborted(streamSource.cacheKey)
        }
    }

    private companion object {
        const val TAG = "ProgressiveMediaSource"
    }
}

class VoiceAudioPlayer(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sessionCounter = AtomicLong(0L)
    private val isReleasing = AtomicBoolean(false)

    private var mediaPlayer: MediaPlayer? = null
    private var currentDataSource: ProgressiveStreamMediaDataSource? = null
    private var currentTransientFile: File? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var currentVolume: Float = 1.0f

    @Volatile
    private var activeInterruptionCallback: (() -> Unit)? = null

    @Volatile
    private var activeCompletionCallback: (() -> Unit)? = null

    @Volatile
    private var activeFirstAudioReadyCallback: (() -> Unit)? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val interruptCb = activeInterruptionCallback
                stop()
                interruptCb?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val ducked = (currentVolume * 0.25f).coerceIn(0f, 0.3f)
                runCatching { mediaPlayer?.setVolume(ducked, ducked) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val restored = currentVolume.coerceIn(0f, 1f)
                runCatching { mediaPlayer?.setVolume(restored, restored) }
            }
        }
    }

    fun playAudioSource(
        source: TtsAudioSource,
        speed: Float,
        volume: Float,
        onFirstAudioReady: () -> Unit = {},
        onCompletion: () -> Unit,
        onInterrupted: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        when (source) {
            is TtsAudioSource.CachedFile -> {
                playFile(
                    file = source.file,
                    deleteAfterPlay = false,
                    speed = speed,
                    volume = volume,
                    onFirstAudioReady = onFirstAudioReady,
                    onCompletion = onCompletion,
                    onInterrupted = onInterrupted,
                    onError = onError
                )
            }
            is TtsAudioSource.ProgressiveStream -> {
                playProgressiveStream(
                    streamSource = source,
                    speed = speed,
                    volume = volume,
                    onFirstAudioReady = onFirstAudioReady,
                    onCompletion = onCompletion,
                    onInterrupted = onInterrupted,
                    onError = onError
                )
            }
        }
    }

    fun playFile(
        file: File,
        speed: Float,
        volume: Float,
        onCompletion: () -> Unit,
        onError: (String) -> Unit,
        onInterrupted: () -> Unit = {},
        deleteAfterPlay: Boolean = true,
        onFirstAudioReady: () -> Unit = {}
    ) {
        stop()
        val sessionId = sessionCounter.incrementAndGet()
        currentVolume = volume.coerceIn(0f, 1f)
        if (deleteAfterPlay) {
            currentTransientFile = file
        }

        if (!requestAudioFocus()) {
            releasePlayerAndDataSource()
            onInterrupted()
            return
        }

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(buildSpeechAudioAttributes())
                setDataSource(file.absolutePath)
                setVolume(currentVolume, currentVolume)
                configurePlaybackListeners(
                    sessionId = sessionId,
                    player = this,
                    speed = speed,
                    onFirstAudioReady = onFirstAudioReady,
                    onCompletion = onCompletion,
                    onError = onError
                )
            }
            mediaPlayer = player
            activeInterruptionCallback = onInterrupted
            activeCompletionCallback = onCompletion
            activeFirstAudioReadyCallback = onFirstAudioReady
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start file audio playback", e)
            if (sessionId == sessionCounter.get()) {
                releasePlayerAndDataSource()
                onError("Failed to play voice response.")
            }
        }
    }

    private fun playProgressiveStream(
        streamSource: TtsAudioSource.ProgressiveStream,
        speed: Float,
        volume: Float,
        onFirstAudioReady: () -> Unit,
        onCompletion: () -> Unit,
        onInterrupted: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val sessionId = sessionCounter.incrementAndGet()
        currentVolume = volume.coerceIn(0f, 1f)

        if (!requestAudioFocus()) {
            streamSource.closeQuietly()
            streamSource.onStreamAborted(streamSource.cacheKey)
            releasePlayerAndDataSource()
            onInterrupted()
            return
        }

        val dataSource = ProgressiveStreamMediaDataSource(
            streamSource = streamSource,
            onStreamFailure = { msg ->
                // Atomically invalidate sessionId immediately so OnCompletionListener cannot
                // treat a truncated stream's -1 return value as a normal completion.
                if (sessionCounter.compareAndSet(sessionId, sessionId + 1)) {
                    val handleFailure = Runnable {
                        activeFirstAudioReadyCallback = null
                        releasePlayerAndDataSource()
                        onError(msg)
                    }
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        handleFailure.run()
                    } else {
                        mainHandler.post(handleFailure)
                    }
                }
            }
        )
        currentDataSource = dataSource

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(buildSpeechAudioAttributes())
                setDataSource(dataSource)
                setVolume(currentVolume, currentVolume)
                configurePlaybackListeners(
                    sessionId = sessionId,
                    player = this,
                    speed = speed,
                    onFirstAudioReady = onFirstAudioReady,
                    onCompletion = onCompletion,
                    onError = onError
                )
            }
            mediaPlayer = player
            activeInterruptionCallback = onInterrupted
            activeCompletionCallback = onCompletion
            activeFirstAudioReadyCallback = onFirstAudioReady
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming audio playback", e)
            if (sessionCounter.compareAndSet(sessionId, sessionId + 1)) {
                releasePlayerAndDataSource()
                onError("Couldn't stream voice audio right now. Please try again.")
            }
        }
    }

    private fun MediaPlayer.configurePlaybackListeners(
        sessionId: Long,
        player: MediaPlayer,
        speed: Float,
        onFirstAudioReady: () -> Unit,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ) {
        setOnPreparedListener { mp ->
            if (sessionId != sessionCounter.get()) {
                runCatching { mp.release() }
                return@setOnPreparedListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    mp.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.75f, 1.35f))
                }
            }
            val readyCb = activeFirstAudioReadyCallback
            activeFirstAudioReadyCallback = null
            readyCb?.invoke()
            mp.start()
        }
        setOnCompletionListener {
            if (!sessionCounter.compareAndSet(sessionId, sessionId + 1)) return@setOnCompletionListener
            activeFirstAudioReadyCallback = null
            releasePlayerAndDataSource()
            onCompletion()
        }
        setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
            if (!sessionCounter.compareAndSet(sessionId, sessionId + 1)) return@setOnErrorListener true
            activeFirstAudioReadyCallback = null
            releasePlayerAndDataSource()
            onError("Couldn't play voice audio right now. You can still read the reply or try again.")
            true
        }
    }

    internal fun buildSpeechAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    fun updateVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        currentVolume = v
        runCatching { mediaPlayer?.setVolume(v, v) }
    }

    fun isPlaying(): Boolean = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)

    fun stop() {
        sessionCounter.incrementAndGet()
        activeInterruptionCallback = null
        activeCompletionCallback = null
        activeFirstAudioReadyCallback = null
        releasePlayerAndDataSource()
    }

    internal fun hasActivePlaybackForTest(): Boolean = activeCompletionCallback != null

    internal fun completeActivePlaybackNowForTest() {
        val cb = activeCompletionCallback ?: return
        val readyCb = activeFirstAudioReadyCallback
        activeFirstAudioReadyCallback = null
        activeCompletionCallback = null
        sessionCounter.incrementAndGet()
        readyCb?.invoke()
        releasePlayerAndDataSource()
        cb.invoke()
    }

    private fun releasePlayerAndDataSource() {
        if (!isReleasing.compareAndSet(false, true)) return
        try {
            currentDataSource?.let { ds ->
                runCatching { ds.close() }
            }
            currentDataSource = null

            mediaPlayer?.let { mp ->
                runCatching {
                    if (mp.isPlaying) {
                        mp.stop()
                    }
                }
                runCatching { mp.reset() }
                runCatching { mp.release() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Release error: ${e.message}")
        } finally {
            mediaPlayer = null
            currentTransientFile?.delete()
            currentTransientFile = null
            activeInterruptionCallback = null
            activeCompletionCallback = null
            abandonAudioFocus()
            isReleasing.set(false)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(buildSpeechAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
            result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private companion object {
        const val TAG = "VoiceAudioPlayer"
    }
}
