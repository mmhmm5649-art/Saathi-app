package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ConversationEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.SaathiDao
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.UserPreferencesState
import com.example.data.remote.FirestoreRepository
import com.example.data.remote.SaathiAiRepository
import com.example.data.remote.TtsAudioSource
import com.example.data.remote.VoiceBackendClient
import com.example.data.remote.VoiceBackendResult
import com.example.model.ElevenLabsVoiceOption
import com.example.model.MemorySuggestion
import com.example.model.SaathiLanguage
import com.example.model.SaathiLatencyRecorder
import com.example.model.SaathiVoiceCatalog
import com.example.model.SttProvider
import com.example.model.TurnLatencyMetrics
import com.example.model.VoiceGender
import com.example.model.VoiceLibraryCandidate
import com.example.model.VoiceSessionState
import com.example.voice.AndroidSpeechRecognizerManager
import com.example.voice.AudioRecorderManager
import com.example.voice.VoiceAudioPlayer
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface BackendHealthState {
    data object Idle : BackendHealthState
    data object Checking : BackendHealthState
    data class Ready(val message: String) : BackendHealthState
    data class Failed(val message: String) : BackendHealthState
}

data class CompanionUiState(
    val activeConversationId: String = "",
    val voiceSessionState: VoiceSessionState = VoiceSessionState.IDLE,
    val liveTranscript: String = "",
    val streamingReply: String = "",
    val lastAssistantReply: String = "",
    val audioAmplitude: Float = 0.08f,
    val isMuted: Boolean = false,
    val isTextThinking: Boolean = false,
    val lastFailedPrompt: String? = null,
    val lastFailedWasVoice: Boolean = false,
    val lastFailedConversationId: String? = null,
    val needsMicPermissionForAutoStart: Boolean = false,
    val errorMessage: String? = null,
    val voiceBackendNotice: String? = null,
    val pendingMemorySuggestion: MemorySuggestion? = null,
    val historySearchQuery: String = "",
    val previewingVoiceId: String? = null,
    val availableVoices: List<ElevenLabsVoiceOption> = SaathiVoiceCatalog.voices,
    val activeTtsModel: String = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
    val activeSttModel: String = "scribe_v3_live",
    val voiceLibraryCandidates: List<VoiceLibraryCandidate> = emptyList(),
    val isSearchingLibrary: Boolean = false,
    val isSavingBackendVoice: Boolean = false,
    val healthState: BackendHealthState = BackendHealthState.Idle,
    val isRecordingScribe: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val isGuestSession: Boolean = false,
    val latestTurnMetrics: TurnLatencyMetrics? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class SaathiViewModel(
    val currentUserId: String,
    val userDisplayName: String,
    val userEmail: String,
    val userPhotoUrl: String?,
    val isGuestSession: Boolean = false,
    private val dao: SaathiDao,
    private val prefsRepo: UserPreferencesRepository,
    private val firestoreRepo: FirestoreRepository,
    private val aiRepo: SaathiAiRepository,
    private val voiceBackendClient: VoiceBackendClient,
    private val speechRecognizer: AndroidSpeechRecognizerManager,
    private val audioRecorder: AudioRecorderManager,
    private val audioPlayer: VoiceAudioPlayer,
    val latencyRecorder: SaathiLatencyRecorder = SaathiLatencyRecorder()
) : ViewModel() {

    private val initialConvId: String = "conv_${UUID.randomUUID().toString().replace("-", "")}"

    private val _uiState = MutableStateFlow(
        CompanionUiState(
            activeConversationId = initialConvId,
            isGuestSession = isGuestSession
        )
    )
    val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()

    private val canUseCloudSync: Boolean
        get() = !isGuestSession && preferences.value.cloudSyncEnabled

    val preferences: StateFlow<UserPreferencesState> = prefsRepo.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UserPreferencesState()
        )

    private val activeConvIdFlow = MutableStateFlow(initialConvId)
    private val searchQueryFlow = MutableStateFlow("")

    val messages: StateFlow<List<MessageEntity>> = activeConvIdFlow
        .flatMapLatest { convId ->
            if (convId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList())
            else dao.observeMessages(convId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val conversations: StateFlow<List<ConversationEntity>> = searchQueryFlow
        .flatMapLatest { query ->
            if (query.isBlank()) dao.observeConversations(currentUserId)
            else dao.searchConversations(currentUserId, query.trim())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val memories: StateFlow<List<MemoryEntity>> = dao.observeMemories(currentUserId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private var activeAiJob: Job? = null
    private var activeGreetingJob: Job? = null
    private var activeSttJob: Job? = null
    private var activePreviewJob: Job? = null
    private var speakingPulseJob: Job? = null
    private var autoTurnJob: Job? = null

    private val greetedConversationIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val knownConversationTitles = ConcurrentHashMap<String, String>()
    private val turnMetricsCounter = AtomicLong(0L)

    @Volatile
    private var sttListeningStartAtNs: Long = 0L

    @Volatile
    private var lastMeasuredSttMs: Double = 0.0

    @Volatile
    private var isSessionEndedByUser: Boolean = false

    @Volatile
    private var pendingLanguageOverride: SaathiLanguage? = null

    @Volatile
    private var isAppInForeground: Boolean = true
    private var voiceTurnSequence: Long = 0L
    private var ttsTurnToken: Long = 0L
    private var lastDeliveredVoiceToken: Long = 0L
    private var lastSubmittedVoiceNormalized: String = ""
    private var lastSubmittedVoiceAtMs: Long = 0L
    private var lastSubmittedTextNormalized: String = ""
    private var lastSubmittedTextAtMs: Long = 0L

    init {
        syncProfileAndCloudObservers()
        observeBackendVoices()
    }

    private fun observeBackendVoices() {
        viewModelScope.launch {
            prefsRepo.preferencesFlow
                .map { it.voiceBackendUrl }
                .distinctUntilChanged()
                .collect { url ->
                    if (url.isNotBlank()) {
                        refreshBackendVoices(url)
                    } else {
                        _uiState.update {
                            it.copy(
                                availableVoices = SaathiVoiceCatalog.voices,
                                activeTtsModel = SaathiVoiceCatalog.DEFAULT_TTS_MODEL,
                                activeSttModel = "scribe_v3_live"
                            )
                        }
                    }
                }
        }
    }

    private fun syncProfileAndCloudObservers() {
        // Temporary debug guest sessions are strictly local and never read/write Firestore
        if (isGuestSession) return
        viewModelScope.launch {
            val currentPrefs = preferences.value
            firestoreRepo.ensureProfileExists(
                displayName = userDisplayName,
                photoUrl = userPhotoUrl,
                language = currentPrefs.preferredLanguage,
                voiceId = currentPrefs.selectedVoiceId,
                voiceGender = currentPrefs.selectedVoiceGender
            )
        }

        // Sync remote memories into local Room cache when cloud sync is enabled
        viewModelScope.launch {
            firestoreRepo.observeMemories(currentUserId)
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    Log.w(TAG, "Cloud memory observer warning: ${(fsErr ?: error).message}")
                }
                .collect { remoteList ->
                    if (preferences.value.cloudSyncEnabled) {
                        val existingById = dao.getMemoriesOnce(currentUserId).associateBy { it.id }
                        val isTeluguPref = preferences.value.preferredLanguage == SaathiLanguage.TELUGU
                        for (doc in remoteList) {
                            val remoteUpdatedAt = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                            val local = existingById[doc.id]
                            if (local == null || remoteUpdatedAt > local.updatedAtEpochMs) {
                                val safeContent = if (isTeluguPref) {
                                    SaathiVoiceCatalog.ensureRomanTeluguDisplay(doc.content)
                                } else {
                                    doc.content
                                }
                                dao.upsertMemory(
                                    MemoryEntity(
                                        id = doc.id,
                                        userId = currentUserId,
                                        category = doc.category,
                                        content = safeContent,
                                        createdAtEpochMs = doc.createdAt?.toDate()?.time ?: remoteUpdatedAt,
                                        updatedAtEpochMs = remoteUpdatedAt
                                    )
                                )
                            }
                        }
                    }
                }
        }

        // Sync remote conversation headers into local Room cache without overwriting newer local updates
        viewModelScope.launch {
            firestoreRepo.observeConversations(currentUserId)
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    Log.w(TAG, "Cloud conversation observer warning: ${(fsErr ?: error).message}")
                }
                .collect { remoteConvs ->
                    if (preferences.value.cloudSyncEnabled) {
                        for (doc in remoteConvs) {
                            val remoteUpdatedAt = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                            val local = dao.getConversationById(doc.id)
                            if (local == null || remoteUpdatedAt > local.updatedAtEpochMs) {
                                val isTeluguConv = doc.languageCode.equals(SaathiLanguage.TELUGU.name, ignoreCase = true)
                                val safeTitle = if (isTeluguConv) {
                                    SaathiVoiceCatalog.ensureRomanTeluguDisplay(doc.title)
                                } else doc.title
                                val safePreview = if (isTeluguConv) {
                                    SaathiVoiceCatalog.ensureRomanTeluguDisplay(doc.lastMessagePreview)
                                } else doc.lastMessagePreview
                                dao.upsertConversation(
                                    ConversationEntity(
                                        id = doc.id,
                                        userId = currentUserId,
                                        title = safeTitle,
                                        languageCode = doc.languageCode,
                                        lastMessagePreview = safePreview,
                                        createdAtEpochMs = doc.createdAt?.toDate()?.time ?: remoteUpdatedAt,
                                        updatedAtEpochMs = remoteUpdatedAt
                                    )
                                )
                            }
                        }
                    }
                }
        }
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        voiceTurnSequence++
        ttsTurnToken++
        autoTurnJob?.cancel()
        autoTurnJob = null
        activeGreetingJob?.cancel()
        activeGreetingJob = null
        val sttWasActive = activeSttJob?.isActive == true
        activeSttJob?.cancel()
        activeSttJob = null
        activePreviewJob?.cancel()
        activePreviewJob = null
        speakingPulseJob?.cancel()
        speakingPulseJob = null
        voiceBackendClient.cancelActiveSttRequest()
        voiceBackendClient.cancelActiveTtsRequest()
        audioPlayer.stop()
        speechRecognizer.onAppBackgrounded()
        audioRecorder.stopAndDiscard()
        _uiState.update { state ->
            val resetState = if (
                state.voiceSessionState == VoiceSessionState.GREETING ||
                state.voiceSessionState == VoiceSessionState.LISTENING ||
                state.voiceSessionState == VoiceSessionState.SPEAKING ||
                (sttWasActive && state.voiceSessionState == VoiceSessionState.THINKING)
            ) {
                VoiceSessionState.IDLE
            } else {
                state.voiceSessionState
            }
            state.copy(
                voiceSessionState = resetState,
                audioAmplitude = 0.08f,
                isRecordingScribe = false,
                previewingVoiceId = null
            )
        }
    }

    fun onAppForegrounded() {
        isAppInForeground = true
        speechRecognizer.onAppForegrounded()
    }

    /**
     * Called when VoiceCompanionScreen is displayed or when activeConversationId / autoStartConversation changes.
     * Automatically starts a natural spoken greeting in the selected language if automatic conversation is enabled
     * and microphone permission has already been granted, playing at most once per new conversation.
     */
    fun onVoiceScreenReady(hasMicPermission: Boolean = audioRecorder.hasRecordPermission()) {
        if (!isAppInForeground || isSessionEndedByUser) return
        val prefs = preferences.value
        if (!prefs.autoStartConversation) {
            if (_uiState.value.needsMicPermissionForAutoStart) {
                _uiState.update { it.copy(needsMicPermissionForAutoStart = false) }
            }
            return
        }
        val convId = _uiState.value.activeConversationId
        if (convId.isBlank() || greetedConversationIds.contains(convId)) return
        if (_uiState.value.voiceSessionState != VoiceSessionState.IDLE) return

        if (!hasMicPermission) {
            // Never secretly record — surface a clear microphone permission prompt instead
            _uiState.update { it.copy(needsMicPermissionForAutoStart = true) }
            return
        }

        _uiState.update { it.copy(needsMicPermissionForAutoStart = false) }
        startAutomaticGreeting(convId = convId, startListeningAfter = true)
    }

    fun onMicPermissionResult(granted: Boolean) {
        if (granted) {
            _uiState.update { it.copy(needsMicPermissionForAutoStart = false, errorMessage = null) }
            val convId = _uiState.value.activeConversationId
            if (preferences.value.autoStartConversation &&
                convId.isNotBlank() &&
                !greetedConversationIds.contains(convId) &&
                _uiState.value.voiceSessionState == VoiceSessionState.IDLE
            ) {
                startAutomaticGreeting(convId = convId, startListeningAfter = true)
            } else {
                onMicPressed()
            }
        } else {
            // Never secretly record when microphone permission is denied
            _uiState.update {
                it.copy(
                    needsMicPermissionForAutoStart = false,
                    voiceSessionState = VoiceSessionState.ERROR,
                    audioAmplitude = 0.08f,
                    errorMessage = "Microphone access is required for voice conversations so SAATHI can hear you. Please allow microphone permission or switch to Text Chat."
                )
            }
        }
    }

    fun startAutomaticGreeting(
        convId: String = _uiState.value.activeConversationId,
        startListeningAfter: Boolean = true
    ) {
        if (!isAppInForeground || isSessionEndedByUser) return
        val targetConvId = convId.ifBlank { _uiState.value.activeConversationId }
        if (targetConvId.isBlank() || !greetedConversationIds.add(targetConvId)) {
            return
        }

        autoTurnJob?.cancel()
        autoTurnJob = null
        activeGreetingJob?.cancel()

        activeGreetingJob = viewModelScope.launch {
            val existingMsgs = dao.getMessagesOnce(targetConvId)
            if (existingMsgs.isNotEmpty()) {
                return@launch
            }

            _uiState.update {
                it.copy(
                    voiceSessionState = VoiceSessionState.GREETING,
                    needsMicPermissionForAutoStart = false,
                    errorMessage = null,
                    streamingReply = ""
                )
            }

            val prefs = preferences.value
            val greetingText = prefs.preferredLanguage.spokenGreeting
            val actualConvId = ensureActiveConversationExists(greetingText, targetConvId)
            greetedConversationIds.add(actualConvId)

            val now = System.currentTimeMillis()
            val msgId = newCleanId("msg")
            val greetingMsg = MessageEntity(
                id = msgId,
                conversationId = actualConvId,
                userId = currentUserId,
                role = "model",
                text = greetingText,
                isVoice = true,
                timestampEpochMs = now
            )
            dao.upsertMessage(greetingMsg)
            updateConversationHeader(actualConvId, greetingText, prefs.preferredLanguage, now)

            if (canUseCloudSync) {
                launch {
                    firestoreRepo.upsertConversation(
                        conversationId = actualConvId,
                        title = dao.getConversationById(actualConvId)?.title ?: greetingText.take(40),
                        language = prefs.preferredLanguage,
                        lastMessagePreview = greetingText.take(120)
                    )
                    firestoreRepo.addMessage(actualConvId, msgId, "model", greetingText, true)
                }
            }

            _uiState.update { state ->
                if (state.activeConversationId == actualConvId) {
                    state.copy(
                        voiceSessionState = VoiceSessionState.GREETING,
                        lastAssistantReply = greetingText
                    )
                } else state
            }

            if (prefs.autoPlayVoice && !_uiState.value.isMuted) {
                speakWithElevenLabs(
                    text = greetingText,
                    prefs = prefs,
                    isGreeting = true,
                    startListeningAfter = startListeningAfter
                )
            } else if (startListeningAfter &&
                isAppInForeground &&
                !isSessionEndedByUser &&
                (prefs.autoTurnTaking || prefs.autoStartConversation) &&
                audioRecorder.hasRecordPermission()
            ) {
                startVoiceListening()
            } else {
                _uiState.update {
                    it.copy(
                        voiceSessionState = VoiceSessionState.IDLE,
                        audioAmplitude = 0.08f
                    )
                }
            }
        }
    }

    fun onMicPressed() {
        val currentState = _uiState.value.voiceSessionState
        when (currentState) {
            VoiceSessionState.GREETING,
            VoiceSessionState.SPEAKING,
            VoiceSessionState.THINKING -> {
                // User interrupted SAATHI while greeting/speaking/thinking -> cancel and immediately listen
                interruptCurrentResponse(markInterrupted = true)
                startVoiceListening()
            }
            VoiceSessionState.LISTENING -> {
                if (_uiState.value.isRecordingScribe) {
                    stopScribeAndSubmit()
                } else {
                    val stopped = speechRecognizer.stopListening()
                    if (!stopped) {
                        _uiState.update {
                            it.copy(
                                voiceSessionState = VoiceSessionState.IDLE,
                                audioAmplitude = 0.08f
                            )
                        }
                    }
                }
            }
            VoiceSessionState.ENDED -> {
                isSessionEndedByUser = false
                val newId = newCleanId("conv")
                activeConvIdFlow.value = newId
                _uiState.update {
                    it.copy(
                        activeConversationId = newId,
                        voiceSessionState = VoiceSessionState.IDLE,
                        liveTranscript = "",
                        streamingReply = "",
                        lastAssistantReply = "",
                        errorMessage = null,
                        needsMicPermissionForAutoStart = false
                    )
                }
                startVoiceListening()
            }
            VoiceSessionState.IDLE,
            VoiceSessionState.INTERRUPTED,
            VoiceSessionState.ERROR -> {
                isSessionEndedByUser = false
                _uiState.update { it.copy(needsMicPermissionForAutoStart = false) }
                startVoiceListening()
            }
        }
    }

    fun interruptSpeech() {
        interruptCurrentResponse(markInterrupted = true)
    }

    fun interruptCurrentResponse(markInterrupted: Boolean = false) {
        voiceTurnSequence++
        ttsTurnToken++
        autoTurnJob?.cancel()
        autoTurnJob = null
        activeGreetingJob?.cancel()
        activeGreetingJob = null
        activeSttJob?.cancel()
        activeSttJob = null
        activeAiJob?.cancel()
        activeAiJob = null
        activePreviewJob?.cancel()
        activePreviewJob = null
        speakingPulseJob?.cancel()
        speakingPulseJob = null
        voiceBackendClient.cancelActiveSttRequest()
        voiceBackendClient.cancelActiveTtsRequest()
        audioPlayer.stop()
        speechRecognizer.cancel()
        audioRecorder.stopAndDiscard()
        _uiState.update {
            it.copy(
                voiceSessionState = if (markInterrupted) VoiceSessionState.INTERRUPTED else VoiceSessionState.IDLE,
                isTextThinking = false,
                streamingReply = "",
                audioAmplitude = 0.08f,
                isRecordingScribe = false,
                previewingVoiceId = null
            )
        }
    }

    /**
     * Stops active voice listening or speaking when switching to Text Chat while preserving
     * the active conversation and any in-flight text generation.
     */
    fun stopVoiceOnlyForModeSwitch() {
        voiceTurnSequence++
        ttsTurnToken++
        autoTurnJob?.cancel()
        autoTurnJob = null
        activeGreetingJob?.cancel()
        activeGreetingJob = null
        activeSttJob?.cancel()
        activeSttJob = null
        activePreviewJob?.cancel()
        activePreviewJob = null
        speakingPulseJob?.cancel()
        speakingPulseJob = null
        voiceBackendClient.cancelActiveSttRequest()
        voiceBackendClient.cancelActiveTtsRequest()
        audioPlayer.stop()
        speechRecognizer.cancel()
        audioRecorder.stopAndDiscard()
        _uiState.update { state ->
            state.copy(
                voiceSessionState = if (state.voiceSessionState == VoiceSessionState.ENDED) {
                    VoiceSessionState.ENDED
                } else {
                    VoiceSessionState.IDLE
                },
                audioAmplitude = 0.08f,
                isRecordingScribe = false,
                previewingVoiceId = null
            )
        }
    }

    private fun startVoiceListening(languageOverride: SaathiLanguage? = null) {
        if (!isAppInForeground) {
            Log.d(TAG, "Skipping startVoiceListening while app is backgrounded")
            return
        }
        if (isSessionEndedByUser) {
            Log.d(TAG, "Skipping startVoiceListening because conversation was ended by user")
            return
        }
        if (_uiState.value.voiceSessionState == VoiceSessionState.LISTENING ||
            speechRecognizer.isSessionActive() ||
            _uiState.value.isRecordingScribe
        ) {
            Log.d(TAG, "Preventing concurrent voice listening start")
            return
        }

        autoTurnJob?.cancel()
        autoTurnJob = null
        val prefs = preferences.value
        val activeLanguage = languageOverride ?: pendingLanguageOverride ?: prefs.preferredLanguage
        val turnToken = ++voiceTurnSequence
        sttListeningStartAtNs = latencyRecorder.nowNs()

        _uiState.update {
            it.copy(
                voiceSessionState = VoiceSessionState.LISTENING,
                liveTranscript = "",
                errorMessage = null,
                voiceBackendNotice = null,
                needsMicPermissionForAutoStart = false,
                audioAmplitude = 0.14f
            )
        }

        if (prefs.sttProvider == SttProvider.ELEVENLABS_SCRIBE) {
            if (prefs.voiceBackendUrl.isBlank()) {
                _uiState.update {
                    it.copy(
                        voiceSessionState = VoiceSessionState.ERROR,
                        audioAmplitude = 0.08f,
                        errorMessage = "ElevenLabs Scribe v3 Live requires your Voice Backend URL. Configure it in Voice Settings or switch to Device Speech Recognition."
                    )
                }
                return
            }
            if (!audioRecorder.hasRecordPermission()) {
                _uiState.update {
                    it.copy(
                        voiceSessionState = VoiceSessionState.ERROR,
                        audioAmplitude = 0.08f,
                        errorMessage = "Microphone permission is needed to listen. Please allow microphone access or use Text Chat."
                    )
                }
                return
            }
            _uiState.update { it.copy(isRecordingScribe = true) }
            audioRecorder.startRecording(
                scope = viewModelScope,
                onAmplitude = { amp ->
                    if (turnToken == voiceTurnSequence) {
                        _uiState.update { it.copy(audioAmplitude = amp) }
                    }
                },
                onError = { err ->
                    if (turnToken == voiceTurnSequence && isAppInForeground) {
                        _uiState.update {
                            it.copy(
                                voiceSessionState = VoiceSessionState.ERROR,
                                isRecordingScribe = false,
                                audioAmplitude = 0.08f,
                                errorMessage = err
                            )
                        }
                    }
                },
                onSilenceDetected = {
                    if (turnToken == voiceTurnSequence &&
                        isAppInForeground &&
                        preferences.value.autoTurnTaking &&
                        _uiState.value.isRecordingScribe
                    ) {
                        viewModelScope.launch { stopScribeAndSubmit() }
                    }
                }
            )
        } else {
            val started = speechRecognizer.startListening(
                language = activeLanguage,
                onReady = {
                    if (turnToken == voiceTurnSequence) {
                        sttListeningStartAtNs = latencyRecorder.nowNs()
                        _uiState.update { it.copy(audioAmplitude = 0.18f) }
                    }
                },
                onRmsChanged = { rms ->
                    if (turnToken == voiceTurnSequence) {
                        _uiState.update { it.copy(audioAmplitude = rms) }
                    }
                },
                onPartialResult = { partial ->
                    if (turnToken == voiceTurnSequence) {
                        val displayPartial = if (activeLanguage == SaathiLanguage.TELUGU) {
                            SaathiVoiceCatalog.ensureRomanTeluguDisplay(partial)
                        } else partial
                        _uiState.update { it.copy(liveTranscript = displayPartial) }
                    }
                },
                onFinalResult = { finalText ->
                    if (turnToken != voiceTurnSequence || turnToken == lastDeliveredVoiceToken) {
                        return@startListening
                    }
                    lastDeliveredVoiceToken = turnToken
                    lastMeasuredSttMs = TurnLatencyMetrics.nanosToMs(
                        latencyRecorder.nowNs() - sttListeningStartAtNs
                    )
                    val displayFinal = if (activeLanguage == SaathiLanguage.TELUGU) {
                        SaathiVoiceCatalog.ensureRomanTeluguDisplay(finalText)
                    } else finalText
                    _uiState.update {
                        it.copy(
                            liveTranscript = displayFinal,
                            audioAmplitude = 0.1f
                        )
                    }
                    submitUserTurn(displayFinal, isVoiceMode = true)
                },
                onError = { errorMsg ->
                    if (turnToken == voiceTurnSequence && isAppInForeground) {
                        _uiState.update {
                            it.copy(
                                voiceSessionState = VoiceSessionState.ERROR,
                                errorMessage = errorMsg,
                                audioAmplitude = 0.08f
                            )
                        }
                    }
                }
            )
            if (!started && _uiState.value.voiceSessionState == VoiceSessionState.LISTENING && !speechRecognizer.isSessionActive()) {
                _uiState.update {
                    it.copy(
                        voiceSessionState = VoiceSessionState.IDLE,
                        audioAmplitude = 0.08f
                    )
                }
            }
        }
    }

    private fun stopScribeAndSubmit() {
        if (!_uiState.value.isRecordingScribe) return
        val turnToken = voiceTurnSequence
        val scribeStartNs = latencyRecorder.nowNs()
        _uiState.update {
            it.copy(
                isRecordingScribe = false,
                voiceSessionState = VoiceSessionState.THINKING,
                audioAmplitude = 0.12f
            )
        }
        val wavBytes = audioRecorder.stopAndBuildWav()
        if (wavBytes == null) {
            _uiState.update {
                it.copy(
                    voiceSessionState = VoiceSessionState.ERROR,
                    audioAmplitude = 0.08f,
                    errorMessage = "Recording was too short. Tap the mic and speak clearly."
                )
            }
            return
        }
        val prefs = preferences.value
        activeSttJob?.cancel()
        activeSttJob = viewModelScope.launch {
            try {
                val sttRes = voiceBackendClient.transcribeWithScribe(
                    prefs.voiceBackendUrl,
                    wavBytes,
                    prefs.preferredLanguage
                )
                if (turnToken != voiceTurnSequence || !isAppInForeground) {
                    return@launch
                }
                when (sttRes) {
                    is VoiceBackendResult.Success -> {
                        lastMeasuredSttMs = TurnLatencyMetrics.nanosToMs(
                            latencyRecorder.nowNs() - scribeStartNs
                        )
                        val displayStt = if (prefs.preferredLanguage == SaathiLanguage.TELUGU) {
                            SaathiVoiceCatalog.ensureRomanTeluguDisplay(sttRes.data)
                        } else sttRes.data
                        _uiState.update { it.copy(liveTranscript = displayStt) }
                        submitUserTurn(displayStt, isVoiceMode = true)
                    }
                    is VoiceBackendResult.NotConfigured -> {
                        _uiState.update {
                            it.copy(
                                voiceSessionState = VoiceSessionState.ERROR,
                                audioAmplitude = 0.08f,
                                errorMessage = sttRes.message
                            )
                        }
                    }
                    is VoiceBackendResult.Error -> {
                        _uiState.update {
                            it.copy(
                                voiceSessionState = VoiceSessionState.ERROR,
                                audioAmplitude = 0.08f,
                                errorMessage = sttRes.message
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                activeSttJob = null
            }
        }
    }

    fun sendTextMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return
        submitUserTurn(text, isVoiceMode = false)
    }

    fun retryLastFailedMessage() {
        val state = _uiState.value
        val prompt = state.lastFailedPrompt ?: return
        val failedConvId = state.lastFailedConversationId ?: state.activeConversationId
        if (failedConvId.isNotBlank() && failedConvId != state.activeConversationId) {
            activeConvIdFlow.value = failedConvId
            _uiState.update { it.copy(activeConversationId = failedConvId) }
        }
        _uiState.update {
            it.copy(
                errorMessage = null,
                lastFailedPrompt = null,
                lastFailedConversationId = null
            )
        }
        submitUserTurn(
            userText = prompt,
            isVoiceMode = state.lastFailedWasVoice,
            isRetry = true
        )
    }

    private fun submitUserTurn(userText: String, isVoiceMode: Boolean, isRetry: Boolean = false) {
        val rawClean = userText.trim()
        if (rawClean.isBlank()) return
        val cleanText = if (preferences.value.preferredLanguage == SaathiLanguage.TELUGU) {
            SaathiVoiceCatalog.ensureRomanTeluguDisplay(rawClean)
        } else {
            rawClean
        }
        val nowMs = System.currentTimeMillis()
        val normalized = cleanText.lowercase()

        if (!isRetry) {
            if (isVoiceMode) {
                if (normalized == lastSubmittedVoiceNormalized &&
                    (nowMs - lastSubmittedVoiceAtMs) < 1800L &&
                    activeAiJob?.isActive == true
                ) {
                    Log.d(TAG, "Ignoring duplicate voice transcript submission")
                    return
                }
                lastSubmittedVoiceNormalized = normalized
                lastSubmittedVoiceAtMs = nowMs
            } else {
                if (_uiState.value.isTextThinking && activeAiJob?.isActive == true) {
                    Log.d(TAG, "Ignoring concurrent text submission while SAATHI is already responding")
                    return
                }
                if (normalized == lastSubmittedTextNormalized &&
                    (nowMs - lastSubmittedTextAtMs) < 1500L &&
                    activeAiJob?.isActive == true
                ) {
                    Log.d(TAG, "Ignoring duplicate rapid text submission")
                    return
                }
                lastSubmittedTextNormalized = normalized
                lastSubmittedTextAtMs = nowMs
            }
        }

        val userActionAtNs = latencyRecorder.nowNs()
        val turnId = turnMetricsCounter.incrementAndGet()
        val capturedSttMs = if (isVoiceMode) lastMeasuredSttMs else 0.0
        lastMeasuredSttMs = 0.0

        isSessionEndedByUser = false
        val targetConvId = _uiState.value.activeConversationId.ifBlank { newCleanId("conv") }
        greetedConversationIds.add(targetConvId)

        autoTurnJob?.cancel()
        activeGreetingJob?.cancel()
        activeAiJob?.cancel()
        voiceBackendClient.cancelActiveTtsRequest()
        audioPlayer.stop()

        // Update UI state immediately before launching coroutine so typing/thinking feedback is zero-latency
        _uiState.update { state ->
            state.copy(
                activeConversationId = targetConvId,
                voiceSessionState = if (isVoiceMode) VoiceSessionState.THINKING else state.voiceSessionState,
                isTextThinking = !isVoiceMode,
                streamingReply = "",
                errorMessage = null,
                lastFailedPrompt = null,
                lastFailedWasVoice = false,
                lastFailedConversationId = null
            )
        }

        activeAiJob = viewModelScope.launch {
            val viewModelStartAtNs = latencyRecorder.nowNs()
            val convId = ensureActiveConversationExists(cleanText, targetConvId)
            greetedConversationIds.add(convId)
            val prefs = preferences.value

            // Load only recent conversation window + initial greeting (if conversation > 16 turns)
            val recentWindow = dao.getRecentMessagesOnce(convId, limit = 16)
            val existingMessages = if (recentWindow.size >= 16) {
                val firstMsg = dao.getFirstMessageOnce(convId)
                if (firstMsg != null && recentWindow.firstOrNull()?.id != firstMsg.id) {
                    listOf(firstMsg) + recentWindow
                } else {
                    recentWindow
                }
            } else {
                recentWindow
            }

            val alreadyHasTrailingUserMsg = existingMessages.lastOrNull()?.let {
                it.role == "user" && it.text == cleanText
            } == true
            val priorHistory = if (alreadyHasTrailingUserMsg) {
                existingMessages.dropLast(1)
            } else {
                existingMessages
            }
            val activeMemories = if (prefs.memoryEnabled) {
                dao.getRecentMemoriesOnce(currentUserId, limit = 15)
            } else {
                emptyList()
            }

            if (!isRetry || !alreadyHasTrailingUserMsg) {
                val userMsgId = newCleanId("msg")
                val now = System.currentTimeMillis()
                val userMsg = MessageEntity(
                    id = userMsgId,
                    conversationId = convId,
                    userId = currentUserId,
                    role = "user",
                    text = cleanText,
                    isVoice = isVoiceMode,
                    timestampEpochMs = now
                )
                dao.upsertMessage(userMsg)
                updateConversationHeader(convId, cleanText, prefs.preferredLanguage, now)

                if (canUseCloudSync) {
                    launch {
                        val title = knownConversationTitles[convId] ?: cleanText.take(40)
                        firestoreRepo.upsertConversation(
                            conversationId = convId,
                            title = title,
                            language = prefs.preferredLanguage,
                            lastMessagePreview = cleanText.take(120)
                        )
                        firestoreRepo.addMessage(convId, userMsgId, "user", cleanText, isVoiceMode)
                    }
                }
            }

            val historyPreparedAtNs = latencyRecorder.nowNs()
            var geminiStartAtNs = historyPreparedAtNs
            var geminiFirstTokenAtNs = 0L
            var geminiCompleteAtNs = 0L
            var backendReqStartAtNs = 0L
            var backendReqFinishAtNs = 0L
            var ttsFirstAudioAtNs = 0L

            var finalReply = ""
            var chunkEmissionCount = 0
            var prefetchedFirstSentence: String? = null
            var prefetchedFirstSentenceJob: Deferred<VoiceBackendResult<TtsAudioSource>>? = null

            val shouldSpeakReply = isVoiceMode && prefs.autoPlayVoice && !_uiState.value.isMuted
            val activeVoiceOption = if (shouldSpeakReply) {
                resolveActiveVoiceOption(prefs.selectedVoiceGender, prefs.selectedVoiceId)
            } else null

            try {
                aiRepo.streamCompanionReply(
                    userMessage = cleanText,
                    history = priorHistory,
                    language = prefs.preferredLanguage,
                    preferredName = prefs.preferredName.ifBlank {
                        if (isGuestSession) "Friend" else userDisplayName.substringBefore(" ")
                    },
                    memories = activeMemories,
                    memoryEnabled = prefs.memoryEnabled,
                    isVoiceMode = isVoiceMode,
                    onRequestStarted = { ts -> geminiStartAtNs = ts },
                    onFirstToken = { ts -> geminiFirstTokenAtNs = ts },
                    onCompleted = { ts -> geminiCompleteAtNs = ts }
                ).collect { partialReply ->
                    chunkEmissionCount++
                    finalReply = partialReply
                    _uiState.update { state ->
                        if (state.activeConversationId == convId) {
                            state.copy(streamingReply = partialReply)
                        } else state
                    }

                    // Incremental sentence-level TTS prefetching when Gemini streams a multi-sentence voice reply across multiple chunks
                    if (shouldSpeakReply &&
                        prefetchedFirstSentenceJob == null &&
                        chunkEmissionCount >= 2 &&
                        activeVoiceOption != null &&
                        prefs.voiceBackendUrl.isNotBlank()
                    ) {
                        val split = extractIncrementalLeadSentence(partialReply)
                        if (split != null) {
                            val leadSentence = split.first
                            prefetchedFirstSentence = leadSentence
                            prefetchedFirstSentenceJob = async {
                                voiceBackendClient.openTtsAudioSource(
                                    baseUrl = prefs.voiceBackendUrl,
                                    text = leadSentence,
                                    voiceSlot = activeVoiceOption.slot,
                                    voiceId = activeVoiceOption.configuredVoiceId,
                                    modelId = activeVoiceOption.modelId.ifBlank { _uiState.value.activeTtsModel },
                                    language = prefs.preferredLanguage,
                                    speed = prefs.speechSpeed,
                                    isPreview = false,
                                    onBackendRequestStart = { ts -> if (backendReqStartAtNs == 0L) backendReqStartAtNs = ts },
                                    onBackendResponseHeaders = { ts -> if (backendReqFinishAtNs == 0L) backendReqFinishAtNs = ts },
                                    onFirstAudioChunk = { ts -> if (ttsFirstAudioAtNs == 0L) ttsFirstAudioAtNs = ts }
                                )
                            }
                        }
                    }
                }

                if (geminiCompleteAtNs == 0L) {
                    geminiCompleteAtNs = latencyRecorder.nowNs()
                }
                if (geminiFirstTokenAtNs == 0L) {
                    geminiFirstTokenAtNs = geminiCompleteAtNs
                }

                val isStillActiveConv = _uiState.value.activeConversationId == convId
                val replyTime = System.currentTimeMillis()
                val modelMsgId = newCleanId("msg")
                val modelMsg = MessageEntity(
                    id = modelMsgId,
                    conversationId = convId,
                    userId = currentUserId,
                    role = "model",
                    text = finalReply,
                    isVoice = isVoiceMode,
                    timestampEpochMs = replyTime
                )
                dao.upsertMessage(modelMsg)
                updateConversationHeader(convId, finalReply, prefs.preferredLanguage, replyTime)

                if (canUseCloudSync) {
                    launch {
                        val title = knownConversationTitles[convId] ?: cleanText.take(40)
                        firestoreRepo.upsertConversation(
                            conversationId = convId,
                            title = title,
                            language = prefs.preferredLanguage,
                            lastMessagePreview = finalReply.take(120)
                        )
                        firestoreRepo.addMessage(convId, modelMsgId, "model", finalReply, isVoiceMode)
                    }
                }

                // Run background memory extraction after primary reply completes so it never contends with reply streaming
                if (prefs.memoryEnabled && isStillActiveConv) {
                    launch {
                        val candidate = aiRepo.extractMemoryCandidate(cleanText, activeMemories)
                        if (candidate != null && _uiState.value.activeConversationId == convId) {
                            val normalizedCandidate = if (prefs.preferredLanguage == SaathiLanguage.TELUGU) {
                                candidate.copy(content = SaathiVoiceCatalog.ensureRomanTeluguDisplay(candidate.content))
                            } else candidate
                            _uiState.update { it.copy(pendingMemorySuggestion = normalizedCandidate) }
                        }
                    }
                }

                val textMetrics = if (!isVoiceMode) {
                    TurnLatencyMetrics(
                        turnId = turnId,
                        isVoiceMode = false,
                        languageCode = prefs.preferredLanguage.isoCode,
                        userActionAtNs = userActionAtNs,
                        viewModelStartAtNs = viewModelStartAtNs,
                        historyPreparedAtNs = historyPreparedAtNs,
                        geminiStartAtNs = geminiStartAtNs,
                        geminiFirstTokenAtNs = geminiFirstTokenAtNs,
                        geminiCompleteAtNs = geminiCompleteAtNs,
                        succeeded = true
                    ).also { latencyRecorder.recordTurn(it) }
                } else null

                _uiState.update { state ->
                    if (state.activeConversationId == convId) {
                        state.copy(
                            isTextThinking = false,
                            streamingReply = "",
                            lastAssistantReply = finalReply,
                            latestTurnMetrics = textMetrics ?: state.latestTurnMetrics
                        )
                    } else {
                        state.copy(
                            isTextThinking = false,
                            streamingReply = "",
                            latestTurnMetrics = textMetrics ?: state.latestTurnMetrics
                        )
                    }
                }

                if (isVoiceMode && isStillActiveConv && prefs.autoPlayVoice && !_uiState.value.isMuted) {
                    speakWithElevenLabs(
                        text = finalReply,
                        prefs = prefs,
                        isGreeting = false,
                        startListeningAfter = true,
                        prefetchedLeadSentence = prefetchedFirstSentence,
                        prefetchedLeadSourceDeferred = prefetchedFirstSentenceJob,
                        onTtsStageTimestamps = { reqStartNs, reqFinishNs, firstAudioNs, playbackStartNs, segCount ->
                            val effReqStart = if (backendReqStartAtNs > 0L) backendReqStartAtNs else reqStartNs
                            val effReqFinish = if (backendReqFinishAtNs > 0L) backendReqFinishAtNs else reqFinishNs
                            val effFirstAudio = if (ttsFirstAudioAtNs > 0L) ttsFirstAudioAtNs else firstAudioNs
                            val metrics = TurnLatencyMetrics(
                                turnId = turnId,
                                isVoiceMode = true,
                                languageCode = prefs.preferredLanguage.isoCode,
                                userActionAtNs = userActionAtNs,
                                viewModelStartAtNs = viewModelStartAtNs,
                                historyPreparedAtNs = historyPreparedAtNs,
                                geminiStartAtNs = geminiStartAtNs,
                                geminiFirstTokenAtNs = geminiFirstTokenAtNs,
                                geminiCompleteAtNs = geminiCompleteAtNs,
                                sttLatencyMs = capturedSttMs,
                                backendRequestStartAtNs = effReqStart,
                                backendRequestFinishAtNs = effReqFinish,
                                ttsFirstAudioAtNs = effFirstAudio,
                                audioPlaybackStartAtNs = playbackStartNs,
                                segmentCount = segCount,
                                succeeded = true
                            )
                            latencyRecorder.recordTurn(metrics)
                            _uiState.update { it.copy(latestTurnMetrics = metrics) }
                        }
                    )
                } else if (isVoiceMode && isStillActiveConv) {
                    prefetchedFirstSentenceJob?.cancel()
                    val nowNs = latencyRecorder.nowNs()
                    val metrics = TurnLatencyMetrics(
                        turnId = turnId,
                        isVoiceMode = true,
                        languageCode = prefs.preferredLanguage.isoCode,
                        userActionAtNs = userActionAtNs,
                        viewModelStartAtNs = viewModelStartAtNs,
                        historyPreparedAtNs = historyPreparedAtNs,
                        geminiStartAtNs = geminiStartAtNs,
                        geminiFirstTokenAtNs = geminiFirstTokenAtNs,
                        geminiCompleteAtNs = geminiCompleteAtNs,
                        sttLatencyMs = capturedSttMs,
                        audioPlaybackStartAtNs = nowNs,
                        succeeded = true
                    )
                    latencyRecorder.recordTurn(metrics)
                    _uiState.update {
                        it.copy(
                            voiceSessionState = VoiceSessionState.IDLE,
                            latestTurnMetrics = metrics
                        )
                    }
                }
            } catch (e: CancellationException) {
                prefetchedFirstSentenceJob?.cancel()
                _uiState.update { state ->
                    state.copy(
                        isTextThinking = false,
                        streamingReply = "",
                        voiceSessionState = if (
                            state.voiceSessionState == VoiceSessionState.THINKING
                        ) VoiceSessionState.IDLE else state.voiceSessionState
                    )
                }
                throw e
            } catch (e: Exception) {
                prefetchedFirstSentenceJob?.cancel()
                Log.e(TAG, "Companion reply error", e)
                val failNs = latencyRecorder.nowNs()
                val failedMetrics = TurnLatencyMetrics(
                    turnId = turnId,
                    isVoiceMode = isVoiceMode,
                    languageCode = prefs.preferredLanguage.isoCode,
                    userActionAtNs = userActionAtNs,
                    viewModelStartAtNs = viewModelStartAtNs,
                    historyPreparedAtNs = historyPreparedAtNs,
                    geminiStartAtNs = geminiStartAtNs,
                    geminiFirstTokenAtNs = geminiFirstTokenAtNs,
                    geminiCompleteAtNs = failNs,
                    sttLatencyMs = capturedSttMs,
                    succeeded = false
                )
                latencyRecorder.recordTurn(failedMetrics)
                _uiState.update { state ->
                    if (state.activeConversationId == convId) {
                        state.copy(
                            isTextThinking = false,
                            streamingReply = "",
                            voiceSessionState = if (isVoiceMode) VoiceSessionState.ERROR else VoiceSessionState.IDLE,
                            lastFailedPrompt = cleanText,
                            lastFailedWasVoice = isVoiceMode,
                            lastFailedConversationId = convId,
                            errorMessage = e.localizedMessage ?: "SAATHI couldn't respond right now. Please retry.",
                            latestTurnMetrics = failedMetrics
                        )
                    } else {
                        state.copy(isTextThinking = false, streamingReply = "", latestTurnMetrics = failedMetrics)
                    }
                }
            }
        }
    }

    /**
     * Splits a multi-sentence streaming reply into (leadSentence, remainder) only when the lead
     * sentence is a complete, meaningful sentence (>= 36 chars) and at least 16 chars of a
     * second sentence have already arrived, preventing tiny TTS fragments.
     */
    internal fun extractIncrementalLeadSentence(text: String): Pair<String, String>? {
        val trimmed = text.trim()
        if (trimmed.length < 60) return null
        val boundaryRegex = Regex("([.!?।]+)\\s+")
        val match = boundaryRegex.findAll(trimmed).firstOrNull { m ->
            val endIdx = m.range.last + 1
            val firstPart = trimmed.substring(0, endIdx).trim()
            val secondPart = trimmed.substring(endIdx).trim()
            firstPart.length in 36..180 && secondPart.length >= 16 && !firstPart.endsWith("...")
        } ?: return null
        val splitIndex = match.range.last + 1
        val lead = trimmed.substring(0, splitIndex).trim()
        val tail = trimmed.substring(splitIndex).trim()
        return if (lead.isNotBlank() && tail.isNotBlank()) lead to tail else null
    }

    private fun resolveActiveVoiceOption(
        gender: VoiceGender,
        preferredVoiceId: String = ""
    ): ElevenLabsVoiceOption {
        val list = _uiState.value.availableVoices
        val candidate = list.firstOrNull { it.gender == gender }
            ?: SaathiVoiceCatalog.defaultForGender(gender)
        val cleanPrefId = preferredVoiceId.trim()
        return if (
            candidate.configuredVoiceId.isBlank() &&
            cleanPrefId.isNotBlank() &&
            cleanPrefId != SaathiVoiceCatalog.SLOT_FEMALE &&
            cleanPrefId != SaathiVoiceCatalog.SLOT_MALE
        ) {
            candidate.copy(configuredVoiceId = cleanPrefId, configured = true)
        } else {
            candidate
        }
    }

    private suspend fun ensureBackendVoicesLoadedIfNeeded(baseUrl: String, preferredVoiceId: String = "") {
        val cleanUrl = baseUrl.trim()
        if (cleanUrl.isBlank()) return
        val cleanPref = preferredVoiceId.trim()
        val hasConcretePreferredVoiceId = cleanPref.isNotBlank() &&
            cleanPref != SaathiVoiceCatalog.SLOT_FEMALE &&
            cleanPref != SaathiVoiceCatalog.SLOT_MALE
        if (hasConcretePreferredVoiceId) {
            // Do not block the critical TTS path when a concrete Voice ID is already selected;
            // refresh metadata asynchronously in the background if not yet populated.
            if (_uiState.value.availableVoices.none { it.configured && it.configuredVoiceId.isNotBlank() }) {
                refreshBackendVoices(cleanUrl)
            }
            return
        }
        if (_uiState.value.availableVoices.any { it.configured && it.configuredVoiceId.isNotBlank() }) return
        when (val res = voiceBackendClient.fetchConfiguredVoices(cleanUrl)) {
            is VoiceBackendResult.Success -> {
                _uiState.update {
                    it.copy(
                        availableVoices = res.data.voices,
                        activeTtsModel = res.data.ttsModel,
                        activeSttModel = res.data.sttModel
                    )
                }
            }
            else -> {}
        }
    }

    private suspend fun speakWithElevenLabs(
        text: String,
        prefs: UserPreferencesState,
        isGreeting: Boolean = false,
        startListeningAfter: Boolean = true,
        prefetchedLeadSentence: String? = null,
        prefetchedLeadSourceDeferred: Deferred<VoiceBackendResult<TtsAudioSource>>? = null,
        onTtsStageTimestamps: (
            reqStartNs: Long,
            reqFinishNs: Long,
            firstAudioNs: Long,
            playbackStartNs: Long,
            segmentCount: Int
        ) -> Unit = { _, _, _, _, _ -> }
    ) {
        activePreviewJob?.cancel()
        activePreviewJob = null
        val ttsToken = ++ttsTurnToken
        ensureBackendVoicesLoadedIfNeeded(prefs.voiceBackendUrl, prefs.selectedVoiceId)
        val latestPrefs = preferences.value
        val voice = resolveActiveVoiceOption(latestPrefs.selectedVoiceGender, latestPrefs.selectedVoiceId)

        if (!isAppInForeground || isSessionEndedByUser || _uiState.value.isMuted) {
            prefetchedLeadSourceDeferred?.cancel()
            _uiState.update {
                it.copy(
                    voiceSessionState = if (isSessionEndedByUser) VoiceSessionState.ENDED else VoiceSessionState.IDLE,
                    audioAmplitude = 0.08f
                )
            }
            return
        }

        val activeSpeakingState = if (isGreeting) VoiceSessionState.GREETING else VoiceSessionState.SPEAKING
        _uiState.update { it.copy(voiceSessionState = activeSpeakingState, previewingVoiceId = null) }
        startSpeakingPulse()

        var reqStartNs = 0L
        var reqFinishNs = 0L
        var firstAudioNs = 0L

        val remainingTail = if (
            !prefetchedLeadSentence.isNullOrBlank() &&
            prefetchedLeadSourceDeferred != null &&
            text.trim().startsWith(prefetchedLeadSentence.trim())
        ) {
            text.trim().removePrefix(prefetchedLeadSentence.trim()).trim()
        } else {
            ""
        }

        val useIncrementalTwoSegmentPlayback = remainingTail.length >= 16 && prefetchedLeadSourceDeferred != null
        val firstSegmentResult = if (useIncrementalTwoSegmentPlayback) {
            prefetchedLeadSourceDeferred!!.await()
        } else {
            prefetchedLeadSourceDeferred?.cancel()
            voiceBackendClient.openTtsAudioSource(
                baseUrl = latestPrefs.voiceBackendUrl,
                text = text,
                voiceSlot = voice.slot,
                voiceId = voice.configuredVoiceId,
                modelId = voice.modelId.ifBlank { _uiState.value.activeTtsModel },
                language = latestPrefs.preferredLanguage,
                speed = latestPrefs.speechSpeed,
                isPreview = false,
                onBackendRequestStart = { ts -> reqStartNs = ts },
                onBackendResponseHeaders = { ts -> reqFinishNs = ts },
                onFirstAudioChunk = { ts -> firstAudioNs = ts }
            )
        }

        // Start prefetching Segment 2 while Segment 1 begins playing
        val secondSegmentDeferred: Deferred<VoiceBackendResult<TtsAudioSource>>? =
            if (useIncrementalTwoSegmentPlayback && firstSegmentResult is VoiceBackendResult.Success) {
                viewModelScope.async {
                    voiceBackendClient.openTtsAudioSource(
                        baseUrl = latestPrefs.voiceBackendUrl,
                        text = remainingTail,
                        voiceSlot = voice.slot,
                        voiceId = voice.configuredVoiceId,
                        modelId = voice.modelId.ifBlank { _uiState.value.activeTtsModel },
                        language = latestPrefs.preferredLanguage,
                        speed = latestPrefs.speechSpeed,
                        isPreview = false
                    )
                }
            } else null

        when (firstSegmentResult) {
            is VoiceBackendResult.Success -> {
                if (ttsToken != ttsTurnToken || !isAppInForeground || isSessionEndedByUser || _uiState.value.isMuted) {
                    secondSegmentDeferred?.cancel()
                    (firstSegmentResult.data as? TtsAudioSource.ProgressiveStream)?.closeQuietly()
                    speakingPulseJob?.cancel()
                    _uiState.update {
                        it.copy(
                            voiceSessionState = if (isSessionEndedByUser) VoiceSessionState.ENDED else VoiceSessionState.IDLE,
                            audioAmplitude = 0.08f
                        )
                    }
                    return
                }

                val finishPlaybackAndTransition = {
                    if (ttsToken == ttsTurnToken && !isSessionEndedByUser) {
                        speakingPulseJob?.cancel()
                        val shouldAutoListen = isAppInForeground &&
                            !isSessionEndedByUser &&
                            startListeningAfter &&
                            (preferences.value.autoTurnTaking || (isGreeting && preferences.value.autoStartConversation)) &&
                            !_uiState.value.isMuted &&
                            audioRecorder.hasRecordPermission()

                        if (shouldAutoListen) {
                            startVoiceListening()
                        } else {
                            _uiState.update {
                                it.copy(
                                    voiceSessionState = VoiceSessionState.IDLE,
                                    audioAmplitude = 0.08f
                                )
                            }
                        }
                    }
                }

                val stageTimestampsReported = java.util.concurrent.atomic.AtomicBoolean(false)
                audioPlayer.playAudioSource(
                    source = firstSegmentResult.data,
                    speed = latestPrefs.speechSpeed,
                    volume = latestPrefs.speechVolume,
                    onFirstAudioReady = {
                        if (stageTimestampsReported.compareAndSet(false, true)) {
                            val playStartNs = latencyRecorder.nowNs()
                            onTtsStageTimestamps(
                                reqStartNs,
                                reqFinishNs,
                                firstAudioNs,
                                playStartNs,
                                if (secondSegmentDeferred != null) 2 else 1
                            )
                        }
                    },
                    onCompletion = {
                        if (ttsToken != ttsTurnToken || isSessionEndedByUser) return@playAudioSource
                        if (secondSegmentDeferred == null) {
                            finishPlaybackAndTransition()
                        } else {
                            viewModelScope.launch {
                                val secondRes = secondSegmentDeferred.await()
                                if (ttsToken != ttsTurnToken || !isAppInForeground || isSessionEndedByUser || _uiState.value.isMuted) {
                                    (secondRes as? VoiceBackendResult.Success)?.let {
                                        (it.data as? TtsAudioSource.ProgressiveStream)?.closeQuietly()
                                    }
                                    return@launch
                                }
                                if (secondRes is VoiceBackendResult.Success) {
                                    audioPlayer.playAudioSource(
                                        source = secondRes.data,
                                        speed = latestPrefs.speechSpeed,
                                        volume = latestPrefs.speechVolume,
                                        onCompletion = { finishPlaybackAndTransition() },
                                        onInterrupted = {
                                            if (ttsToken == ttsTurnToken) {
                                                speakingPulseJob?.cancel()
                                                _uiState.update {
                                                    it.copy(
                                                        voiceSessionState = VoiceSessionState.INTERRUPTED,
                                                        audioAmplitude = 0.08f
                                                    )
                                                }
                                            }
                                        },
                                        onError = { err ->
                                            if (ttsToken == ttsTurnToken) {
                                                speakingPulseJob?.cancel()
                                                _uiState.update {
                                                    it.copy(
                                                        voiceSessionState = VoiceSessionState.ERROR,
                                                        audioAmplitude = 0.08f,
                                                        errorMessage = err
                                                    )
                                                }
                                            }
                                        }
                                    )
                                } else {
                                    finishPlaybackAndTransition()
                                }
                            }
                        }
                    },
                    onInterrupted = {
                        secondSegmentDeferred?.cancel()
                        if (ttsToken != ttsTurnToken) return@playAudioSource
                        speakingPulseJob?.cancel()
                        _uiState.update {
                            it.copy(
                                voiceSessionState = VoiceSessionState.INTERRUPTED,
                                audioAmplitude = 0.08f
                            )
                        }
                    },
                    onError = { err ->
                        secondSegmentDeferred?.cancel()
                        if (ttsToken != ttsTurnToken) return@playAudioSource
                        speakingPulseJob?.cancel()
                        _uiState.update {
                            it.copy(
                                voiceSessionState = VoiceSessionState.ERROR,
                                audioAmplitude = 0.08f,
                                errorMessage = err
                            )
                        }
                    }
                )
            }
            is VoiceBackendResult.NotConfigured -> {
                if (ttsToken != ttsTurnToken) return
                speakingPulseJob?.cancel()
                val nowNs = latencyRecorder.nowNs()
                onTtsStageTimestamps(reqStartNs, reqFinishNs, firstAudioNs, nowNs, 1)
                val shouldListenAnyway = isGreeting &&
                    startListeningAfter &&
                    isAppInForeground &&
                    !isSessionEndedByUser &&
                    preferences.value.autoStartConversation &&
                    audioRecorder.hasRecordPermission()
                if (shouldListenAnyway) {
                    startVoiceListening()
                    _uiState.update { it.copy(voiceBackendNotice = firstSegmentResult.message) }
                } else {
                    _uiState.update {
                        it.copy(
                            voiceSessionState = VoiceSessionState.IDLE,
                            audioAmplitude = 0.08f,
                            voiceBackendNotice = firstSegmentResult.message
                        )
                    }
                }
            }
            is VoiceBackendResult.Error -> {
                if (ttsToken != ttsTurnToken) return
                speakingPulseJob?.cancel()
                _uiState.update {
                    it.copy(
                        voiceSessionState = VoiceSessionState.ERROR,
                        audioAmplitude = 0.08f,
                        errorMessage = firstSegmentResult.message
                    )
                }
            }
        }
    }

    private fun startSpeakingPulse() {
        speakingPulseJob?.cancel()
        speakingPulseJob = viewModelScope.launch {
            var phase = 0f
            while (true) {
                phase += 0.35f
                val pulse = 0.38f + 0.38f * kotlin.math.abs(kotlin.math.sin(phase))
                _uiState.update { it.copy(audioAmplitude = pulse) }
                delay(70L)
            }
        }
    }

    fun previewVoice(voiceSlotOrId: String) {
        val prefs = preferences.value
        val voice = _uiState.value.availableVoices.firstOrNull {
            it.slot.equals(voiceSlotOrId, ignoreCase = true) ||
                it.id.equals(voiceSlotOrId, ignoreCase = true)
        } ?: SaathiVoiceCatalog.findById(voiceSlotOrId)

        val alreadyPreviewingSameVoice = _uiState.value.previewingVoiceId == voice.slot &&
            (activePreviewJob?.isActive == true || audioPlayer.isPlaying())
        if (alreadyPreviewingSameVoice) {
            stopVoicePreview()
            return
        }

        val previewToken = ++ttsTurnToken
        activePreviewJob?.cancel()
        voiceBackendClient.cancelActiveTtsRequest()
        audioPlayer.stop()
        _uiState.update {
            it.copy(
                previewingVoiceId = voice.slot,
                errorMessage = null,
                voiceBackendNotice = null
            )
        }

        activePreviewJob = viewModelScope.launch {
            var resolvedVoice = voice
            if (resolvedVoice.configuredVoiceId.isBlank()) {
                val prefId = SaathiVoiceCatalog.extractVoiceId(prefs.selectedVoiceId)
                val matchesSelectedGender = prefs.selectedVoiceGender.slotKey.equals(resolvedVoice.slot, ignoreCase = true)
                if (matchesSelectedGender &&
                    prefId.isNotBlank() &&
                    prefId != SaathiVoiceCatalog.SLOT_FEMALE &&
                    prefId != SaathiVoiceCatalog.SLOT_MALE
                ) {
                    resolvedVoice = resolvedVoice.copy(id = prefId, configuredVoiceId = prefId, configured = true)
                } else {
                    ensureBackendVoicesLoadedIfNeeded(prefs.voiceBackendUrl)
                    resolvedVoice = _uiState.value.availableVoices.firstOrNull {
                        it.slot.equals(voiceSlotOrId, ignoreCase = true) ||
                            it.id.equals(voiceSlotOrId, ignoreCase = true)
                    } ?: resolvedVoice
                }
            }
            val sampleText = prefs.preferredLanguage.sampleReply
            when (
                val res = voiceBackendClient.openTtsAudioSource(
                    baseUrl = prefs.voiceBackendUrl,
                    text = sampleText,
                    voiceSlot = resolvedVoice.slot,
                    voiceId = resolvedVoice.configuredVoiceId,
                    modelId = resolvedVoice.modelId.ifBlank { _uiState.value.activeTtsModel },
                    language = prefs.preferredLanguage,
                    speed = prefs.speechSpeed,
                    isPreview = true
                )
            ) {
                is VoiceBackendResult.Success -> {
                    if (previewToken != ttsTurnToken || !isAppInForeground) {
                        (res.data as? TtsAudioSource.ProgressiveStream)?.closeQuietly()
                        _uiState.update { it.copy(previewingVoiceId = null) }
                        return@launch
                    }
                    audioPlayer.playAudioSource(
                        source = res.data,
                        speed = prefs.speechSpeed,
                        volume = prefs.speechVolume,
                        onCompletion = {
                            if (previewToken == ttsTurnToken) {
                                _uiState.update { it.copy(previewingVoiceId = null) }
                            }
                        },
                        onInterrupted = {
                            if (previewToken == ttsTurnToken) {
                                _uiState.update { it.copy(previewingVoiceId = null) }
                            }
                        },
                        onError = { err ->
                            if (previewToken == ttsTurnToken) {
                                _uiState.update { it.copy(previewingVoiceId = null, errorMessage = err) }
                            }
                        }
                    )
                }
                is VoiceBackendResult.NotConfigured -> {
                    if (previewToken == ttsTurnToken) {
                        _uiState.update {
                            it.copy(
                                previewingVoiceId = null,
                                voiceBackendNotice = res.message
                            )
                        }
                    }
                }
                is VoiceBackendResult.Error -> {
                    if (previewToken == ttsTurnToken) {
                        _uiState.update {
                            it.copy(
                                previewingVoiceId = null,
                                voiceBackendNotice = res.message
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stopVoicePreview() {
        ttsTurnToken++
        activePreviewJob?.cancel()
        activePreviewJob = null
        voiceBackendClient.cancelActiveTtsRequest()
        audioPlayer.stop()
        _uiState.update { it.copy(previewingVoiceId = null) }
    }

    fun refreshBackendVoices(urlOverride: String? = null) {
        val url = (urlOverride ?: preferences.value.voiceBackendUrl).trim()
        if (url.isBlank()) return
        viewModelScope.launch {
            when (val res = voiceBackendClient.fetchConfiguredVoices(url)) {
                is VoiceBackendResult.Success -> {
                    _uiState.update {
                        it.copy(
                            availableVoices = res.data.voices,
                            activeTtsModel = res.data.ttsModel,
                            activeSttModel = res.data.sttModel
                        )
                    }
                }
                else -> {}
            }
        }
    }

    fun searchIndianLibraryVoices(gender: VoiceGender, query: String = "") {
        val url = preferences.value.voiceBackendUrl.trim()
        if (url.isBlank()) {
            _uiState.update {
                it.copy(voiceBackendNotice = "Enter and test your Voice Backend URL first to browse the ElevenLabs Indian Voice Library.")
            }
            return
        }
        _uiState.update { it.copy(isSearchingLibrary = true, voiceBackendNotice = null) }
        viewModelScope.launch {
            when (val res = voiceBackendClient.searchIndianVoiceLibrary(url, gender, query)) {
                is VoiceBackendResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSearchingLibrary = false,
                            voiceLibraryCandidates = res.data
                        )
                    }
                }
                is VoiceBackendResult.NotConfigured -> {
                    _uiState.update { it.copy(isSearchingLibrary = false, voiceBackendNotice = res.message) }
                }
                is VoiceBackendResult.Error -> {
                    _uiState.update { it.copy(isSearchingLibrary = false, voiceBackendNotice = res.message) }
                }
            }
        }
    }

    fun configureBackendVoice(gender: VoiceGender, voiceId: String, voiceName: String) {
        val url = preferences.value.voiceBackendUrl.trim()
        val cleanVoiceId = SaathiVoiceCatalog.extractVoiceId(voiceId)
        if (url.isBlank() || cleanVoiceId.isBlank()) return
        _uiState.update { it.copy(isSavingBackendVoice = true, voiceBackendNotice = null) }
        viewModelScope.launch {
            when (val res = voiceBackendClient.updateBackendVoiceConfig(url, gender, cleanVoiceId, voiceName)) {
                is VoiceBackendResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSavingBackendVoice = false,
                            availableVoices = res.data.voices,
                            activeTtsModel = res.data.ttsModel,
                            activeSttModel = res.data.sttModel,
                            voiceBackendNotice = null
                        )
                    }
                    selectVoice(cleanVoiceId, gender)
                }
                is VoiceBackendResult.NotConfigured -> {
                    _uiState.update { it.copy(isSavingBackendVoice = false, voiceBackendNotice = res.message) }
                }
                is VoiceBackendResult.Error -> {
                    _uiState.update { it.copy(isSavingBackendVoice = false, voiceBackendNotice = res.message) }
                }
            }
        }
    }

    fun testVoiceBackendHealth(url: String) {
        _uiState.update { it.copy(healthState = BackendHealthState.Checking) }
        viewModelScope.launch {
            prefsRepo.setVoiceBackendUrl(url)
            when (val res = voiceBackendClient.checkHealth(url)) {
                is VoiceBackendResult.Success -> {
                    _uiState.update {
                        it.copy(healthState = BackendHealthState.Ready(res.data), voiceBackendNotice = null)
                    }
                    refreshBackendVoices(url)
                }
                is VoiceBackendResult.NotConfigured -> _uiState.update {
                    it.copy(healthState = BackendHealthState.Failed(res.message))
                }
                is VoiceBackendResult.Error -> _uiState.update {
                    it.copy(healthState = BackendHealthState.Failed(res.message))
                }
            }
        }
    }

    fun endConversation() {
        isSessionEndedByUser = true
        interruptCurrentResponse(markInterrupted = false)
        _uiState.update {
            it.copy(
                voiceSessionState = VoiceSessionState.ENDED,
                liveTranscript = "",
                streamingReply = "",
                audioAmplitude = 0.08f,
                needsMicPermissionForAutoStart = false
            )
        }
    }

    fun startNewConversation() {
        isSessionEndedByUser = false
        interruptCurrentResponse(markInterrupted = false)
        val newId = newCleanId("conv")
        activeConvIdFlow.value = newId
        _uiState.update {
            it.copy(
                activeConversationId = newId,
                voiceSessionState = VoiceSessionState.IDLE,
                liveTranscript = "",
                streamingReply = "",
                lastAssistantReply = "",
                errorMessage = null,
                lastFailedPrompt = null,
                lastFailedConversationId = null,
                needsMicPermissionForAutoStart = false
            )
        }
    }

    fun selectConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        isSessionEndedByUser = false
        greetedConversationIds.add(conversationId)
        interruptCurrentResponse(markInterrupted = false)
        activeConvIdFlow.value = conversationId
        _uiState.update {
            it.copy(
                activeConversationId = conversationId,
                voiceSessionState = VoiceSessionState.IDLE,
                liveTranscript = "",
                streamingReply = "",
                errorMessage = null,
                lastFailedPrompt = null,
                lastFailedConversationId = null,
                needsMicPermissionForAutoStart = false
            )
        }
        viewModelScope.launch {
            val convHeader = dao.getConversationById(conversationId)
            val isTeluguConv = convHeader?.languageCode?.equals(SaathiLanguage.TELUGU.name, ignoreCase = true) == true ||
                preferences.value.preferredLanguage == SaathiLanguage.TELUGU
            var localMsgs = dao.getMessagesOnce(conversationId)
            if (isTeluguConv) {
                for (msg in localMsgs) {
                    if (SaathiVoiceCatalog.containsTeluguUnicode(msg.text)) {
                        dao.upsertMessage(msg.copy(text = SaathiVoiceCatalog.ensureRomanTeluguDisplay(msg.text)))
                    }
                }
            }
            if (canUseCloudSync) {
                val localIds = localMsgs.map { it.id }.toHashSet()
                val remoteMsgs = firestoreRepo.getMessagesOnce(conversationId).getOrNull().orEmpty()
                var insertedAny = false
                for (doc in remoteMsgs) {
                    // Never overwrite existing local messages; only backfill missing remote messages
                    if (doc.id !in localIds) {
                        val safeText = if (isTeluguConv) {
                            SaathiVoiceCatalog.ensureRomanTeluguDisplay(doc.text)
                        } else doc.text
                        dao.upsertMessage(
                            MessageEntity(
                                id = doc.id,
                                conversationId = conversationId,
                                userId = currentUserId,
                                role = doc.role,
                                text = safeText,
                                isVoice = doc.isVoice,
                                timestampEpochMs = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                            )
                        )
                        insertedAny = true
                    }
                }
                if (insertedAny || isTeluguConv) {
                    localMsgs = dao.getMessagesOnce(conversationId)
                }
            } else if (isTeluguConv) {
                localMsgs = dao.getMessagesOnce(conversationId)
            }
            val latestReply = localMsgs.lastOrNull { it.role == "model" }?.text.orEmpty()
            _uiState.update { state ->
                if (state.activeConversationId == conversationId) {
                    state.copy(lastAssistantReply = latestReply)
                } else state
            }
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        val clean = newTitle.trim().take(120)
        if (clean.isBlank()) return
        knownConversationTitles[conversationId] = clean
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.renameConversation(conversationId, clean, now)
            if (canUseCloudSync) {
                val conv = dao.getConversationById(conversationId)
                val lang = runCatching {
                    SaathiLanguage.valueOf(conv?.languageCode ?: SaathiLanguage.ENGLISH.name)
                }.getOrDefault(SaathiLanguage.ENGLISH)
                firestoreRepo.upsertConversation(
                    conversationId = conversationId,
                    title = clean,
                    language = lang,
                    lastMessagePreview = conv?.lastMessagePreview ?: ""
                )
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        knownConversationTitles.remove(conversationId)
        viewModelScope.launch {
            dao.deleteConversation(conversationId)
            greetedConversationIds.remove(conversationId)
            if (canUseCloudSync) {
                firestoreRepo.deleteConversation(conversationId)
            }
            if (_uiState.value.activeConversationId == conversationId) {
                startNewConversation()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
        _uiState.update { it.copy(historySearchQuery = query) }
    }

    fun toggleMute() {
        val nextMuted = !_uiState.value.isMuted
        if (nextMuted) {
            audioPlayer.stop()
            speakingPulseJob?.cancel()
            if (_uiState.value.voiceSessionState == VoiceSessionState.SPEAKING ||
                _uiState.value.voiceSessionState == VoiceSessionState.GREETING
            ) {
                _uiState.update { it.copy(voiceSessionState = VoiceSessionState.IDLE, audioAmplitude = 0.08f) }
            }
        }
        _uiState.update { it.copy(isMuted = nextMuted) }
    }

    fun setLanguage(language: SaathiLanguage) {
        pendingLanguageOverride = language
        val wasListeningAndroidSpeech = _uiState.value.voiceSessionState == VoiceSessionState.LISTENING &&
            !_uiState.value.isRecordingScribe
        val switchToken = if (wasListeningAndroidSpeech) {
            val nextToken = ++voiceTurnSequence
            speechRecognizer.cancel()
            _uiState.update {
                it.copy(
                    voiceSessionState = VoiceSessionState.IDLE,
                    liveTranscript = "",
                    audioAmplitude = 0.08f
                )
            }
            nextToken
        } else {
            voiceTurnSequence
        }
        viewModelScope.launch {
            try {
                prefsRepo.setLanguage(language)
                if (canUseCloudSync) {
                    firestoreRepo.updateProfileFields(mapOf("preferredLanguage" to language.name))
                }
            } finally {
                if (pendingLanguageOverride == language) {
                    pendingLanguageOverride = null
                }
            }
            if (wasListeningAndroidSpeech &&
                switchToken == voiceTurnSequence &&
                isAppInForeground &&
                !isSessionEndedByUser
            ) {
                startVoiceListening(languageOverride = language)
            }
        }
    }

    fun selectVoice(voiceId: String, gender: VoiceGender) {
        val extracted = SaathiVoiceCatalog.extractVoiceId(voiceId)
        val backendOptionId = _uiState.value.availableVoices
            .firstOrNull { it.gender == gender }
            ?.configuredVoiceId
            ?.let { SaathiVoiceCatalog.extractVoiceId(it) }
            .orEmpty()

        val resolvedId = when {
            extracted.isNotBlank() &&
                extracted != SaathiVoiceCatalog.SLOT_FEMALE &&
                extracted != SaathiVoiceCatalog.SLOT_MALE -> extracted
            backendOptionId.isNotBlank() -> backendOptionId
            else -> SaathiVoiceCatalog.slotForGender(gender)
        }

        // Apply voice switches immediately without requiring an app restart
        ttsTurnToken++
        activePreviewJob?.cancel()
        activePreviewJob = null
        speakingPulseJob?.cancel()
        speakingPulseJob = null
        voiceBackendClient.cancelActiveTtsRequest()
        audioPlayer.stop()
        _uiState.update { state ->
            val wasSpeaking = state.voiceSessionState == VoiceSessionState.SPEAKING ||
                state.voiceSessionState == VoiceSessionState.GREETING
            state.copy(
                previewingVoiceId = null,
                voiceSessionState = if (wasSpeaking) {
                    VoiceSessionState.IDLE
                } else {
                    state.voiceSessionState
                },
                audioAmplitude = if (wasSpeaking) 0.08f else state.audioAmplitude
            )
        }

        viewModelScope.launch {
            prefsRepo.setVoice(resolvedId, gender)
            if (canUseCloudSync) {
                firestoreRepo.updateProfileFields(
                    mapOf("selectedVoiceId" to resolvedId, "selectedVoiceGender" to gender.name)
                )
            }
        }
    }

    fun setSpeechSpeed(speed: Float) {
        viewModelScope.launch {
            prefsRepo.setSpeechSpeed(speed)
            if (canUseCloudSync) {
                firestoreRepo.updateProfileFields(mapOf("speechSpeed" to speed.toDouble()))
            }
        }
    }

    fun setSpeechVolume(volume: Float) {
        audioPlayer.updateVolume(volume)
        viewModelScope.launch {
            prefsRepo.setSpeechVolume(volume)
            if (canUseCloudSync) {
                firestoreRepo.updateProfileFields(mapOf("speechVolume" to volume.toDouble()))
            }
        }
    }

    fun setAutoPlayVoice(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAutoPlayVoice(enabled)
            if (canUseCloudSync) {
                firestoreRepo.updateProfileFields(mapOf("autoPlayVoice" to enabled))
            }
        }
    }

    fun setAutoTurnTaking(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAutoTurnTaking(enabled)
        }
    }

    fun setAutoStartConversation(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAutoStartConversation(enabled)
            if (!enabled) {
                _uiState.update { it.copy(needsMicPermissionForAutoStart = false) }
            }
        }
    }

    fun setSttProvider(provider: SttProvider) {
        viewModelScope.launch { prefsRepo.setSttProvider(provider) }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setMemoryEnabled(enabled)
            if (canUseCloudSync) {
                firestoreRepo.updateProfileFields(mapOf("memoryEnabled" to enabled))
            }
        }
    }

    fun setCloudSyncEnabled(enabled: Boolean) {
        if (isGuestSession) return
        viewModelScope.launch {
            prefsRepo.setCloudSyncEnabled(enabled)
            firestoreRepo.updateProfileFields(mapOf("cloudSyncEnabled" to enabled))
        }
    }

    fun setDailyRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setDailyRemindersEnabled(enabled)
            if (canUseCloudSync) {
                firestoreRepo.updateProfileFields(mapOf("dailyRemindersEnabled" to enabled))
            }
        }
    }

    fun updatePreferredName(name: String) {
        val clean = name.trim().take(60)
        viewModelScope.launch {
            prefsRepo.setPreferredName(clean)
            if (canUseCloudSync && clean.isNotEmpty()) {
                firestoreRepo.updateProfileFields(mapOf("preferredName" to clean))
            }
        }
    }

    fun acceptMemorySuggestion() {
        val suggestion = _uiState.value.pendingMemorySuggestion ?: return
        _uiState.update { it.copy(pendingMemorySuggestion = null) }
        saveMemory(category = suggestion.category, content = suggestion.content)
    }

    fun dismissMemorySuggestion() {
        _uiState.update { it.copy(pendingMemorySuggestion = null) }
    }

    fun saveMemory(category: String, content: String, existingId: String? = null) {
        val rawClean = content.trim().take(500)
        if (rawClean.isBlank()) return
        val cleanContent = if (preferences.value.preferredLanguage == SaathiLanguage.TELUGU) {
            SaathiVoiceCatalog.ensureRomanTeluguDisplay(rawClean)
        } else rawClean
        val memId = existingId ?: newCleanId("mem")
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            dao.upsertMemory(
                MemoryEntity(
                    id = memId,
                    userId = currentUserId,
                    category = category.ifBlank { "Context" },
                    content = cleanContent,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            if (canUseCloudSync) {
                firestoreRepo.saveMemory(memId, category, cleanContent, isNew = existingId == null)
            }
        }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            dao.deleteMemory(memoryId)
            if (canUseCloudSync) {
                firestoreRepo.deleteMemory(memoryId)
            }
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            val all = dao.getMemoriesOnce(currentUserId)
            dao.clearMemoriesForUser(currentUserId)
            if (canUseCloudSync) {
                all.forEach { firestoreRepo.deleteMemory(it.id) }
            }
        }
    }

    fun clearAllConversations() {
        knownConversationTitles.clear()
        viewModelScope.launch {
            dao.clearConversationsForUser(currentUserId)
            greetedConversationIds.clear()
            if (canUseCloudSync) {
                val remote = firestoreRepo.observeConversations(currentUserId)
                    .catch { }
                    .let { flow ->
                        var items: List<com.example.model.ConversationDoc> = emptyList()
                        runCatching {
                            flow.collect {
                                items = it
                                throw kotlinx.coroutines.CancellationException("Done")
                            }
                        }
                        items
                    }
                remote.forEach { firestoreRepo.deleteConversation(it.id) }
            }
            startNewConversation()
        }
    }

    fun deleteAccountAndData(onComplete: () -> Unit, onError: (String) -> Unit) {
        _uiState.update { it.copy(isDeletingAccount = true) }
        viewModelScope.launch {
            try {
                if (!isGuestSession) {
                    firestoreRepo.deleteAllUserData()
                }
                dao.clearConversationsForUser(currentUserId)
                dao.clearMemoriesForUser(currentUserId)
                greetedConversationIds.clear()
                knownConversationTitles.clear()
                if (!isGuestSession) {
                    prefsRepo.clearAll()
                    runCatching { Firebase.auth.currentUser?.delete()?.await() }
                    Firebase.auth.signOut()
                } else {
                    startNewConversation()
                }
                _uiState.update { it.copy(isDeletingAccount = false) }
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Data deletion failed", e)
                _uiState.update { it.copy(isDeletingAccount = false) }
                onError(e.localizedMessage ?: "Unable to clear saved data right now. Please try again.")
            }
        }
    }

    fun clearError() {
        _uiState.update { state ->
            state.copy(
                voiceSessionState = if (state.voiceSessionState == VoiceSessionState.ERROR) {
                    VoiceSessionState.IDLE
                } else {
                    state.voiceSessionState
                },
                errorMessage = null,
                voiceBackendNotice = null
            )
        }
    }

    private suspend fun ensureActiveConversationExists(
        seedText: String,
        preferredConvId: String? = null
    ): String {
        val currentId = preferredConvId?.takeIf { it.isNotBlank() }
            ?: _uiState.value.activeConversationId.ifBlank { newCleanId("conv") }
        if (knownConversationTitles.containsKey(currentId)) {
            return currentId
        }
        val existing = dao.getConversationById(currentId)
        if (existing != null) {
            knownConversationTitles[currentId] = existing.title
        } else {
            val now = System.currentTimeMillis()
            val title = seedText.trim().take(42).ifBlank { "SAATHI Chat" }
            knownConversationTitles[currentId] = title
            dao.upsertConversation(
                ConversationEntity(
                    id = currentId,
                    userId = currentUserId,
                    title = title,
                    languageCode = preferences.value.preferredLanguage.name,
                    lastMessagePreview = seedText.take(120),
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            if (_uiState.value.activeConversationId == currentId || _uiState.value.activeConversationId.isBlank()) {
                activeConvIdFlow.value = currentId
                _uiState.update { it.copy(activeConversationId = currentId) }
            }
        }
        return currentId
    }

    private suspend fun updateConversationHeader(
        convId: String,
        preview: String,
        language: SaathiLanguage,
        updatedAt: Long
    ) {
        dao.updateConversationHeaderFast(
            conversationId = convId,
            languageCode = language.name,
            preview = preview.take(160),
            updatedAt = updatedAt
        )
    }

    private fun newCleanId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    internal fun cleanupResourcesForTest() {
        onCleared()
    }

    public override fun onCleared() {
        interruptCurrentResponse(markInterrupted = false)
        super.onCleared()
    }

    private companion object {
        const val TAG = "SaathiViewModel"
    }
}
