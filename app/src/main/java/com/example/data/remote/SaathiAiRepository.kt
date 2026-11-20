package com.example.data.remote

import android.util.Log
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.model.MemorySuggestion
import com.example.model.SaathiLanguage
import com.example.model.SaathiVoiceCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal enum class CompanionThinkingLevel {
    LOW,
    MINIMAL
}

internal data class CompanionTurn(
    val role: String,
    val text: String
)

internal data class CompanionStreamRequest(
    val backendUrl: String,
    val modelName: String,
    val thinkingLevel: CompanionThinkingLevel,
    val requestTimeoutMs: Long,
    val systemInstruction: String,
    val history: List<CompanionTurn>,
    val userMessage: String,
    val maxOutputTokens: Int,
    val languageCode: String
)

internal fun interface CompanionStreamTransport {
    fun streamReply(request: CompanionStreamRequest): Flow<String>
}

class SaathiAiRepository internal constructor(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val backendUrlProvider: () -> String = { "" },
    private val clientTokenProvider: () -> String = { "" },
    private val primaryAttemptTimeoutMs: Long = PRIMARY_ATTEMPT_TIMEOUT_MS,
    private val fallbackAttemptTimeoutMs: Long = FALLBACK_ATTEMPT_TIMEOUT_MS,
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
    private val customTransport: CompanionStreamTransport? = null,
    private val customMemoryExtractor: (suspend (String) -> String?)? = null,
    private val httpClient: OkHttpClient = SHARED_AI_HTTP_CLIENT,
    private val nanoClock: () -> Long = { System.nanoTime() }
) {
    constructor(
        backendUrlProvider: () -> String = { "" },
        clientTokenProvider: () -> String = { "" }
    ) : this(
        ioDispatcher = Dispatchers.IO,
        backendUrlProvider = backendUrlProvider,
        clientTokenProvider = clientTokenProvider
    )

    private val systemInstructionCache = ConcurrentHashMap<Pair<SaathiLanguage, Boolean>, String>()
    private val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    fun cancelActiveRequests() {
        val snapshot = activeCalls.toList()
        activeCalls.clear()
        snapshot.forEach { runCatching { it.cancel() } }
    }

    private fun validateBackendUrl(rawUrl: String): String {
        val clean = rawUrl.trim().trimEnd('/')
        if (clean.isBlank()) {
            throw IllegalStateException(BACKEND_NOT_CONFIGURED_MESSAGE)
        }
        val uri = runCatching { URI(clean) }.getOrNull()
            ?: throw IllegalStateException("Backend URL is invalid. Enter a valid HTTPS URL in Voice & Backend Settings.")
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val isLocalLoopback = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
        if (scheme != "https" && !(scheme == "http" && isLocalLoopback)) {
            throw IllegalStateException(
                "For your security, SAATHI requires an HTTPS backend URL (https://...)."
            )
        }
        return clean
    }

    private fun Request.Builder.withClientHeaders(): Request.Builder {
        addHeader("X-Saathi-Client", "saathi-android")
        val token = clientTokenProvider().trim()
        if (token.isNotBlank()) {
            addHeader("X-Saathi-Client-Token", token)
        }
        return this
    }

    private val defaultTransport = CompanionStreamTransport { request ->
        flow {
            val baseUrl = validateBackendUrl(request.backendUrl)
            val payload = JSONObject().apply {
                put("modelName", request.modelName)
                put("thinkingLevel", request.thinkingLevel.name)
                put("systemInstruction", request.systemInstruction)
                put("userMessage", request.userMessage)
                put("maxOutputTokens", request.maxOutputTokens)
                put("languageCode", request.languageCode)
                put(
                    "history",
                    JSONArray().apply {
                        request.history.forEach { turn ->
                            put(
                                JSONObject().apply {
                                    put("role", turn.role)
                                    put("text", turn.text)
                                }
                            )
                        }
                    }
                )
            }.toString()

            val attemptClient = httpClient.newBuilder()
                .readTimeout(request.requestTimeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
                .callTimeout((request.requestTimeoutMs + 4_000L).coerceAtLeast(2_000L), TimeUnit.MILLISECONDS)
                .build()

            var attempt = 0
            while (true) {
                val httpRequest = Request.Builder()
                    .url("$baseUrl/v1/chat/stream")
                    .withClientHeaders()
                    .addHeader("Accept", "text/event-stream")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val call = attemptClient.newCall(httpRequest)
                activeCalls.add(call)
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val code = response.code
                            val retryAfter = response.header("Retry-After")
                            val rawErr = response.body?.string().orEmpty().take(280)
                            val serverErr = runCatching { JSONObject(rawErr).optString("error", "") }.getOrDefault("")

                            val transient = code == 429 || code in 500..504
                            if (transient && attempt < MAX_NETWORK_RETRIES && !call.isCanceled()) {
                                val waitMs = computeRetryDelayMs(attempt, retryAfter)
                                attempt++
                                delay(waitMs)
                                return@use
                            }

                            if (code == 504) {
                                throw SocketTimeoutException(serverErr.ifBlank { "Backend Gemini request timed out." })
                            }
                            if (code == 429 || code in 500..503) {
                                throw TransientBackendException(code, friendlyHttpError(code, serverErr))
                            }
                            throw IllegalStateException(friendlyHttpError(code, serverErr))
                        }

                        val body = response.body
                            ?: throw IOException("Empty response body from SAATHI backend.")

                        val reader = body.charStream().buffered()
                        while (currentCoroutineContext().isActive) {
                            val line = reader.readLine() ?: break
                            val trimmed = line.trim()
                            if (!trimmed.startsWith("data:")) continue
                            val dataPayload = trimmed.removePrefix("data:").trim()
                            if (dataPayload.isEmpty() || dataPayload == "[DONE]") continue
                            val json = runCatching { JSONObject(dataPayload) }.getOrNull() ?: continue
                            val errMsg = json.optString("error", "").trim()
                            if (errMsg.isNotEmpty()) {
                                throw IllegalStateException(errMsg)
                            }
                            val piece = json.optString("text", "")
                            if (piece.isNotEmpty()) {
                                emit(piece)
                            }
                            if (json.optBoolean("done", false)) {
                                break
                            }
                        }
                        return@flow
                    }
                } catch (e: CancellationException) {
                    call.cancel()
                    throw e
                } catch (e: SocketTimeoutException) {
                    throw e
                } catch (e: IOException) {
                    if (call.isCanceled()) {
                        throw CancellationException("Gemini streaming request cancelled.")
                    }
                    if (attempt < MAX_NETWORK_RETRIES) {
                        val waitMs = computeRetryDelayMs(attempt, null)
                        attempt++
                        delay(waitMs)
                        continue
                    }
                    throw e
                } finally {
                    activeCalls.remove(call)
                }
            }
        }
    }

    fun streamCompanionReply(
        userMessage: String,
        history: List<MessageEntity>,
        language: SaathiLanguage,
        preferredName: String,
        memories: List<MemoryEntity>,
        memoryEnabled: Boolean,
        isVoiceMode: Boolean,
        backendUrl: String = backendUrlProvider(),
        onRequestStarted: (Long) -> Unit = {},
        onFirstToken: (Long) -> Unit = {},
        onCompleted: (Long) -> Unit = {}
    ): Flow<String> = flow {
        val systemInstruction = systemInstructionCache.getOrPut(language to isVoiceMode) {
            buildBaseSystemInstruction(
                language = language,
                isVoiceMode = isVoiceMode
            )
        }
        val normalized = normalizeHistoryAndPrompt(
            userMessage = userMessage,
            history = history,
            preferredName = preferredName,
            memories = if (memoryEnabled) memories else emptyList()
        )
        val maxTokens = if (isVoiceMode) VOICE_MAX_OUTPUT_TOKENS else TEXT_MAX_OUTPUT_TOKENS
        val transport = customTransport ?: defaultTransport
        val accumulated = StringBuilder()
        var firstTokenRecorded = false

        try {
            withTimeout(totalTimeoutMs) {
                val primaryRequest = CompanionStreamRequest(
                    backendUrl = backendUrl,
                    modelName = PRIMARY_MODEL_NAME,
                    thinkingLevel = CompanionThinkingLevel.LOW,
                    requestTimeoutMs = primaryAttemptTimeoutMs,
                    systemInstruction = systemInstruction,
                    history = normalized.historyTurns,
                    userMessage = normalized.effectivePrompt,
                    maxOutputTokens = maxTokens,
                    languageCode = language.isoCode
                )

                onRequestStarted(nanoClock())
                var shouldTryFallback = false
                try {
                    withTimeout(primaryAttemptTimeoutMs) {
                        transport.streamReply(primaryRequest).collect { piece ->
                            if (piece.isNotEmpty()) {
                                if (!firstTokenRecorded) {
                                    firstTokenRecorded = true
                                    onFirstToken(nanoClock())
                                }
                                val displayPiece = if (language == SaathiLanguage.TELUGU) {
                                    SaathiVoiceCatalog.ensureRomanTeluguDisplay(piece)
                                } else {
                                    piece
                                }
                                accumulated.append(displayPiece)
                                if (language == SaathiLanguage.TELUGU &&
                                    SaathiVoiceCatalog.containsTeluguUnicode(accumulated.toString())
                                ) {
                                    val normalizedSoFar = SaathiVoiceCatalog.ensureRomanTeluguDisplay(accumulated.toString())
                                    accumulated.setLength(0)
                                    accumulated.append(normalizedSoFar)
                                }
                                emit(accumulated.toString())
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    if (!currentCoroutineContext().isActive || accumulated.isNotEmpty()) {
                        throw e
                    }
                    Log.w(TAG, "Primary model $PRIMARY_MODEL_NAME timed out before first token; trying $FAST_MODEL_NAME", e)
                    shouldTryFallback = true
                } catch (e: SocketTimeoutException) {
                    if (accumulated.isNotEmpty()) throw e
                    Log.w(TAG, "Primary HTTP request timed out on $PRIMARY_MODEL_NAME; trying $FAST_MODEL_NAME", e)
                    shouldTryFallback = true
                } catch (e: TransientBackendException) {
                    if (accumulated.isNotEmpty()) throw e
                    Log.w(TAG, "Primary model transient error (${e.code}); trying $FAST_MODEL_NAME", e)
                    shouldTryFallback = true
                }

                if (shouldTryFallback) {
                    val fallbackRequest = primaryRequest.copy(
                        modelName = FAST_MODEL_NAME,
                        thinkingLevel = CompanionThinkingLevel.MINIMAL,
                        requestTimeoutMs = fallbackAttemptTimeoutMs
                    )
                    withTimeout(fallbackAttemptTimeoutMs) {
                        transport.streamReply(fallbackRequest).collect { piece ->
                            if (piece.isNotEmpty()) {
                                if (!firstTokenRecorded) {
                                    firstTokenRecorded = true
                                    onFirstToken(nanoClock())
                                }
                                val displayPiece = if (language == SaathiLanguage.TELUGU) {
                                    SaathiVoiceCatalog.ensureRomanTeluguDisplay(piece)
                                } else {
                                    piece
                                }
                                accumulated.append(displayPiece)
                                if (language == SaathiLanguage.TELUGU &&
                                    SaathiVoiceCatalog.containsTeluguUnicode(accumulated.toString())
                                ) {
                                    val normalizedSoFar = SaathiVoiceCatalog.ensureRomanTeluguDisplay(accumulated.toString())
                                    accumulated.setLength(0)
                                    accumulated.append(normalizedSoFar)
                                }
                                emit(accumulated.toString())
                            }
                        }
                    }
                }
            }

            if (accumulated.isBlank()) {
                throw IllegalStateException("SAATHI received an empty response. Please try again.")
            }
            onCompleted(nanoClock())
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini streaming timed out", e)
            throw IllegalStateException(TIMEOUT_USER_MESSAGE, e)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Gemini HTTP streaming timed out", e)
            throw IllegalStateException(TIMEOUT_USER_MESSAGE, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientBackendException) {
            Log.e(TAG, "Gemini backend transient failure", e)
            throw IllegalStateException(e.message ?: "SAATHI backend is temporarily busy. Please try again.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming failed", e)
            throw e
        }
    }.flowOn(ioDispatcher)

    suspend fun extractMemoryCandidate(
        userMessage: String,
        existingMemories: List<MemoryEntity>,
        backendUrl: String = backendUrlProvider()
    ): MemorySuggestion? {
        if (userMessage.length < 10) return null
        val existingSummary = existingMemories.take(20).joinToString("; ") { it.content }
        val prompt = """
            Analyze this message from a user talking to their companion SAATHI:
            "$userMessage"

            Already saved memories: [$existingSummary]

            Only set shouldSuggest=true if the user clearly shared a durable personal preference, preferred name, hobby/interest, ongoing project, or helpful life context that is NOT already in saved memories.
            Never suggest passwords, secrets, financial account numbers, or trivial filler ("hi", "how are you").
        """.trimIndent()

        return try {
            val raw = withContext(ioDispatcher) {
                withTimeout(MEMORY_TIMEOUT_MS) {
                    if (customMemoryExtractor != null) {
                        customMemoryExtractor.invoke(prompt)
                    } else {
                        fetchMemoryExtractionFromBackend(backendUrl, prompt)
                    }
                }
            } ?: return null
            val json = JSONObject(raw)
            if (json.optBoolean("shouldSuggest", false)) {
                val category = json.optString("category", "Context").ifBlank { "Context" }
                val content = json.optString("content", "").trim()
                if (content.isNotBlank()) MemorySuggestion(category, content) else null
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Memory candidate extraction skipped: ${e.message}")
            null
        }
    }

    private fun fetchMemoryExtractionFromBackend(rawBackendUrl: String, prompt: String): String? {
        val cleanUrl = rawBackendUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) return null
        val validatedUrl = validateBackendUrl(cleanUrl)
        val payload = JSONObject().put("prompt", prompt).toString()
        val request = Request.Builder()
            .url("$validatedUrl/v1/memory/extract")
            .withClientHeaders()
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = httpClient.newCall(request)
        activeCalls.add(call)
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun computeRetryDelayMs(attempt: Int, retryAfterHeader: String?): Long {
        val headerSecs = retryAfterHeader?.trim()?.toDoubleOrNull()
        if (headerSecs != null && headerSecs > 0.0) {
            return (headerSecs * 1000.0).toLong().coerceIn(300L, 4_000L)
        }
        return if (attempt == 0) 450L else 1_100L
    }

    private fun friendlyHttpError(code: Int, serverMessage: String): String = when (code) {
        400 -> serverMessage.ifBlank { "Invalid request sent to SAATHI backend." }
        401 -> "Backend authorization failed (HTTP 401). Check your backend access configuration."
        426 -> "HTTPS is required by your SAATHI backend. Use an https:// URL."
        429 -> "SAATHI is busy right now (rate limit). Please wait a moment and try again."
        503 -> serverMessage.ifBlank { "Gemini AI service is not configured on the backend server (missing GEMINI_API_KEY)." }
        504 -> "SAATHI took too long to reply. Please try again."
        else -> serverMessage.ifBlank { "SAATHI couldn't reach your backend (HTTP $code). Please try again." }
    }

    private class TransientBackendException(val code: Int, message: String) : IOException(message)

    internal data class NormalizedConversation(
        val historyTurns: List<CompanionTurn>,
        val effectivePrompt: String
    )

    internal fun normalizeHistoryAndPrompt(
        userMessage: String,
        history: List<MessageEntity>,
        preferredName: String,
        memories: List<MemoryEntity>
    ): NormalizedConversation {
        val cleaned = mutableListOf<Pair<String, String>>()

        // Inject lightweight per-turn local profile/memory context cleanly (distinct from permanent on-device Room storage)
        val contextLines = buildList {
            if (preferredName.isNotBlank()) {
                add("User preferred name: \"$preferredName\".")
            }
            if (memories.isNotEmpty()) {
                add(
                    "Active local memories (reference naturally only when helpful, never invent others): " +
                        memories.take(15).joinToString("; ") { "[${it.category}] ${it.content}" }
                )
            }
        }
        if (contextLines.isNotEmpty()) {
            cleaned.add("user" to "[Session Context]\n${contextLines.joinToString("\n")}")
            cleaned.add("model" to "Understood. I will keep this context in mind naturally.")
        }

        // Preserve initial SAATHI spoken greeting even in long conversations (>14 messages)
        val nonBlankHistory = history.filter { it.text.isNotBlank() }
        val recent = if (nonBlankHistory.size > MAX_RECENT_MESSAGES) {
            val firstMsg = nonBlankHistory.first()
            val tail = nonBlankHistory.takeLast(MAX_RECENT_MESSAGES)
            val firstIsGreeting = firstMsg.role == "model" &&
                SaathiLanguage.entries.any { it.spokenGreeting == firstMsg.text.trim() }
            if (firstIsGreeting && tail.firstOrNull()?.id != firstMsg.id) {
                listOf(firstMsg) + tail.drop(1)
            } else {
                tail
            }
        } else {
            nonBlankHistory
        }

        // Merge consecutive same-role entries and ensure leading turn is "user"
        for (msg in recent) {
            val role = if (msg.role == "model") "model" else "user"
            val trimmed = msg.text.trim().take(MAX_SINGLE_HISTORY_MESSAGE_CHARS)
            if (cleaned.isEmpty() && role == "model") {
                val isSpokenGreeting = SaathiLanguage.entries.any { it.spokenGreeting == trimmed }
                if (isSpokenGreeting) {
                    cleaned.add("user" to "[Session Context]\nConversation opened.")
                    cleaned.add("model" to trimmed)
                }
                continue
            }
            val last = cleaned.lastOrNull()
            if (last != null && last.first == role) {
                cleaned[cleaned.lastIndex] = role to "${last.second}\n$trimmed"
            } else {
                cleaned.add(role to trimmed)
            }
        }

        // Fold/deduplicate trailing user message so strict user/model alternation is preserved
        var effectivePrompt = userMessage.trim()
        val trailing = cleaned.lastOrNull()
        if (trailing != null && trailing.first == "user") {
            cleaned.removeAt(cleaned.lastIndex)
            if (trailing.second != effectivePrompt && trailing.second.isNotBlank()) {
                effectivePrompt = "${trailing.second}\n$effectivePrompt"
            }
        }

        val turns = cleaned.map { (role, text) -> CompanionTurn(role = role, text = text) }
        return NormalizedConversation(turns, effectivePrompt)
    }

    private fun buildBaseSystemInstruction(
        language: SaathiLanguage,
        isVoiceMode: Boolean
    ): String {
        val languageDirective = when (language) {
            SaathiLanguage.TELUGU -> """
                CRITICAL LANGUAGE RULE (NATURAL ROMAN TELUGU ONLY):
                - Respond ONLY in natural, colloquial Andhra/Telangana-style everyday conversational Telugu written in English/Roman letters ONLY.
                - NEVER output Telugu Unicode script characters (ఀ-౿). Every word must use Roman letters so displayed text is 100% Roman Telugu while spoken pronunciation stays natural Telugu.
                - Do NOT translate English sentences word-for-word into stiff, formal, or textbook Telugu. Talk like a close friend on a relaxed phone call.
                - Style & tone examples (use for style inspiration only — never repeat as fixed canned templates):
                  * User: "Ela unnav ra?" -> SAATHI: "Nenu baane unna ra 😂 Nuvvu cheppu, em chestunnav?"
                  * User: "Em ledu, bore koduthundi." -> SAATHI: "Ayyo! Mari mana iddaram konchem kaburlu cheppukundama? 😂"
                  * User: "Naku nidra vastundi." -> SAATHI: "Haha, aithe phone pakkana petti konchem rest teesuko ra. Repu malli maatladukundam!"
                  * User: "Nuvvu em chestunnav?" -> SAATHI: "Netho maatladutunna kada ra 😄 Inka em cheyyali cheppu!"
                  * Casual reaction: "Arey, cheppu ra! Em ayindi? 😄"
                  * Short natural reactions when fitting: "Avunaa?", "Haha nijamga?", "Ade kada ra!", "Cheppu, vintunna."
                  * Empathetic / serious / upset: "Ayyo... em ayindi ra? Nemmadiga cheppu, nenu vintunnanu."
                - Use clear phonetic Roman Telugu spelling with natural commas and occasional ellipses (...) so ElevenLabs Telugu TTS pronounces every word smoothly.
                - Do NOT switch into Hindi or full English sentences unless the user explicitly asks.
            """.trimIndent()
            SaathiLanguage.HINDI -> """
                CRITICAL LANGUAGE RULE (NATURAL CONVERSATIONAL HINDI):
                - Respond ONLY in natural, warm, everyday conversational Indian Hindi written in Devanagari script (हिन्दी).
                - Talk like a close, relaxed Indian friend on a phone call (e.g., "अरे यार, बताओ क्या चल रहा है?", "अरे वाह! सुनकर अच्छा लगा यार।").
                - Style & tone examples (for style inspiration only — generate fresh, spontaneous replies):
                  * Playful / funny / light: "अरे वाह! हाहा, सच में? फिर क्या हुआ यार?" or "हाहा, ये तो कमाल हो गया यार!"
                  * Short natural reactions: "अच्छा सच में?", "हाहा सही बात है यार!", "बताओ यार, मैं सुन रहा हूँ।"
                  * Empathetic / caring / serious: "अरे यार... सुनकर बुरा लगा। आराम से बताओ क्या हुआ, मैं यहीं हूँ।"
                - Do NOT use stiff robotic textbook Hindi, and do NOT mix Telugu or English unnecessarily.
            """.trimIndent()
            SaathiLanguage.ENGLISH -> """
                CRITICAL LANGUAGE RULE (NATURAL CONVERSATIONAL ENGLISH):
                - Respond ONLY in natural, warm, relaxed everyday conversational English.
                - Talk like a close friend catching up on a phone call — use natural contractions ("I'm", "you're", "that's", "what's") and everyday rhythm.
                - Style & tone examples (for style inspiration only — generate fresh, spontaneous replies):
                  * Playful / funny / light: "Haha, wait, seriously? No way! What happened next?" or "That's actually hilarious! Haha, tell me more."
                  * Short natural reactions: "Wait, really?", "Haha fair enough!", "Tell me about it, I'm listening."
                  * Empathetic / caring / serious: "Hey... I'm really sorry you're dealing with that. Take your time, tell me what happened."
                - Do NOT switch into Telugu or Hindi unless the user explicitly asks.
            """.trimIndent()
        }

        val lengthGuidance = if (isVoiceMode) {
            "This is a real-time spoken phone-like voice conversation with natural turn-taking. Vary your response length naturally: sometimes give a quick 1-sentence reaction or playful observation, sometimes 1 to 3 short spoken sentences. Use natural pause punctuation (commas and occasional ...), and NEVER use emojis, markdown bullets, asterisks (*), hashtags (#), or bracketed stage directions like [laughs] so speech synthesis sounds completely human."
        } else {
            "Keep the conversation feeling like a relaxed chat with a close friend. Vary your response length naturally (from a quick lively reaction to 1-3 warm sentences, or a clear fuller explanation only when the user asks for details). In text chat you may use an occasional natural emoji (😄, 😂) when the mood is light, and avoid long robotic walls of text or bulleted lists."
        }

        return """
            You are SAATHI ("Your Friend. Your World."), a close, friendly, warm, expressive, and emotionally intelligent AI conversation partner created by Usman.

            1. Core Person-to-Person Personality:
            - Communicate like a close friend talking naturally with another person on a relaxed phone call — NEVER like a customer-service bot or command-driven assistant.
            - Be friendly, warm, expressive, approachable, naturally funny and playful when appropriate, emotionally aware, empathetic, and genuinely curious about what the user says.
            - Feel comfortable with casual everyday banter: joke lightly, tease gently like a friend, share enthusiasm, and react spontaneously.
            - Never claim to actually be a biological human being if asked directly, and never encourage unhealthy isolation from real-world friends and family.

            2. Human Conversational Behaviour:
            - Do NOT make every response a formal answer.
            - Vary response length and structure naturally:
              * Sometimes give a short, genuine reaction ("Avunaa?", "Haha nijamga?", "Ade kada ra!", "Cheppu, vintunna.").
              * Sometimes ask one natural follow-up question when you are genuinely curious.
              * Sometimes share a playful observation or warm thought without asking a question at all.
            - NEVER turn a casual conversation into an interrogation or interview by firing questions on every single turn.
            - Avoid repeating the user's complete sentence back to them.
            - Avoid unnecessary introductions, conclusions, or formal filler.
            - NEVER start or end messages with "How can I help you?", "Hello! How may I assist you today?", "Sure!", "Certainly!", "Of course!", or "As an AI...".
            - Pay close attention to the conversation history so you remember what the user already told you and NEVER repeat the same question.

            3. Short-Reply & Context Continuity:
            - When the user sends a short reply such as "yes", "no", "really?", "why?", "avunu", "kaadu", "nijamga?", "enduku?", "sare", "हाँ", "नहीं", "सच में?", or "क्यों?", interpret it directly in the context of your immediately preceding message or question in the conversation history.
            - Never act confused by a short reply when the preceding turn makes its meaning obvious — continue that exact thread naturally.

            4. Natural Laughter & Emotional Awareness:
            - Include occasional soft laughter ("Haha", "हाहा") ONLY when something is genuinely funny, light, or playful.
            - NEVER laugh in every response, and NEVER laugh during serious, sad, tired, stressed, or emotional moments — respond with calm warmth and empathy instead.

            $languageDirective
            $lengthGuidance
        """.trimIndent()
    }

    internal companion object {
        const val TAG = "SaathiAiRepository"
        const val PRIMARY_MODEL_NAME = "gemini-2.5-flash"
        const val FAST_MODEL_NAME = "gemini-2.5-flash-lite"
        const val VOICE_MAX_OUTPUT_TOKENS = 256
        const val TEXT_MAX_OUTPUT_TOKENS = 512
        const val MAX_RECENT_MESSAGES = 14
        const val MAX_SINGLE_HISTORY_MESSAGE_CHARS = 600
        const val PRIMARY_ATTEMPT_TIMEOUT_MS = 32_000L
        const val FALLBACK_ATTEMPT_TIMEOUT_MS = 25_000L
        const val TOTAL_TIMEOUT_MS = 60_000L
        const val MEMORY_TIMEOUT_MS = 20_000L
        const val MAX_NETWORK_RETRIES = 1
        const val BACKEND_NOT_CONFIGURED_MESSAGE =
            "SAATHI backend URL is not configured yet. Open Voice & Backend Settings and enter your HTTPS backend URL."
        const val TIMEOUT_USER_MESSAGE =
            "Response timed out after 60 seconds. Check your internet connection and tap retry."

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SHARED_AI_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
