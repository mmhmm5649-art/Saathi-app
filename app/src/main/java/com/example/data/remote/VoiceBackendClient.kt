package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.model.BackendVoicesPayload
import com.example.model.ElevenLabsVoiceOption
import com.example.model.SaathiLanguage
import com.example.model.SaathiVoiceCatalog
import com.example.model.VoiceGender
import com.example.model.VoiceLibraryCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

sealed interface VoiceBackendResult<out T> {
    data class Success<T>(val data: T) : VoiceBackendResult<T>
    data class NotConfigured(val message: String) : VoiceBackendResult<Nothing>
    data class Error(val message: String) : VoiceBackendResult<Nothing>
}

sealed interface TtsAudioSource {
    data class CachedFile(val file: File) : TtsAudioSource

    class ProgressiveStream(
        val call: Call,
        val response: Response,
        val initialBytes: ByteArray,
        val byteStream: InputStream,
        val cacheKey: String,
        val onStreamPersisted: (String, ByteArray) -> Unit,
        val onStreamAborted: (String) -> Unit
    ) : TtsAudioSource {
        fun closeQuietly() {
            runCatching { byteStream.close() }
            runCatching { response.close() }
            runCatching { call.cancel() }
        }
    }
}

class VoiceBackendClient(
    private val context: Context,
    private val clientTokenProvider: suspend () -> String? = { null },
    private val nanoClock: () -> Long = { System.nanoTime() }
) {

    private val client = SHARED_HTTP_CLIENT

    private val activeTtsCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var activeSttCall: Call? = null

    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val recentCacheIndex = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val file: File,
        val createdAtMs: Long
    )

    private val ttsCacheDir: File by lazy {
        File(context.cacheDir, "saathi_tts_cache").apply { mkdirs() }
    }

    private fun validateBackendUrl(rawUrl: String): String? {
        val cleanUrl = rawUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) return null
        val uri = runCatching { URI(cleanUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val isLocalLoopback = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
        return if (scheme == "https" || (scheme == "http" && isLocalLoopback)) {
            cleanUrl
        } else {
            null
        }
    }

    private suspend fun Request.Builder.withStandaloneClientHeaders(): Request.Builder {
        addHeader("X-Saathi-Client", "saathi-android")
        val token = runCatching { clientTokenProvider()?.trim() }.getOrNull()
        if (!token.isNullOrBlank()) {
            addHeader("X-Saathi-Client-Token", token)
        }
        return this
    }

    fun cancelActiveTtsRequest() {
        val snapshot = activeTtsCalls.toList()
        activeTtsCalls.clear()
        snapshot.forEach { runCatching { it.cancel() } }
    }

    fun cancelActiveSttRequest() {
        activeSttCall?.cancel()
        activeSttCall = null
    }

    fun clearCachedAudio() {
        recentCacheIndex.clear()
        runCatching {
            ttsCacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun checkHealth(baseUrl: String): VoiceBackendResult<String> = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return@withContext VoiceBackendResult.NotConfigured(
                "Enter your deployed SAATHI Backend HTTPS URL to enable Gemini AI and ElevenLabs speech."
            )
        }
        val cleanUrl = validateBackendUrl(trimmed)
            ?: return@withContext VoiceBackendResult.Error(HTTPS_REQUIRED_MESSAGE)
        try {
            val request = Request.Builder()
                .url("$cleanUrl/health")
                .withStandaloneClientHeaders()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val geminiConfigured = json?.optBoolean("geminiConfigured", true) ?: true
                    val elevenConfigured = json?.optBoolean("elevenLabsConfigured", false) ?: false
                    val ttsModel = json?.optString("ttsModel", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
                        ?: SaathiVoiceCatalog.DEFAULT_TTS_MODEL
                    val sttModel = json?.optString("sttModel", "scribe_v3_live") ?: "scribe_v3_live"
                    when {
                        geminiConfigured && elevenConfigured ->
                            VoiceBackendResult.Success("Connected • Gemini + ElevenLabs ($ttsModel • $sttModel)")
                        !geminiConfigured && !elevenConfigured ->
                            VoiceBackendResult.Error("Backend reachable, but GEMINI_API_KEY and ELEVENLABS_API_KEY are missing on the server.")
                        !geminiConfigured ->
                            VoiceBackendResult.Error("Backend reachable, but GEMINI_API_KEY is missing on the server.")
                        else ->
                            VoiceBackendResult.Error("Backend reachable, but ELEVENLABS_API_KEY is missing on the server.")
                    }
                } else {
                    VoiceBackendResult.Error("Backend returned HTTP ${response.code}.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            VoiceBackendResult.Error(e.localizedMessage ?: "Could not reach backend URL.")
        }
    }

    suspend fun fetchConfiguredVoices(baseUrl: String): VoiceBackendResult<BackendVoicesPayload> =
        withContext(Dispatchers.IO) {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) {
                return@withContext VoiceBackendResult.NotConfigured(
                    "SAATHI backend URL is not configured yet."
                )
            }
            val cleanUrl = validateBackendUrl(trimmed)
                ?: return@withContext VoiceBackendResult.Error(HTTPS_REQUIRED_MESSAGE)

            val request = Request.Builder()
                .url("$cleanUrl/v1/voices")
                .withStandaloneClientHeaders()
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext VoiceBackendResult.Error("Failed to load voices (HTTP ${response.code}).")
                    }
                    VoiceBackendResult.Success(parseBackendVoicesPayload(raw))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "fetchConfiguredVoices failed", e)
                VoiceBackendResult.Error(e.localizedMessage ?: "Failed to load backend voices.")
            }
        }

    suspend fun searchIndianVoiceLibrary(
        baseUrl: String,
        gender: VoiceGender,
        query: String = ""
    ): VoiceBackendResult<List<VoiceLibraryCandidate>> = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return@withContext VoiceBackendResult.NotConfigured("Configure your SAATHI Backend HTTPS URL first.")
        }
        val cleanUrl = validateBackendUrl(trimmed)
            ?: return@withContext VoiceBackendResult.Error(HTTPS_REQUIRED_MESSAGE)

        val encodedSearch = URLEncoder.encode(query.trim(), "UTF-8")
        val request = Request.Builder()
            .url("$cleanUrl/v1/voices/library?gender=${gender.slotKey}&search=$encodedSearch")
            .withStandaloneClientHeaders()
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext VoiceBackendResult.Error("Voice library lookup failed (HTTP ${response.code}).")
                }
                val json = JSONObject(raw)
                val arr = json.optJSONArray("voices")
                val list = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            add(
                                VoiceLibraryCandidate(
                                    voiceId = item.optString("voiceId", ""),
                                    name = item.optString("name", "Indian Voice"),
                                    gender = item.optString("gender", gender.slotKey),
                                    accent = item.optString("accent", "Indian"),
                                    language = item.optString("language", "en, hi, te"),
                                    tone = item.optString("tone", "Conversational"),
                                    description = item.optString("description", ""),
                                    previewUrl = item.optString("previewUrl", ""),
                                    source = item.optString("source", "voice_library")
                                )
                            )
                        }
                    }
                }.filter { it.voiceId.isNotBlank() }
                VoiceBackendResult.Success(list)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "searchIndianVoiceLibrary failed", e)
            VoiceBackendResult.Error(e.localizedMessage ?: "Failed to search Voice Library.")
        }
    }

    suspend fun updateBackendVoiceConfig(
        baseUrl: String,
        gender: VoiceGender,
        voiceId: String,
        voiceName: String
    ): VoiceBackendResult<BackendVoicesPayload> = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return@withContext VoiceBackendResult.NotConfigured("Configure your SAATHI Backend HTTPS URL first.")
        }
        val cleanUrl = validateBackendUrl(trimmed)
            ?: return@withContext VoiceBackendResult.Error(HTTPS_REQUIRED_MESSAGE)

        val cleanVoiceId = SaathiVoiceCatalog.extractVoiceId(voiceId)
        val bodyJson = JSONObject().apply {
            if (gender == VoiceGender.FEMALE) {
                put("femaleVoiceId", cleanVoiceId)
                if (voiceName.isNotBlank()) put("femaleVoiceName", voiceName.trim())
            } else {
                put("maleVoiceId", cleanVoiceId)
                if (voiceName.isNotBlank()) put("maleVoiceName", voiceName.trim())
            }
        }.toString()

        val request = Request.Builder()
            .url("$cleanUrl/v1/voices/config")
            .withStandaloneClientHeaders()
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext VoiceBackendResult.Error("Failed to save voice config (HTTP ${response.code}).")
                }
                clearCachedAudio()
                VoiceBackendResult.Success(parseBackendVoicesPayload(raw))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "updateBackendVoiceConfig failed", e)
            VoiceBackendResult.Error(e.localizedMessage ?: "Failed to save backend voice ID.")
        }
    }

    /**
     * Opens a low-latency streaming audio source (or cached file hit / coalesced in-flight result)
     * with timeout, HTTP 429 Retry-After / 5xx retries, and duplicate request prevention.
     */
    suspend fun openTtsAudioSource(
        baseUrl: String,
        text: String,
        voiceSlot: String,
        voiceId: String,
        modelId: String,
        language: SaathiLanguage,
        speed: Float,
        isPreview: Boolean = false,
        onBackendRequestStart: (Long) -> Unit = {},
        onBackendResponseHeaders: (Long) -> Unit = {},
        onFirstAudioChunk: (Long) -> Unit = {}
    ): VoiceBackendResult<TtsAudioSource> = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return@withContext VoiceBackendResult.NotConfigured(
                "SAATHI secure backend is not configured yet. Configure your HTTPS backend URL in Voice Settings to hear SAATHI speak."
            )
        }
        val cleanUrl = validateBackendUrl(trimmed)
            ?: return@withContext VoiceBackendResult.Error(HTTPS_REQUIRED_MESSAGE)

        val cleanText = SaathiVoiceCatalog.sanitizeSpeechText(text, language)
        if (cleanText.isBlank()) {
            return@withContext VoiceBackendResult.Error("Nothing to speak.")
        }

        val roundedSpeed = (speed.coerceIn(0.75f, 1.25f) * 100f).roundToInt() / 100f
        val requestKey = computeRequestKey(
            baseUrl = cleanUrl,
            text = cleanText,
            voiceSlot = voiceSlot,
            voiceId = voiceId,
            modelId = modelId.ifBlank { SaathiVoiceCatalog.DEFAULT_TTS_MODEL },
            languageCode = language.isoCode,
            speed = roundedSpeed,
            isPreview = isPreview
        )

        // 1. Return recent cached clip if valid (avoids duplicate billing on repeated replay/preview)
        getValidCachedFile(requestKey)?.let { cached ->
            val now = nanoClock()
            onBackendRequestStart(now)
            onBackendResponseHeaders(now)
            onFirstAudioChunk(now)
            return@withContext VoiceBackendResult.Success(TtsAudioSource.CachedFile(cached))
        }

        // 2. Deduplicate concurrent identical requests while already in-flight
        val existingInFlight = inFlightRequests[requestKey]
        if (existingInFlight != null) {
            Log.d(TAG, "Coalescing duplicate TTS request for key=${requestKey.take(10)}")
            val startWait = nanoClock()
            onBackendRequestStart(startWait)
            val sharedFile = try {
                existingInFlight.await()
            } catch (e: CancellationException) {
                throw e
            }
            if (sharedFile != null && sharedFile.exists() && sharedFile.length() > 0L) {
                val doneWait = nanoClock()
                onBackendResponseHeaders(doneWait)
                onFirstAudioChunk(doneWait)
                return@withContext VoiceBackendResult.Success(TtsAudioSource.CachedFile(sharedFile))
            }
        }

        val ownerDeferred = CompletableDeferred<File?>()
        val prev = inFlightRequests.putIfAbsent(requestKey, ownerDeferred)
        if (prev != null) {
            val sharedFile = prev.await()
            if (sharedFile != null && sharedFile.exists() && sharedFile.length() > 0L) {
                return@withContext VoiceBackendResult.Success(TtsAudioSource.CachedFile(sharedFile))
            }
            inFlightRequests[requestKey] = ownerDeferred
        }

        val normalizedVoiceId = SaathiVoiceCatalog.extractVoiceId(voiceId)
        val payload = JSONObject().apply {
            put("text", cleanText)
            put("voiceSlot", voiceSlot)
            if (normalizedVoiceId.isNotBlank() &&
                normalizedVoiceId != SaathiVoiceCatalog.SLOT_FEMALE &&
                normalizedVoiceId != SaathiVoiceCatalog.SLOT_MALE
            ) {
                put("voiceId", normalizedVoiceId)
            }
            put("modelId", modelId.ifBlank { SaathiVoiceCatalog.DEFAULT_TTS_MODEL })
            put("languageCode", language.isoCode)
            put("speed", roundedSpeed.toDouble().coerceIn(0.75, 1.2))
            put("expressive", true)
        }.toString()

        val endpoint = if (isPreview) "$cleanUrl/v1/tts/preview" else "$cleanUrl/v1/tts/stream"

        try {
            onBackendRequestStart(nanoClock())
            val execResult = executeTtsWithRetries(endpoint, payload)
            when (execResult) {
                is TtsHttpOutcome.Failure -> {
                    inFlightRequests.remove(requestKey, ownerDeferred)
                    ownerDeferred.complete(null)
                    execResult.result
                }
                is TtsHttpOutcome.StreamingResponse -> {
                    onBackendResponseHeaders(nanoClock())
                    val response = execResult.response
                    val call = execResult.call
                    val body = response.body
                    if (body == null) {
                        response.close()
                        inFlightRequests.remove(requestKey, ownerDeferred)
                        ownerDeferred.complete(null)
                        return@withContext VoiceBackendResult.Error("Voice backend returned an empty audio stream.")
                    }

                    val inputStream = body.byteStream()
                    val firstChunkBuf = ByteArray(INITIAL_PROBE_BYTES)
                    val firstRead = readAtLeastOneChunk(inputStream, firstChunkBuf)
                    if (firstRead <= 0) {
                        runCatching { inputStream.close() }
                        runCatching { response.close() }
                        inFlightRequests.remove(requestKey, ownerDeferred)
                        ownerDeferred.complete(null)
                        return@withContext VoiceBackendResult.Error("Received empty audio stream from voice backend.")
                    }

                    onFirstAudioChunk(nanoClock())
                    val initialBytes = firstChunkBuf.copyOf(firstRead)
                    val streamSource = TtsAudioSource.ProgressiveStream(
                        call = call,
                        response = response,
                        initialBytes = initialBytes,
                        byteStream = inputStream,
                        cacheKey = requestKey,
                        onStreamPersisted = { key, fullBytes ->
                            val file = persistToCacheFile(key, fullBytes)
                            inFlightRequests.remove(key, ownerDeferred)
                            ownerDeferred.complete(file)
                            activeTtsCalls.remove(call)
                        },
                        onStreamAborted = { key ->
                            inFlightRequests.remove(key, ownerDeferred)
                            ownerDeferred.complete(null)
                            activeTtsCalls.remove(call)
                        }
                    )
                    VoiceBackendResult.Success(streamSource)
                }
            }
        } catch (e: CancellationException) {
            inFlightRequests.remove(requestKey, ownerDeferred)
            ownerDeferred.complete(null)
            throw e
        } catch (e: Exception) {
            inFlightRequests.remove(requestKey, ownerDeferred)
            ownerDeferred.complete(null)
            Log.e(TAG, "openTtsAudioSource failed", e)
            VoiceBackendResult.Error(friendlyNetworkErrorMessage(e))
        }
    }

    /**
     * Downloads and returns a complete cached audio file (ideal for file playback fallback & verification).
     */
    suspend fun synthesizeSpeechToCacheFile(
        baseUrl: String,
        text: String,
        voiceSlot: String,
        voiceId: String,
        modelId: String,
        language: SaathiLanguage,
        speed: Float,
        isPreview: Boolean = false
    ): VoiceBackendResult<File> = withContext(Dispatchers.IO) {
        when (
            val sourceRes = openTtsAudioSource(
                baseUrl = baseUrl,
                text = text,
                voiceSlot = voiceSlot,
                voiceId = voiceId,
                modelId = modelId,
                language = language,
                speed = speed,
                isPreview = isPreview
            )
        ) {
            is VoiceBackendResult.NotConfigured -> sourceRes
            is VoiceBackendResult.Error -> sourceRes
            is VoiceBackendResult.Success -> {
                when (val source = sourceRes.data) {
                    is TtsAudioSource.CachedFile -> {
                        val copy = File.createTempFile("saathi_tts_", ".mp3", context.cacheDir)
                        source.file.copyTo(copy, overwrite = true)
                        VoiceBackendResult.Success(copy)
                    }
                    is TtsAudioSource.ProgressiveStream -> {
                        val outFile = File.createTempFile("saathi_tts_", ".mp3", context.cacheDir)
                        try {
                            val collected = java.io.ByteArrayOutputStream()
                            collected.write(source.initialBytes)
                            val buf = ByteArray(8192)
                            while (true) {
                                val read = source.byteStream.read(buf)
                                if (read == -1) break
                                if (read > 0) collected.write(buf, 0, read)
                            }
                            val allBytes = collected.toByteArray()
                            if (allBytes.isEmpty()) {
                                outFile.delete()
                                source.onStreamAborted(source.cacheKey)
                                return@withContext VoiceBackendResult.Error("Received empty audio stream from voice backend.")
                            }
                            FileOutputStream(outFile).use { it.write(allBytes) }
                            source.onStreamPersisted(source.cacheKey, allBytes)
                            VoiceBackendResult.Success(outFile)
                        } catch (e: CancellationException) {
                            outFile.delete()
                            source.onStreamAborted(source.cacheKey)
                            source.closeQuietly()
                            throw e
                        } catch (e: Exception) {
                            outFile.delete()
                            source.onStreamAborted(source.cacheKey)
                            if (source.call.isCanceled()) {
                                throw CancellationException("TTS request cancelled by user interruption.")
                            }
                            Log.e(TAG, "TTS stream download failed", e)
                            VoiceBackendResult.Error(friendlyNetworkErrorMessage(e))
                        } finally {
                            runCatching { source.byteStream.close() }
                            runCatching { source.response.close() }
                        }
                    }
                }
            }
        }
    }

    private sealed interface TtsHttpOutcome {
        data class StreamingResponse(val call: Call, val response: Response) : TtsHttpOutcome
        data class Failure(val result: VoiceBackendResult<TtsAudioSource>) : TtsHttpOutcome
    }

    private suspend fun executeTtsWithRetries(
        endpoint: String,
        payloadJson: String
    ): TtsHttpOutcome {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(endpoint)
                .withStandaloneClientHeaders()
                .addHeader("Accept", "audio/mpeg")
                .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val call = client.newCall(request)
            activeTtsCalls.add(call)

            try {
                val response = call.execute()
                if (response.isSuccessful) {
                    return TtsHttpOutcome.StreamingResponse(call = call, response = response)
                }

                val code = response.code
                val retryAfterHeader = response.header("Retry-After")
                val errBody = response.body?.string().orEmpty().take(240)
                response.close()
                activeTtsCalls.remove(call)

                val shouldRetry = (code == 429 || code in 500..504) && attempt < MAX_TTS_RETRIES && !call.isCanceled()
                if (shouldRetry) {
                    val waitMs = computeRetryDelayMs(attempt, retryAfterHeader)
                    Log.w(TAG, "TTS HTTP $code; retrying attempt=${attempt + 1} in ${waitMs}ms")
                    attempt++
                    delay(waitMs)
                    continue
                }

                return TtsHttpOutcome.Failure(VoiceBackendResult.Error(friendlyHttpErrorMessage(code, errBody)))
            } catch (e: CancellationException) {
                activeTtsCalls.remove(call)
                throw e
            } catch (e: IOException) {
                activeTtsCalls.remove(call)
                if (call.isCanceled()) {
                    throw CancellationException("TTS request cancelled by user interruption.")
                }
                if (attempt < MAX_TTS_RETRIES) {
                    val waitMs = computeRetryDelayMs(attempt, null)
                    Log.w(TAG, "TTS IO failure (${e.javaClass.simpleName}); retrying attempt=${attempt + 1} in ${waitMs}ms")
                    attempt++
                    delay(waitMs)
                    continue
                }
                return TtsHttpOutcome.Failure(VoiceBackendResult.Error(friendlyNetworkErrorMessage(e)))
            }
        }
    }

    private fun readAtLeastOneChunk(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total == 0) {
            val read = stream.read(buffer, 0, buffer.size)
            if (read == -1) return -1
            if (read > 0) total = read
        }
        return total
    }

    private fun computeRetryDelayMs(attempt: Int, retryAfterHeader: String?): Long {
        val headerSecs = retryAfterHeader?.trim()?.toDoubleOrNull()
        if (headerSecs != null && headerSecs > 0.0) {
            return (headerSecs * 1000.0).toLong().coerceIn(300L, 4000L)
        }
        return if (attempt == 0) 500L else 1200L
    }

    private fun friendlyHttpErrorMessage(code: Int, rawDetail: String): String {
        val extracted = runCatching { JSONObject(rawDetail).optString("error", "") }.getOrDefault("")
        return when (code) {
            401 -> "Backend authorization failed (HTTP 401). Check your backend access token configuration."
            422 -> extracted.ifBlank { "No ElevenLabs Voice ID is configured yet. Select or configure an Indian voice in Voice Settings." }
            426 -> HTTPS_REQUIRED_MESSAGE
            429 -> "Voice service is busy right now (rate limit). Please try again in a few seconds."
            503 -> extracted.ifBlank { "ElevenLabs voice service is not configured on the backend server." }
            504 -> "Voice synthesis timed out on the server. Please try again."
            else -> extracted.ifBlank { "Couldn't generate voice audio right now. Please try again." }
        }
    }

    private fun friendlyNetworkErrorMessage(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Voice synthesis timed out. Check your internet connection and try again."
        else -> "Couldn't reach your voice server right now. Check your connection or continue in Text Chat."
    }

    private fun computeRequestKey(
        baseUrl: String,
        text: String,
        voiceSlot: String,
        voiceId: String,
        modelId: String,
        languageCode: String,
        speed: Float,
        isPreview: Boolean
    ): String {
        val raw = "$baseUrl|$voiceSlot|$voiceId|$modelId|$languageCode|$speed|$isPreview|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getValidCachedFile(key: String): File? {
        val entry = recentCacheIndex[key] ?: return null
        val expired = (System.currentTimeMillis() - entry.createdAtMs) > CACHE_TTL_MS
        if (expired || !entry.file.exists() || entry.file.length() <= 0L) {
            recentCacheIndex.remove(key, entry)
            entry.file.delete()
            return null
        }
        return entry.file
    }

    private fun persistToCacheFile(key: String, bytes: ByteArray): File? {
        if (bytes.isEmpty() || bytes.size > MAX_CACHED_CLIP_BYTES) return null
        return runCatching {
            pruneCacheIfNeeded()
            val file = File(ttsCacheDir, "${key.take(24)}.mp3")
            FileOutputStream(file).use { it.write(bytes) }
            recentCacheIndex[key] = CacheEntry(file = file, createdAtMs = System.currentTimeMillis())
            file
        }.getOrNull()
    }

    private fun pruneCacheIfNeeded() {
        if (recentCacheIndex.size < MAX_CACHE_ENTRIES) return
        val oldest = recentCacheIndex.entries.minByOrNull { it.value.createdAtMs } ?: return
        recentCacheIndex.remove(oldest.key, oldest.value)
        oldest.value.file.delete()
    }

    suspend fun transcribeWithScribe(
        baseUrl: String,
        wavBytes: ByteArray,
        language: SaathiLanguage
    ): VoiceBackendResult<String> = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return@withContext VoiceBackendResult.NotConfigured(
                "ElevenLabs Scribe requires your secure backend URL. Configure it in Voice Settings or switch to Device Speech Recognition."
            )
        }
        val cleanUrl = validateBackendUrl(trimmed)
            ?: return@withContext VoiceBackendResult.Error(HTTPS_REQUIRED_MESSAGE)

        return@withContext executeSttWithRetries(cleanUrl, wavBytes, language)
    }

    private suspend fun executeSttWithRetries(
        cleanUrl: String,
        wavBytes: ByteArray,
        language: SaathiLanguage
    ): VoiceBackendResult<String> {
        var attempt = 0
        while (true) {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("languageCode", language.isoCode)
                .addFormDataPart(
                    "audio",
                    "speech.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$cleanUrl/v1/stt")
                .withStandaloneClientHeaders()
                .post(multipart)
                .build()

            val call = client.newCall(request)
            activeSttCall = call

            try {
                val response = call.execute()
                val raw = response.body?.string().orEmpty()
                val code = response.code
                val retryAfter = response.header("Retry-After")
                val isSuccessful = response.isSuccessful
                response.close()
                if (activeSttCall === call) activeSttCall = null

                if (isSuccessful) {
                    val text = runCatching { JSONObject(raw).optString("text", "") }.getOrDefault("").trim()
                    return if (text.isBlank()) {
                        VoiceBackendResult.Error("Couldn't catch any words. Please try speaking again.")
                    } else {
                        VoiceBackendResult.Success(text)
                    }
                }

                val shouldRetry = (code == 429 || code in 500..504) && attempt < MAX_STT_RETRIES && !call.isCanceled()
                if (shouldRetry) {
                    val waitMs = computeRetryDelayMs(attempt, retryAfter)
                    Log.w(TAG, "Scribe STT HTTP $code; retrying attempt=${attempt + 1} in ${waitMs}ms")
                    attempt++
                    delay(waitMs)
                    continue
                }

                val friendlyMsg = when (code) {
                    401 -> "Backend authorization failed (HTTP 401). Check your backend access token configuration."
                    426 -> HTTPS_REQUIRED_MESSAGE
                    429 -> "Speech recognition is busy right now (rate limit). Please try again in a few seconds."
                    503 -> "ElevenLabs speech recognition is not configured on the backend server."
                    504 -> "Speech recognition timed out on the server. Please try again."
                    else -> "Scribe speech recognition failed right now. Please try again or use Text Chat."
                }
                return VoiceBackendResult.Error(friendlyMsg)
            } catch (e: CancellationException) {
                if (activeSttCall === call) activeSttCall = null
                throw e
            } catch (e: IOException) {
                if (activeSttCall === call) activeSttCall = null
                if (call.isCanceled()) {
                    throw CancellationException("STT request cancelled by user interruption.")
                }
                if (attempt < MAX_STT_RETRIES) {
                    val waitMs = computeRetryDelayMs(attempt, null)
                    Log.w(TAG, "Scribe STT IO failure (${e.javaClass.simpleName}); retrying attempt=${attempt + 1} in ${waitMs}ms")
                    attempt++
                    delay(waitMs)
                    continue
                }
                Log.e(TAG, "Scribe STT request failed", e)
                return VoiceBackendResult.Error(
                    "Speech recognition couldn't reach your voice backend. Check your connection or switch to Text Chat."
                )
            } catch (e: Exception) {
                if (activeSttCall === call) activeSttCall = null
                Log.e(TAG, "Scribe STT request failed", e)
                return VoiceBackendResult.Error(
                    "Speech recognition couldn't reach your voice backend. Please try again."
                )
            }
        }
    }

    private fun parseBackendVoicesPayload(raw: String): BackendVoicesPayload {
        val json = JSONObject(raw)
        val ttsModel = json.optString("ttsModel", SaathiVoiceCatalog.DEFAULT_TTS_MODEL)
        val sttModel = json.optString("sttModel", "scribe_v3_live")
        val arr = json.optJSONArray("voices")
        val list = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val slot = v.optString("slot", SaathiVoiceCatalog.SLOT_FEMALE)
                    val gender = if (slot.equals("male", ignoreCase = true) ||
                        v.optString("gender", "").equals("MALE", ignoreCase = true)
                    ) VoiceGender.MALE else VoiceGender.FEMALE
                    val defaultOption = SaathiVoiceCatalog.defaultForGender(gender)
                    add(
                        ElevenLabsVoiceOption(
                            id = slot,
                            slot = slot,
                            name = v.optString("name", defaultOption.name).ifBlank { defaultOption.name },
                            gender = gender,
                            accent = v.optString("accent", "Indian").ifBlank { "Indian" },
                            tone = v.optString("tone", defaultOption.tone).ifBlank { defaultOption.tone },
                            description = v.optString("description", defaultOption.description).ifBlank { defaultOption.description },
                            modelId = v.optString("modelId", ttsModel).ifBlank { ttsModel },
                            configuredVoiceId = v.optString("voiceId", ""),
                            configured = v.optBoolean("configured", false),
                            source = v.optString("source", "backend"),
                            multilingualNote = "$ttsModel • Consistent across Telugu, Hindi & English"
                        )
                    )
                }
            }
        }
        return BackendVoicesPayload(
            ttsModel = ttsModel,
            sttModel = sttModel,
            voices = if (list.size == 2) list else SaathiVoiceCatalog.voices
        )
    }

    private companion object {
        const val TAG = "VoiceBackendClient"
        const val HTTPS_REQUIRED_MESSAGE =
            "For your security, SAATHI requires an HTTPS backend URL (https://...)."
        const val MAX_TTS_RETRIES = 2
        const val MAX_STT_RETRIES = 1
        const val INITIAL_PROBE_BYTES = 1024
        const val MAX_CACHE_ENTRIES = 8
        const val CACHE_TTL_MS = 180_000L
        const val MAX_CACHED_CLIP_BYTES = 1024 * 1024 // 1 MB

        val SHARED_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .build()
    }
}
