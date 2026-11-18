package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.SaathiLanguage
import com.example.model.SaathiVoiceCatalog
import com.example.model.SttProvider
import com.example.model.VoiceGender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.saathiPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "saathi_preferences")

data class UserPreferencesState(
    val onboardingCompleted: Boolean = false,
    val preferredName: String = "",
    val preferredLanguage: SaathiLanguage = SaathiLanguage.ENGLISH,
    val selectedVoiceGender: VoiceGender = VoiceGender.FEMALE,
    val selectedVoiceId: String = SaathiVoiceCatalog.SLOT_FEMALE,
    val speechSpeed: Float = 1.0f,
    val speechVolume: Float = 1.0f,
    val autoPlayVoice: Boolean = true,
    val autoTurnTaking: Boolean = true,
    val autoStartConversation: Boolean = true,
    val memoryEnabled: Boolean = true,
    val cloudSyncEnabled: Boolean = true,
    val dailyRemindersEnabled: Boolean = false,
    val voiceBackendUrl: String = "",
    val sttProvider: SttProvider = SttProvider.ANDROID_SPEECH
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PREFERRED_NAME = stringPreferencesKey("preferred_name")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        val VOICE_GENDER = stringPreferencesKey("voice_gender")
        val VOICE_ID = stringPreferencesKey("voice_id")
        val SPEECH_SPEED = floatPreferencesKey("speech_speed")
        val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        val AUTO_PLAY_VOICE = booleanPreferencesKey("auto_play_voice")
        val AUTO_TURN_TAKING = booleanPreferencesKey("auto_turn_taking")
        val AUTO_START_CONVERSATION = booleanPreferencesKey("auto_start_conversation")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val CLOUD_SYNC_ENABLED = booleanPreferencesKey("cloud_sync_enabled")
        val DAILY_REMINDERS = booleanPreferencesKey("daily_reminders_enabled")
        val VOICE_BACKEND_URL = stringPreferencesKey("voice_backend_url")
        val STT_PROVIDER = stringPreferencesKey("stt_provider")
    }

    val preferencesFlow: Flow<UserPreferencesState> = context.saathiPrefsDataStore.data.map { prefs ->
        val gender = runCatching {
            VoiceGender.valueOf(prefs[Keys.VOICE_GENDER] ?: VoiceGender.FEMALE.name)
        }.getOrDefault(VoiceGender.FEMALE)

        val language = runCatching {
            SaathiLanguage.valueOf(prefs[Keys.PREFERRED_LANGUAGE] ?: SaathiLanguage.ENGLISH.name)
        }.getOrDefault(SaathiLanguage.ENGLISH)

        val sttProvider = runCatching {
            SttProvider.valueOf(prefs[Keys.STT_PROVIDER] ?: SttProvider.ANDROID_SPEECH.name)
        }.getOrDefault(SttProvider.ANDROID_SPEECH)

        val storedVoiceId = prefs[Keys.VOICE_ID]?.takeIf { it.isNotBlank() }

        UserPreferencesState(
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            preferredName = prefs[Keys.PREFERRED_NAME] ?: "",
            preferredLanguage = language,
            selectedVoiceGender = gender,
            selectedVoiceId = storedVoiceId ?: SaathiVoiceCatalog.slotForGender(gender),
            speechSpeed = (prefs[Keys.SPEECH_SPEED] ?: 1.0f).coerceIn(0.75f, 1.35f),
            speechVolume = (prefs[Keys.SPEECH_VOLUME] ?: 1.0f).coerceIn(0.0f, 1.0f),
            autoPlayVoice = prefs[Keys.AUTO_PLAY_VOICE] ?: true,
            autoTurnTaking = prefs[Keys.AUTO_TURN_TAKING] ?: true,
            autoStartConversation = prefs[Keys.AUTO_START_CONVERSATION] ?: true,
            memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: true,
            cloudSyncEnabled = prefs[Keys.CLOUD_SYNC_ENABLED] ?: true,
            dailyRemindersEnabled = prefs[Keys.DAILY_REMINDERS] ?: false,
            voiceBackendUrl = prefs[Keys.VOICE_BACKEND_URL] ?: "",
            sttProvider = sttProvider
        )
    }

    suspend fun completeOnboarding(language: SaathiLanguage, gender: VoiceGender) {
        val defaultVoice = SaathiVoiceCatalog.defaultForGender(gender)
        context.saathiPrefsDataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = true
            prefs[Keys.PREFERRED_LANGUAGE] = language.name
            prefs[Keys.VOICE_GENDER] = gender.name
            prefs[Keys.VOICE_ID] = defaultVoice.slot
        }
    }

    suspend fun setLanguage(language: SaathiLanguage) {
        context.saathiPrefsDataStore.edit { it[Keys.PREFERRED_LANGUAGE] = language.name }
    }

    suspend fun setVoice(voiceId: String, gender: VoiceGender) {
        context.saathiPrefsDataStore.edit { prefs ->
            prefs[Keys.VOICE_ID] = voiceId.ifBlank { SaathiVoiceCatalog.slotForGender(gender) }
            prefs[Keys.VOICE_GENDER] = gender.name
        }
    }

    suspend fun setVoiceGender(gender: VoiceGender) {
        val voice = SaathiVoiceCatalog.defaultForGender(gender)
        context.saathiPrefsDataStore.edit { prefs ->
            prefs[Keys.VOICE_GENDER] = gender.name
            prefs[Keys.VOICE_ID] = voice.slot
        }
    }

    suspend fun setSpeechSpeed(speed: Float) {
        context.saathiPrefsDataStore.edit { it[Keys.SPEECH_SPEED] = speed.coerceIn(0.75f, 1.35f) }
    }

    suspend fun setSpeechVolume(volume: Float) {
        context.saathiPrefsDataStore.edit { it[Keys.SPEECH_VOLUME] = volume.coerceIn(0.0f, 1.0f) }
    }

    suspend fun setAutoPlayVoice(enabled: Boolean) {
        context.saathiPrefsDataStore.edit { it[Keys.AUTO_PLAY_VOICE] = enabled }
    }

    suspend fun setAutoTurnTaking(enabled: Boolean) {
        context.saathiPrefsDataStore.edit { it[Keys.AUTO_TURN_TAKING] = enabled }
    }

    suspend fun setAutoStartConversation(enabled: Boolean) {
        context.saathiPrefsDataStore.edit { it[Keys.AUTO_START_CONVERSATION] = enabled }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.saathiPrefsDataStore.edit { it[Keys.MEMORY_ENABLED] = enabled }
    }

    suspend fun setCloudSyncEnabled(enabled: Boolean) {
        context.saathiPrefsDataStore.edit { it[Keys.CLOUD_SYNC_ENABLED] = enabled }
    }

    suspend fun setDailyRemindersEnabled(enabled: Boolean) {
        context.saathiPrefsDataStore.edit { it[Keys.DAILY_REMINDERS] = enabled }
    }

    suspend fun setPreferredName(name: String) {
        context.saathiPrefsDataStore.edit { it[Keys.PREFERRED_NAME] = name.trim().take(60) }
    }

    suspend fun setVoiceBackendUrl(url: String) {
        context.saathiPrefsDataStore.edit { it[Keys.VOICE_BACKEND_URL] = url.trim().trimEnd('/') }
    }

    suspend fun setSttProvider(provider: SttProvider) {
        context.saathiPrefsDataStore.edit { it[Keys.STT_PROVIDER] = provider.name }
    }

    suspend fun clearAll() {
        context.saathiPrefsDataStore.edit { it.clear() }
    }
}
