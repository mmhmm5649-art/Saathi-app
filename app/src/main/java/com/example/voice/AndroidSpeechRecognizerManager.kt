package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.SaathiLanguage
import java.util.concurrent.atomic.AtomicLong

interface SpeechRecognizerClient {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(recognizerIntent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

fun interface SpeechRecognizerFactory {
    fun create(context: Context, useOnDevice: Boolean): SpeechRecognizerClient
}

interface SpeechEnvironmentChecker {
    fun hasRecordAudioPermission(context: Context): Boolean
    fun isRecognitionAvailable(context: Context): Boolean
    fun isOnDeviceRecognitionAvailable(context: Context): Boolean
    fun isNetworkAvailable(context: Context): Boolean
}

object DefaultSpeechRecognizerFactory : SpeechRecognizerFactory {
    override fun create(context: Context, useOnDevice: Boolean): SpeechRecognizerClient {
        val appContext = context.applicationContext
        val delegate = if (
            useOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        return object : SpeechRecognizerClient {
            override fun setRecognitionListener(listener: RecognitionListener) {
                delegate.setRecognitionListener(listener)
            }

            override fun startListening(recognizerIntent: Intent) {
                delegate.startListening(recognizerIntent)
            }

            override fun stopListening() {
                delegate.stopListening()
            }

            override fun cancel() {
                delegate.cancel()
            }

            override fun destroy() {
                delegate.destroy()
            }
        }
    }
}

object DefaultSpeechEnvironmentChecker : SpeechEnvironmentChecker {
    override fun hasRecordAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    override fun isRecognitionAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)

    override fun isOnDeviceRecognitionAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context.applicationContext)

    override fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }
}

class AndroidSpeechRecognizerManager(
    private val context: Context,
    private val recognizerFactory: SpeechRecognizerFactory = DefaultSpeechRecognizerFactory,
    private val envChecker: SpeechEnvironmentChecker = DefaultSpeechEnvironmentChecker,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    private data class SessionCallbacks(
        val onReady: () -> Unit,
        val onRmsChanged: (Float) -> Unit,
        val onPartialResult: (String) -> Unit,
        val onFinalResult: (String) -> Unit,
        val onError: (String) -> Unit
    )

    private val sessionLock = Any()
    private val sessionCounter = AtomicLong(0L)

    private var activeSessionId: Long = 0L
    private var isSessionActive: Boolean = false
    private var resultDelivered: Boolean = false
    private var isAppForegrounded: Boolean = true
    private var activeCallbacks: SessionCallbacks? = null

    private var currentRecognizer: SpeechRecognizerClient? = null
    private var pendingRetryRunnable: Runnable? = null

    fun isAvailable(): Boolean =
        envChecker.isRecognitionAvailable(context) ||
            envChecker.isOnDeviceRecognitionAvailable(context)

    fun isSessionActive(): Boolean = synchronized(sessionLock) { isSessionActive }

    fun onAppBackgrounded() {
        val hadPendingWork: Boolean
        synchronized(sessionLock) {
            isAppForegrounded = false
            hadPendingWork = isSessionActive || currentRecognizer != null || pendingRetryRunnable != null
            sessionCounter.incrementAndGet()
            activeSessionId = 0L
            isSessionActive = false
            resultDelivered = false
            activeCallbacks = null
        }
        if (hadPendingWork) {
            runOnMainThread {
                clearPendingRetryLocked()
                destroyRecognizerInternal()
            }
        }
    }

    fun onAppForegrounded() {
        synchronized(sessionLock) {
            isAppForegrounded = true
        }
    }

    fun startListening(
        language: SaathiLanguage,
        onReady: () -> Unit,
        onRmsChanged: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val sessionId: Long
        synchronized(sessionLock) {
            if (!isAppForegrounded) {
                Log.w(TAG, "Ignoring startListening while app is backgrounded")
                onError("Return to SAATHI and tap the mic when you're ready to speak.")
                return false
            }
            if (isSessionActive) {
                Log.d(TAG, "Preventing concurrent SpeechRecognizer session")
                return false
            }
            if (!envChecker.hasRecordAudioPermission(context)) {
                onError("Microphone permission is needed to listen. Please allow microphone access or use Text Chat.")
                return false
            }

            val recognitionAvailable = envChecker.isRecognitionAvailable(context)
            val onDeviceAvailable = envChecker.isOnDeviceRecognitionAvailable(context)
            val networkOnline = envChecker.isNetworkAvailable(context)

            if (!recognitionAvailable && !onDeviceAvailable) {
                onError("Speech recognition service isn't available on this device right now. You can continue anytime using Text Chat.")
                return false
            }
            if (!networkOnline && !onDeviceAvailable) {
                onError("You appear to be offline. Connect to the internet for voice input, or switch to Text Chat.")
                return false
            }

            sessionId = sessionCounter.incrementAndGet()
            activeSessionId = sessionId
            isSessionActive = true
            resultDelivered = false
            activeCallbacks = SessionCallbacks(
                onReady = onReady,
                onRmsChanged = onRmsChanged,
                onPartialResult = onPartialResult,
                onFinalResult = onFinalResult,
                onError = onError
            )
        }

        runOnMainThread {
            startAttempt(
                sessionId = sessionId,
                language = language,
                attempt = 0,
                preferOnDevice = false
            )
        }
        return true
    }

    private fun startAttempt(
        sessionId: Long,
        language: SaathiLanguage,
        attempt: Int,
        preferOnDevice: Boolean
    ) {
        synchronized(sessionLock) {
            if (sessionId != activeSessionId || !isSessionActive || resultDelivered || !isAppForegrounded) {
                return
            }
        }

        clearPendingRetryLocked()
        destroyRecognizerInternal()

        val recognitionAvailable = envChecker.isRecognitionAvailable(context)
        val onDeviceAvailable = envChecker.isOnDeviceRecognitionAvailable(context)
        val networkOnline = envChecker.isNetworkAvailable(context)

        if (!envChecker.hasRecordAudioPermission(context)) {
            deliverTerminalError(
                sessionId = sessionId,
                message = "Microphone permission is needed to listen. Please allow microphone access or use Text Chat."
            )
            return
        }
        if (!recognitionAvailable && !onDeviceAvailable) {
            deliverTerminalError(
                sessionId = sessionId,
                message = "Speech recognition service isn't available on this device right now. You can continue anytime using Text Chat."
            )
            return
        }
        if (!networkOnline && !onDeviceAvailable) {
            deliverTerminalError(
                sessionId = sessionId,
                message = "You appear to be offline. Connect to the internet for voice input, or switch to Text Chat."
            )
            return
        }

        val useOnDevice = ((preferOnDevice || !networkOnline || !recognitionAvailable) && onDeviceAvailable)

        try {
            val recognizer = recognizerFactory.create(context.applicationContext, useOnDevice)
            synchronized(sessionLock) {
                if (sessionId != activeSessionId || !isSessionActive || resultDelivered || !isAppForegrounded) {
                    runCatching {
                        recognizer.cancel()
                        recognizer.destroy()
                    }
                    return
                }
                currentRecognizer = recognizer
            }

            recognizer.setRecognitionListener(
                createListener(
                    sessionId = sessionId,
                    language = language,
                    attempt = attempt,
                    useOnDevice = useOnDevice
                )
            )

            val intent = buildRecognizerIntent(language = language, preferOffline = useOnDevice)
            recognizer.startListening(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting SpeechRecognizer", e)
            destroyRecognizerInternal()
            deliverTerminalError(
                sessionId = sessionId,
                message = "Microphone permission is needed to listen. Please allow microphone access or use Text Chat."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer (attempt=$attempt)", e)
            destroyRecognizerInternal()
            if (attempt < MAX_RETRY_ATTEMPTS && canContinueSession(sessionId)) {
                scheduleRetry(
                    sessionId = sessionId,
                    language = language,
                    nextAttempt = attempt + 1,
                    preferOnDevice = false
                )
            } else {
                deliverTerminalError(
                    sessionId = sessionId,
                    message = "Unable to start voice recognition right now. Tap the mic to try again or use Text Chat."
                )
            }
        }
    }

    private fun createListener(
        sessionId: Long,
        language: SaathiLanguage,
        attempt: Int,
        useOnDevice: Boolean
    ): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            val cb = synchronized(sessionLock) {
                if (sessionId == activeSessionId && isSessionActive && !resultDelivered) activeCallbacks else null
            } ?: return
            cb.onReady()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val cb = synchronized(sessionLock) {
                if (sessionId == activeSessionId && isSessionActive && !resultDelivered) activeCallbacks else null
            } ?: return
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
            cb.onRmsChanged(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            val cb = synchronized(sessionLock) {
                if (sessionId == activeSessionId && isSessionActive && !resultDelivered) activeCallbacks else null
            } ?: return
            cb.onRmsChanged(0.1f)
        }

        override fun onError(error: Int) {
            Log.w(
                TAG,
                "SpeechRecognizer onError code=$error attempt=$attempt sessionId=$sessionId lang=${language.localeTag} useOnDevice=$useOnDevice"
            )

            if (!canContinueSession(sessionId)) {
                return
            }

            destroyRecognizerInternal()

            val shouldRetry = isRetryableError(error) &&
                attempt < MAX_RETRY_ATTEMPTS &&
                canContinueSession(sessionId)

            if (shouldRetry) {
                val nextAttempt = attempt + 1
                val tryOnDeviceFallback =
                    error == ERROR_SERVER_DISCONNECTED_CODE &&
                        !useOnDevice &&
                        nextAttempt >= MAX_RETRY_ATTEMPTS &&
                        envChecker.isOnDeviceRecognitionAvailable(context)
                scheduleRetry(
                    sessionId = sessionId,
                    language = language,
                    nextAttempt = nextAttempt,
                    preferOnDevice = tryOnDeviceFallback
                )
                return
            }

            val friendlyMessage = buildFriendlyErrorMessage(error = error, language = language)
            deliverTerminalError(sessionId = sessionId, message = friendlyMessage)
        }

        override fun onResults(results: Bundle?) {
            val cb: SessionCallbacks?
            synchronized(sessionLock) {
                if (sessionId != activeSessionId || !isSessionActive || resultDelivered) {
                    return
                }
                resultDelivered = true
                isSessionActive = false
                cb = activeCallbacks
                activeCallbacks = null
            }

            clearPendingRetryLocked()
            destroyRecognizerInternal()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (best.isBlank()) {
                cb?.onError?.invoke("I didn't catch any words clearly. Tap the mic and try again.")
            } else {
                cb?.onFinalResult?.invoke(best)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val cb = synchronized(sessionLock) {
                if (sessionId == activeSessionId && isSessionActive && !resultDelivered) activeCallbacks else null
            } ?: return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
            if (partial.isNotEmpty()) {
                cb.onPartialResult(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun scheduleRetry(
        sessionId: Long,
        language: SaathiLanguage,
        nextAttempt: Int,
        preferOnDevice: Boolean
    ) {
        val delayMs = RETRY_BACKOFF_MS.getOrElse(nextAttempt - 1) { RETRY_BACKOFF_MS.last() }
        Log.i(TAG, "Scheduling SpeechRecognizer reconnect attempt=$nextAttempt in ${delayMs}ms (sessionId=$sessionId)")

        val retryRunnable = Runnable {
            pendingRetryRunnable = null
            startAttempt(
                sessionId = sessionId,
                language = language,
                attempt = nextAttempt,
                preferOnDevice = preferOnDevice
            )
        }
        clearPendingRetryLocked()
        pendingRetryRunnable = retryRunnable
        mainHandler.postDelayed(retryRunnable, delayMs)
    }

    private fun canContinueSession(sessionId: Long): Boolean = synchronized(sessionLock) {
        sessionId == activeSessionId && isSessionActive && !resultDelivered && isAppForegrounded
    }

    private fun deliverTerminalError(sessionId: Long, message: String) {
        val cb: SessionCallbacks?
        synchronized(sessionLock) {
            if (sessionId != activeSessionId || resultDelivered) {
                return
            }
            isSessionActive = false
            cb = activeCallbacks
            activeCallbacks = null
        }
        clearPendingRetryLocked()
        destroyRecognizerInternal()
        cb?.onError?.invoke(message)
    }

    fun buildRecognizerIntent(language: SaathiLanguage, preferOffline: Boolean = false): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.localeTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    fun stopListening(): Boolean {
        val hasActiveRecognizer = synchronized(sessionLock) {
            isSessionActive && currentRecognizer != null
        }
        if (!hasActiveRecognizer) {
            cancel()
            return false
        }
        runOnMainThread {
            try {
                currentRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "stopListening error: ${e.message}")
            }
        }
        return true
    }

    fun cancel() {
        synchronized(sessionLock) {
            sessionCounter.incrementAndGet()
            activeSessionId = 0L
            isSessionActive = false
            resultDelivered = false
            activeCallbacks = null
        }
        runOnMainThread {
            clearPendingRetryLocked()
            destroyRecognizerInternal()
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun clearPendingRetryLocked() {
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    private fun destroyRecognizerInternal() {
        val toDestroy = synchronized(sessionLock) {
            val ref = currentRecognizer
            currentRecognizer = null
            ref
        } ?: return

        try {
            toDestroy.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel error: ${e.message}")
        }
        try {
            toDestroy.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy error: ${e.message}")
        }
    }

    private fun isRetryableError(error: Int): Boolean =
        error == ERROR_SERVER_DISCONNECTED_CODE || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

    private fun buildFriendlyErrorMessage(error: Int, language: SaathiLanguage): String {
        val networkOnline = envChecker.isNetworkAvailable(context)
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO ->
                "Couldn't access your microphone audio cleanly. Check if another app is using the mic, or use Text Chat."
            SpeechRecognizer.ERROR_CLIENT ->
                "Voice listening was interrupted. Tap the mic to try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is required to talk to SAATHI. Please allow access or use Text Chat."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs a stable internet connection. Please check your connection or use Text Chat."
            SpeechRecognizer.ERROR_NO_MATCH ->
                "I didn't catch that clearly. Tap the mic and try speaking again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "Voice recognition is getting ready. Please tap the mic again in a moment."
            SpeechRecognizer.ERROR_SERVER ->
                "The speech service is temporarily busy. Please try again or continue in Text Chat."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "I didn't hear anything just now. Tap the mic when you're ready to talk."
            ERROR_TOO_MANY_REQUESTS_CODE ->
                "Voice service is getting too many requests right now. Wait a few seconds and try again."
            ERROR_SERVER_DISCONNECTED_CODE ->
                if (!networkOnline) {
                    "You appear to be offline, so the voice service disconnected. Check your internet connection or use Text Chat."
                } else {
                    "The device voice service disconnected. Tap the mic to try again, or switch to Text Chat anytime."
                }
            ERROR_LANGUAGE_NOT_SUPPORTED_CODE ->
                "${language.displayName} voice input isn't supported on this device's speech engine right now. You can use Text Chat or switch speech providers in Voice Settings."
            ERROR_LANGUAGE_UNAVAILABLE_CODE ->
                "${language.displayName} voice data isn't available offline right now. Check your connection or continue in Text Chat."
            ERROR_CANNOT_CHECK_SUPPORT_CODE,
            ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS_CODE ->
                "Voice recognition couldn't check language availability right now. Please try again or use Text Chat."
            else ->
                "Voice input ran into a temporary issue. Tap the mic to try again, or continue in Text Chat."
        }
    }

    companion object {
        private const val TAG = "AndroidSpeechRecognizer"
        private const val MAX_RETRY_ATTEMPTS = 2
        private val RETRY_BACKOFF_MS = longArrayOf(350L, 800L)

        // SDK constants (API 31-33) kept as explicit values for consistent cross-API-level compilation
        const val ERROR_TOO_MANY_REQUESTS_CODE = 10
        const val ERROR_SERVER_DISCONNECTED_CODE = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED_CODE = 12
        const val ERROR_LANGUAGE_UNAVAILABLE_CODE = 13
        const val ERROR_CANNOT_CHECK_SUPPORT_CODE = 14
        const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS_CODE = 15
    }
}
