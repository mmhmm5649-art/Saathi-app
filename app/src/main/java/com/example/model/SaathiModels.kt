package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class SaathiLanguage(
    val displayName: String,
    val nativeName: String,
    val isoCode: String,
    val localeTag: String,
    val badgeCode: String,
    val sampleUserPrompt: String,
    val sampleReply: String,
    val spokenGreeting: String,
    val greetingTitle: String,
    val greetingSubtitle: String
) {
    TELUGU(
        displayName = "Telugu",
        nativeName = "Telugu",
        isoCode = "te",
        localeTag = "te-IN",
        badgeCode = "TE",
        sampleUserPrompt = "Ela unnav ra?",
        sampleReply = "Nenu baane unna ra 😂 Nuvvu cheppu, em chestunnav?",
        spokenGreeting = "Arey, ela unnav ra? Baagunnava? Eeroju em chestunnav?",
        greetingTitle = "Hey! Nenu SAATHI",
        greetingSubtitle = "Nenu ikkade unna ra. Cheppu, em visheshalu?"
    ),
    HINDI(
        displayName = "Hindi",
        nativeName = "हिन्दी",
        isoCode = "hi",
        localeTag = "hi-IN",
        badgeCode = "अ",
        sampleUserPrompt = "अरे यार, क्या हाल है?",
        sampleReply = "अरे! मैं बिल्कुल बढ़िया हूँ यार 😄 तुम बताओ, आज क्या चल रहा है?",
        spokenGreeting = "अरे, कैसे हो? आज तुम्हारा दिन कैसा जा रहा है?",
        greetingTitle = "हाय! मैं SAATHI हूँ",
        greetingSubtitle = "मैं यहीं हूँ यार। बताओ, आज क्या चल रहा है?"
    ),
    ENGLISH(
        displayName = "English",
        nativeName = "English",
        isoCode = "en",
        localeTag = "en-IN",
        badgeCode = "EN",
        sampleUserPrompt = "Hey Saathi, how's it going?",
        sampleReply = "Hey! I'm doing great, haha 😄 Tell me, how's your day going?",
        spokenGreeting = "Hey! How are you doing? How's your day going?",
        greetingTitle = "Hey! I'm SAATHI",
        greetingSubtitle = "I'm right here. Tell me what's going on!"
    )
}

enum class VoiceGender(val displayName: String) {
    MALE("Male"),
    FEMALE("Female");

    val slotKey: String get() = name.lowercase()
}

enum class SttProvider(val displayName: String, val subtitle: String) {
    ANDROID_SPEECH("Device Speech Recognition", "Built-in multilingual voice input (Telugu, Hindi, English)"),
    ELEVENLABS_SCRIBE("ElevenLabs Scribe v3 Live", "Cloud multilingual speech recognition via your secure backend")
}

enum class VoiceSessionState {
    IDLE,
    GREETING,
    LISTENING,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    ERROR,
    ENDED
}

data class ElevenLabsVoiceOption(
    val id: String,
    val slot: String,
    val name: String,
    val gender: VoiceGender,
    val accent: String = "Indian",
    val tone: String,
    val description: String,
    val modelId: String = "eleven_v4_turbo",
    val configuredVoiceId: String = "",
    val configured: Boolean = false,
    val source: String = "backend",
    val supportedLanguages: List<SaathiLanguage> = listOf(
        SaathiLanguage.TELUGU,
        SaathiLanguage.HINDI,
        SaathiLanguage.ENGLISH
    ),
    val multilingualNote: String
)

data class VoiceLibraryCandidate(
    val voiceId: String,
    val name: String,
    val gender: String,
    val accent: String,
    val language: String,
    val tone: String,
    val description: String,
    val previewUrl: String,
    val source: String
)

data class BackendVoicesPayload(
    val ttsModel: String,
    val sttModel: String,
    val voices: List<ElevenLabsVoiceOption>
)

object SaathiVoiceCatalog {
    const val SLOT_FEMALE = "female"
    const val SLOT_MALE = "male"
    const val DEFAULT_TTS_MODEL = "eleven_v4_turbo"

    // Logical voice slots keep the selected voice identity stable across Telugu, Hindi, and English.
    // Actual ElevenLabs Voice Library IDs are resolved from backend settings or /v1/voices.
    val voices: List<ElevenLabsVoiceOption> = listOf(
        ElevenLabsVoiceOption(
            id = SLOT_FEMALE,
            slot = SLOT_FEMALE,
            name = "SAATHI Female (Indian)",
            gender = VoiceGender.FEMALE,
            accent = "Indian",
            tone = "Warm, Expressive & Natural",
            description = "Natural Indian female companion voice tuned for clear Telugu, Hindi, and English.",
            modelId = DEFAULT_TTS_MODEL,
            multilingualNote = "Eleven v4 Turbo • Consistent identity across te, hi, en"
        ),
        ElevenLabsVoiceOption(
            id = SLOT_MALE,
            slot = SLOT_MALE,
            name = "SAATHI Male (Indian)",
            gender = VoiceGender.MALE,
            accent = "Indian",
            tone = "Warm, Grounded & Friendly",
            description = "Natural Indian male companion voice tuned for clear Telugu, Hindi, and English.",
            modelId = DEFAULT_TTS_MODEL,
            multilingualNote = "Eleven v4 Turbo • Consistent identity across te, hi, en"
        )
    )

    fun slotForGender(gender: VoiceGender): String =
        if (gender == VoiceGender.MALE) SLOT_MALE else SLOT_FEMALE

    fun defaultForGender(gender: VoiceGender): ElevenLabsVoiceOption =
        voices.first { it.gender == gender }

    fun findById(id: String): ElevenLabsVoiceOption =
        voices.firstOrNull { it.id.equals(id, ignoreCase = true) || it.slot.equals(id, ignoreCase = true) }
            ?: voices.first()

    private val reservedUrlSegments = setOf(
        "app", "voice-library", "voice-lab", "voices", "voice",
        "share", "shared", "v1", "text-to-speech", "stream",
        "speech-synthesis", "settings"
    )
    private val parentUrlMarkers = setOf("share", "voice", "voices", "text-to-speech")
    private val voiceIdRegex = Regex("^[A-Za-z0-9_-]{8,64}$")

    fun extractVoiceId(rawValue: String): String {
        val trimmed = rawValue.trim().trim('\'', '"')
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.contains("elevenlabs.io", ignoreCase = true)
        ) {
            runCatching {
                val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"
                val uri = java.net.URI(normalized)
                val queryParams = uri.rawQuery.orEmpty().split("&").mapNotNull { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
                }.toMap()
                val queryId = queryParams["voiceId"] ?: queryParams["voice_id"] ?: queryParams["id"]
                if (!queryId.isNullOrBlank() && voiceIdRegex.matches(queryId.trim())) {
                    return queryId.trim()
                }
                val segments = uri.path.orEmpty().split("/").map { it.trim() }.filter { it.isNotBlank() }
                for (i in 0 until segments.size - 1) {
                    if (segments[i].lowercase() in parentUrlMarkers) {
                        val candidate = segments[i + 1]
                        if (candidate.lowercase() !in reservedUrlSegments && voiceIdRegex.matches(candidate)) {
                            return candidate
                        }
                    }
                }
                for (i in segments.indices.reversed()) {
                    val seg = segments[i]
                    if (seg.lowercase() !in reservedUrlSegments && voiceIdRegex.matches(seg)) {
                        return seg
                    }
                }
            }
        }
        return trimmed
    }

    /**
     * Checks whether [text] contains any Telugu Unicode characters (`\u0C00..\u0C7F`).
     * When Telugu is selected, SAATHI must display colloquial Roman Telugu ONLY and never
     * show Telugu Unicode script in visible UI.
     */
    fun containsTeluguUnicode(text: String): Boolean {
        for (i in text.indices) {
            if (text[i].code in 0x0C00..0x0C7F) return true
        }
        return false
    }

    private val COLLOQUIAL_TELUGU_PHRASE_MAP: List<Pair<String, String>> = listOf(
        "ఎలా ఉన్నావురా" to "ela unnav ra",
        "ఎలా ఉన్నావ్" to "ela unnav",
        "ఎలా ఉన్నావు" to "ela unnav",
        "బాగున్నావా" to "baagunnava",
        "బాగున్నాను" to "baagunnanu",
        "బాగున్నా" to "baagunna",
        "బాగుంది" to "baagundi",
        "ఈరోజు" to "eeroju",
        "ఏం చేస్తున్నావు" to "em chestunnav",
        "ఏం చేస్తున్నావ్" to "em chestunnav",
        "ఏమైంది" to "em ayindi",
        "నిజంగా" to "nijamga",
        "అదైతే" to "adaithe",
        "భలే ఉంది" to "bhale undi",
        "చాలా బాధగా ఉంది" to "chaala baadhaga undi",
        "సంతోషంగా ఉంది" to "santhoshanga undi",
        "మాట్లాడుకుందాం" to "maatladukundam",
        "వింటున్నాను" to "vintunnanu",
        "వింటున్నా" to "vintunna",
        "నెమ్మదిగా" to "nemmadiga",
        "కొత్త సందేశం" to "kotha sandesham",
        "బోర్ కొడుతుంది" to "bore koduthundi",
        "నిద్ర వస్తుంది" to "nidra vastundi",
        "చెప్పు" to "cheppu",
        "అవును" to "avunu",
        "కాదు" to "kaadu",
        "ఎందుకు" to "enduku",
        "సరే" to "sare",
        "అయ్యో" to "Ayyo",
        "అరే" to "Arey",
        "హాహా" to "Haha",
        "హేయ్" to "Hey",
        "హాయ్" to "Hi",
        "సాథీ" to "Saathi",
        "నేను" to "nenu",
        "నువ్వు" to "nuvvu",
        "నాకు" to "naaku",
        "నీకు" to "neeku",
        "మనం" to "manam",
        "సాయంత్రం" to "saayantram",
        "చాయ్" to "chai",
        "తాగుతూ" to "thaaguthu",
        "ఇంకా" to "inka",
        "చాలా" to "chaala",
        "మంచిది" to "manchidi",
        "రా" to "ra"
    )

    private val ROMAN_TO_SPOKEN_TELUGU_WORD_MAP: Map<String, String> = mapOf(
        "arey" to "అరే",
        "ayyo" to "అయ్యో",
        "haha" to "హాహా",
        "hey" to "హేయ్",
        "hi" to "హాయ్",
        "saathi" to "సాథీ",
        "ela" to "ఎలా",
        "unnav" to "ఉన్నావ్",
        "unnavu" to "ఉన్నావు",
        "unna" to "ఉన్నా",
        "unnanu" to "ఉన్నాను",
        "undi" to "ఉంది",
        "ra" to "రా",
        "baagunnava" to "బాగున్నావా",
        "bagunnava" to "బాగున్నావా",
        "baagunna" to "బాగున్నా",
        "bagunna" to "బాగున్నా",
        "baagunnanu" to "బాగున్నాను",
        "baagundi" to "బాగుంది",
        "bagundi" to "బాగుంది",
        "baane" to "బానే",
        "bane" to "బానే",
        "eeroju" to "ఈరోజు",
        "em" to "ఏం",
        "chestunnav" to "చేస్తున్నావ్",
        "chestunnavu" to "చేస్తున్నావు",
        "chestunna" to "చేస్తున్నా",
        "nenu" to "నేను",
        "nuvvu" to "నువ్వు",
        "naaku" to "నాకు",
        "naku" to "నాకు",
        "neeku" to "నీకు",
        "nee" to "నీ",
        "naa" to "నా",
        "mana" to "మన",
        "manam" to "మనం",
        "iddaram" to "ఇద్దరం",
        "konchem" to "కొంచెం",
        "kaburlu" to "కబుర్లు",
        "cheppukundama" to "చెప్పుకుందామా",
        "cheppu" to "చెప్పు",
        "cheppava" to "చెప్పవా",
        "maatladukundam" to "మాట్లాడుకుందాం",
        "vintunnanu" to "వింటున్నాను",
        "vintunna" to "వింటున్నా",
        "vinte" to "వింటే",
        "maata" to "మాట",
        "chaala" to "చాలా",
        "chala" to "చాలా",
        "santhoshanga" to "సంతోషంగా",
        "baadhaga" to "బాధగా",
        "nemmadiga" to "నెమ్మదిగా",
        "nijamga" to "నిజంగా",
        "avunaa" to "అవునా",
        "avunu" to "అవును",
        "kaadu" to "కాదు",
        "enduku" to "ఎందుకు",
        "adaithe" to "అదైతే",
        "aithe" to "అయితే",
        "bhale" to "భలే",
        "ayindi" to "అయింది",
        "mari" to "మరి",
        "bore" to "బోర్",
        "koduthundi" to "కొడుతుంది",
        "nidra" to "నిద్ర",
        "vastundi" to "వస్తుంది",
        "phone" to "ఫోన్",
        "pakkana" to "పక్కన",
        "petti" to "పెట్టి",
        "rest" to "రెస్ట్",
        "teesuko" to "తీసుకో",
        "repu" to "రేపు",
        "malli" to "మళ్ళీ",
        "saayantram" to "సాయంత్రం",
        "chai" to "చాయ్",
        "thaaguthu" to "తాగుతూ",
        "inka" to "ఇంకా",
        "sare" to "సరే",
        "manchidi" to "మంచిది"
    )

    private val TELUGU_INDEPENDENT_VOWELS: Map<Char, String> = mapOf(
        '\u0C05' to "a", '\u0C06' to "aa", '\u0C07' to "i", '\u0C08' to "ee",
        '\u0C09' to "u", '\u0C0A' to "oo", '\u0C0B' to "ru", '\u0C0E' to "e",
        '\u0C0F' to "e", '\u0C10' to "ai", '\u0C12' to "o", '\u0C13' to "o",
        '\u0C14' to "au"
    )

    private val TELUGU_CONSONANTS: Map<Char, String> = mapOf(
        '\u0C15' to "k", '\u0C16' to "kh", '\u0C17' to "g", '\u0C18' to "gh", '\u0C19' to "ng",
        '\u0C1A' to "ch", '\u0C1B' to "chh", '\u0C1C' to "j", '\u0C1D' to "jh", '\u0C1E' to "ny",
        '\u0C1F' to "t", '\u0C20' to "th", '\u0C21' to "d", '\u0C22' to "dh", '\u0C23' to "n",
        '\u0C24' to "th", '\u0C25' to "th", '\u0C26' to "d", '\u0C27' to "dh", '\u0C28' to "n",
        '\u0C2A' to "p", '\u0C2B' to "ph", '\u0C2C' to "b", '\u0C2D' to "bh", '\u0C2E' to "m",
        '\u0C2F' to "y", '\u0C30' to "r", '\u0C31' to "r", '\u0C32' to "l", '\u0C33' to "l",
        '\u0C35' to "v", '\u0C36' to "sh", '\u0C37' to "sh", '\u0C38' to "s", '\u0C39' to "h"
    )

    private val TELUGU_MATRAS: Map<Char, String> = mapOf(
        '\u0C3E' to "aa", '\u0C3F' to "i", '\u0C40' to "ee", '\u0C41' to "u",
        '\u0C42' to "oo", '\u0C43' to "ru", '\u0C46' to "e", '\u0C47' to "e",
        '\u0C48' to "ai", '\u0C4A' to "o", '\u0C4B' to "o", '\u0C4C' to "au"
    )

    /**
     * Guarantees that any Telugu text displayed in SAATHI uses natural Roman/English letters ONLY
     * and never contains Telugu Unicode script (`\u0C00..\u0C7F`).
     */
    fun ensureRomanTeluguDisplay(text: String): String {
        if (!containsTeluguUnicode(text)) return text
        var working = text
        for ((teluguPhrase, romanPhrase) in COLLOQUIAL_TELUGU_PHRASE_MAP) {
            if (working.contains(teluguPhrase)) {
                working = working.replace(teluguPhrase, romanPhrase)
            }
        }
        if (!containsTeluguUnicode(working)) return working

        val sb = StringBuilder(working.length)
        var i = 0
        while (i < working.length) {
            val ch = working[i]
            val code = ch.code
            if (code !in 0x0C00..0x0C7F) {
                sb.append(ch)
                i++
                continue
            }
            when {
                TELUGU_INDEPENDENT_VOWELS.containsKey(ch) -> {
                    sb.append(TELUGU_INDEPENDENT_VOWELS[ch])
                    i++
                }
                TELUGU_CONSONANTS.containsKey(ch) -> {
                    val base = TELUGU_CONSONANTS[ch] ?: ""
                    val next = if (i + 1 < working.length) working[i + 1] else '\u0000'
                    when {
                        next == '\u0C4D' -> {
                            sb.append(base)
                            i += 2
                        }
                        TELUGU_MATRAS.containsKey(next) -> {
                            sb.append(base).append(TELUGU_MATRAS[next])
                            i += 2
                        }
                        else -> {
                            sb.append(base).append('a')
                            i++
                        }
                    }
                }
                TELUGU_MATRAS.containsKey(ch) -> {
                    sb.append(TELUGU_MATRAS[ch])
                    i++
                }
                ch == '\u0C02' -> {
                    val nextConsonant = if (i + 1 < working.length) working[i + 1] else '\u0000'
                    val nasal = if (nextConsonant in listOf('\u0C2A', '\u0C2B', '\u0C2C', '\u0C2D', '\u0C2E')) "m" else "n"
                    sb.append(nasal)
                    i++
                }
                ch == '\u0C03' -> {
                    sb.append("h")
                    i++
                }
                ch in '\u0C66'..'\u0C6F' -> {
                    sb.append(('0'.code + (code - 0x0C66)).toChar())
                    i++
                }
                else -> {
                    i++
                }
            }
        }
        return sb.toString().replace(Regex("[ ]{2,}"), " ")
    }

    /**
     * Converts displayed Roman Telugu text into natural phonetic Telugu script for ElevenLabs TTS
     * (`language_code = "te"`) so ElevenLabs pronounces colloquial Telugu words with authentic
     * Indian/Telugu phonetics instead of reading Roman letters as English.
     *
     * Displayed UI text ALWAYS uses [ensureRomanTeluguDisplay] (Roman letters only), while spoken
     * synthesis uses this separate phonetic representation.
     */
    fun toSpokenTeluguPronunciation(romanOrTeluguText: String): String {
        val cleaned = sanitizeSpeechText(romanOrTeluguText, SaathiLanguage.TELUGU)
        if (cleaned.isBlank()) return ""
        return Regex("[A-Za-z]+").replace(cleaned) { match ->
            val token = match.value
            ROMAN_TO_SPOKEN_TELUGU_WORD_MAP[token.lowercase()] ?: token
        }
    }

    private val markdownLinkRegex = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val stageDirectionRegex = Regex("\\[(?:laughs?|chuckles?|sighs?|smiles?|giggles?|pause)]", RegexOption.IGNORE_CASE)
    private val markdownSymbolsRegex = Regex("[*_`~#]+")
    private val bulletPrefixRegex = Regex("(?m)^\\s*[-•+]\\s+")
    private val multiSpaceRegex = Regex("\\s+")

    private fun stripEmojis(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = Character.codePointAt(input, i)
            val charCount = Character.charCount(cp)
            val isEmojiOrModifier =
                cp >= 0x1F000 ||
                    cp in 0x2600..0x27BF ||
                    cp in 0xFE00..0xFE0F ||
                    cp == 0x200D ||
                    cp == 0x20E3
            if (!isEmojiOrModifier) {
                sb.appendCodePoint(cp)
            }
            i += charCount
        }
        return sb.toString()
    }

    /**
     * Strips emojis, Markdown formatting, bullet markers, and bracketed stage directions
     * so ElevenLabs TTS never reads emojis or formatting characters aloud.
     */
    fun sanitizeSpeechText(rawText: String, language: SaathiLanguage = SaathiLanguage.ENGLISH): String {
        if (rawText.isBlank()) return ""
        return stripEmojis(rawText)
            .replace(markdownLinkRegex, "$1")
            .replace(stageDirectionRegex, "")
            .replace(bulletPrefixRegex, "")
            .replace(markdownSymbolsRegex, "")
            .replace(multiSpaceRegex, " ")
            .trim()
    }
}

data class MemorySuggestion(
    val category: String,
    val content: String
)

@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val preferredName: String? = null,
    val photoUrl: String? = null,
    val preferredLanguage: String = SaathiLanguage.ENGLISH.name,
    val selectedVoiceId: String = SaathiVoiceCatalog.SLOT_FEMALE,
    val selectedVoiceGender: String = VoiceGender.FEMALE.name,
    val speechSpeed: Double = 1.0,
    val speechVolume: Double = 1.0,
    val autoPlayVoice: Boolean = true,
    val memoryEnabled: Boolean = true,
    val cloudSyncEnabled: Boolean = true,
    val dailyRemindersEnabled: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "displayName" to displayName.ifBlank { "Friend" }.take(80),
        "preferredName" to preferredName?.take(60),
        "photoUrl" to photoUrl?.take(500),
        "preferredLanguage" to preferredLanguage,
        "selectedVoiceId" to selectedVoiceId,
        "selectedVoiceGender" to selectedVoiceGender,
        "speechSpeed" to speechSpeed.coerceIn(0.5, 2.0),
        "speechVolume" to speechVolume.coerceIn(0.0, 1.0),
        "autoPlayVoice" to autoPlayVoice,
        "memoryEnabled" to memoryEnabled,
        "cloudSyncEnabled" to cloudSyncEnabled,
        "dailyRemindersEnabled" to dailyRemindersEnabled,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@IgnoreExtraProperties
data class MemoryDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val category: String = "Context",
    val content: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ConversationDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val languageCode: String = SaathiLanguage.ENGLISH.name,
    val lastMessagePreview: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MessageDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "user",
    val text: String = "",
    val isVoice: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)
