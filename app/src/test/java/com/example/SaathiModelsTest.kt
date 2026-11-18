package com.example

import com.example.model.SaathiLanguage
import com.example.model.SaathiVoiceCatalog
import com.example.model.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaathiModelsTest {

    @Test
    fun `voice catalog provides configurable male and female slots without hardcoded unavailable IDs`() {
        val femaleVoices = SaathiVoiceCatalog.voices.filter { it.gender == VoiceGender.FEMALE }
        val maleVoices = SaathiVoiceCatalog.voices.filter { it.gender == VoiceGender.MALE }
        assertEquals(1, femaleVoices.size)
        assertEquals(1, maleVoices.size)
        assertEquals(SaathiVoiceCatalog.SLOT_FEMALE, femaleVoices.first().slot)
        assertEquals(SaathiVoiceCatalog.SLOT_MALE, maleVoices.first().slot)
        assertEquals(SaathiVoiceCatalog.DEFAULT_TTS_MODEL, femaleVoices.first().modelId)
        assertEquals(SaathiVoiceCatalog.DEFAULT_TTS_MODEL, maleVoices.first().modelId)
        assertFalse(femaleVoices.first().configured)
        assertTrue(SaathiVoiceCatalog.voices.all {
            it.supportedLanguages.containsAll(
                listOf(SaathiLanguage.TELUGU, SaathiLanguage.HINDI, SaathiLanguage.ENGLISH)
            )
        })
    }

    @Test
    fun `all three languages have valid ISO codes, locale tags, and natural greetings with Roman Telugu only`() {
        assertEquals("te", SaathiLanguage.TELUGU.isoCode)
        assertEquals("hi", SaathiLanguage.HINDI.isoCode)
        assertEquals("en", SaathiLanguage.ENGLISH.isoCode)
        assertEquals("te-IN", SaathiLanguage.TELUGU.localeTag)
        assertEquals("hi-IN", SaathiLanguage.HINDI.localeTag)
        assertEquals("en-IN", SaathiLanguage.ENGLISH.localeTag)
        assertEquals(
            "Arey, ela unnav ra? Baagunnava? Eeroju em chestunnav?",
            SaathiLanguage.TELUGU.spokenGreeting
        )
        assertEquals(
            "अरे, कैसे हो? आज तुम्हारा दिन कैसा जा रहा है?",
            SaathiLanguage.HINDI.spokenGreeting
        )
        assertEquals(
            "Hey! How are you doing? How's your day going?",
            SaathiLanguage.ENGLISH.spokenGreeting
        )
        // Verify Telugu language strings NEVER contain Telugu Unicode script
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(SaathiLanguage.TELUGU.nativeName))
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(SaathiLanguage.TELUGU.badgeCode))
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(SaathiLanguage.TELUGU.sampleUserPrompt))
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(SaathiLanguage.TELUGU.sampleReply))
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(SaathiLanguage.TELUGU.spokenGreeting))
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(SaathiLanguage.TELUGU.greetingTitle))
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(SaathiLanguage.TELUGU.greetingSubtitle))

        SaathiLanguage.entries.forEach { lang ->
            assertTrue(lang.greetingTitle.isNotBlank())
            assertTrue(lang.sampleReply.isNotBlank())
            assertTrue(lang.spokenGreeting.isNotBlank())
        }
    }

    @Test
    fun `ensureRomanTeluguDisplay converts any Telugu Unicode into natural Roman Telugu without leaving Telugu script`() {
        val romanInput = "Nenu baane unna ra 😂 Nuvvu cheppu, em chestunnav?"
        assertEquals(romanInput, SaathiVoiceCatalog.ensureRomanTeluguDisplay(romanInput))
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(romanInput))

        val unicodeInput = "ఎలా ఉన్నావురా? బాగున్నావా? ఈరోజు ఏం చేస్తున్నావు?"
        assertTrue(SaathiVoiceCatalog.containsTeluguUnicode(unicodeInput))
        val converted = SaathiVoiceCatalog.ensureRomanTeluguDisplay(unicodeInput)
        assertFalse("Converted text must not contain Telugu Unicode: $converted", SaathiVoiceCatalog.containsTeluguUnicode(converted))
        assertTrue(converted.contains("ela unnav ra", ignoreCase = true))
        assertTrue(converted.contains("baagunnava", ignoreCase = true))
        assertTrue(converted.contains("em chestunnav", ignoreCase = true))
    }

    @Test
    fun `extractVoiceId supports raw IDs and ElevenLabs URLs cleanly`() {
        assertEquals("21m00Tcm4TlvDq8ikWAM", SaathiVoiceCatalog.extractVoiceId("21m00Tcm4TlvDq8ikWAM"))
        assertEquals(
            "UploadedMaleID99",
            SaathiVoiceCatalog.extractVoiceId("https://elevenlabs.io/app/voice-library?voiceId=UploadedMaleID99")
        )
        assertEquals(
            "UploadedFemaleID77",
            SaathiVoiceCatalog.extractVoiceId("https://elevenlabs.io/app/voice-lab/share/UploadedFemaleID77/indian-warm-voice")
        )
        assertEquals(
            "UploadedMaleID55",
            SaathiVoiceCatalog.extractVoiceId("https://api.elevenlabs.io/v1/voices/UploadedMaleID55")
        )
    }

    @Test
    fun `sanitizeSpeechText strips emojis Markdown and stage directions while preserving natural expressions`() {
        assertEquals(
            "Haha! Nijamga ra? Adaithe bhale undi!",
            SaathiVoiceCatalog.sanitizeSpeechText(
                "**Haha! Nijamga ra? 😂 [laughs] Adaithe bhale undi!**",
                SaathiLanguage.TELUGU
            )
        )
        assertEquals(
            "अरे वाह! हाहा, सच में?",
            SaathiVoiceCatalog.sanitizeSpeechText(
                "अरे वाह! 😂 *हाहा*, सच में? ✨",
                SaathiLanguage.HINDI
            )
        )
        assertEquals(
            "That's actually funny! Haha, tell me more.",
            SaathiVoiceCatalog.sanitizeSpeechText(
                "That's *actually* funny! 😂 [chuckles] Haha, tell me more.",
                SaathiLanguage.ENGLISH
            )
        )
    }

    @Test
    fun `toSpokenTeluguPronunciation keeps displayed Roman Telugu and spoken Telugu phonetic script cleanly separated`() {
        val displayedGreeting = SaathiLanguage.TELUGU.spokenGreeting
        assertFalse(SaathiVoiceCatalog.containsTeluguUnicode(displayedGreeting))

        val spokenPhonetic = SaathiVoiceCatalog.toSpokenTeluguPronunciation(displayedGreeting)
        assertTrue(spokenPhonetic.contains("అరే"))
        assertTrue(spokenPhonetic.contains("ఎలా ఉన్నావ్ రా"))
        assertTrue(spokenPhonetic.contains("బాగున్నావా"))
        assertTrue(spokenPhonetic.contains("ఈరోజు ఏం చేస్తున్నావ్"))

        // User speech transcripts returned by STT in Telugu Unicode convert cleanly to Roman Telugu
        val sttTranscript = "హాయ్ సాథీ ఎలా ఉన్నావ్? అవును నాకు బోర్ కొడుతుంది"
        val romanTranscript = SaathiVoiceCatalog.ensureRomanTeluguDisplay(sttTranscript)
        assertFalse("STT transcript must not contain Telugu Unicode: $romanTranscript", SaathiVoiceCatalog.containsTeluguUnicode(romanTranscript))
        assertTrue(romanTranscript.contains("Hi Saathi ela unnav", ignoreCase = true))
        assertTrue(romanTranscript.contains("avunu", ignoreCase = true))
        assertTrue(romanTranscript.contains("bore koduthundi", ignoreCase = true))
    }
}
