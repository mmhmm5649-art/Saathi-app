package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.local.SaathiDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.local.UserPreferencesState
import com.example.data.remote.SaathiAiRepository
import com.example.data.remote.VoiceBackendClient
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.LanguageSelectionScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TextChatScreen
import com.example.ui.screens.VoiceCompanionScreen
import com.example.ui.screens.VoiceSelectionScreen
import com.example.ui.theme.SaathiTheme
import com.example.viewmodel.SaathiViewModel
import com.example.voice.AndroidSpeechRecognizerManager
import com.example.voice.AudioRecorderManager
import com.example.voice.VoiceAudioPlayer
import kotlinx.coroutines.launch

object SaathiRoutes {
    const val VOICE = "voice"
    const val TEXT_CHAT = "text_chat"
    const val VOICE_SELECT = "voice_select"
    const val LANGUAGE_SELECT = "language_select"
    const val HISTORY = "history"
    const val MEMORIES = "memories"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
}

private const val LOCAL_USER_ID = "local_saathi_user"
private const val LOCAL_DISPLAY_NAME = "SAATHI Friend"
private const val LOCAL_PROFILE_SUBTITLE = "Private on-device profile"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            SaathiTheme {
                SaathiRootApp()
            }
        }
    }
}

@Composable
private fun SaathiRootApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsRepo = remember { UserPreferencesRepository(context.applicationContext) }
    val preferences by prefsRepo.preferencesFlow.collectAsStateWithLifecycle(initialValue = UserPreferencesState())

    var showSplash by remember { mutableStateOf(true) }

    when {
        showSplash -> {
            SplashScreen(onFinished = { showSplash = false })
        }

        !preferences.onboardingCompleted -> {
            OnboardingScreen(
                initialLanguage = preferences.preferredLanguage,
                initialGender = preferences.selectedVoiceGender,
                onComplete = { language, gender ->
                    scope.launch {
                        prefsRepo.completeOnboarding(language, gender)
                    }
                }
            )
        }

        else -> {
            key(LOCAL_USER_ID) {
                SaathiAppContent(
                    currentUserId = LOCAL_USER_ID,
                    displayName = LOCAL_DISPLAY_NAME,
                    email = LOCAL_PROFILE_SUBTITLE,
                    photoUrl = null,
                    isLocalSession = true
                )
            }
        }
    }
}

@Composable
private fun SaathiAppContent(
    currentUserId: String,
    displayName: String,
    email: String,
    photoUrl: String?,
    isLocalSession: Boolean
) {
    val context = LocalContext.current
    val navController = rememberNavController()

    val viewModel: SaathiViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val db = SaathiDatabase.getInstance(app)
                SaathiViewModel(
                    currentUserId = currentUserId,
                    userDisplayName = displayName,
                    userEmail = email,
                    userPhotoUrl = photoUrl,
                    isGuestSession = isLocalSession,
                    dao = db.saathiDao(),
                    prefsRepo = UserPreferencesRepository(app),
                    aiRepo = SaathiAiRepository(),
                    voiceBackendClient = VoiceBackendClient(app),
                    speechRecognizer = AndroidSpeechRecognizerManager(app),
                    audioRecorder = AudioRecorderManager(app),
                    audioPlayer = VoiceAudioPlayer(app)
                )
            }
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.interruptCurrentResponse()
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = SaathiRoutes.VOICE
    ) {
        composable(SaathiRoutes.VOICE) {
            VoiceCompanionScreen(
                uiState = uiState,
                preferences = preferences,
                onMicClick = viewModel::onMicPressed,
                onLanguageSelected = viewModel::setLanguage,
                onEndConversation = viewModel::endConversation,
                onToggleMute = viewModel::toggleMute,
                onOpenTextChat = {
                    viewModel.stopVoiceOnlyForModeSwitch()
                    viewModel.clearError()
                    navController.navigate(SaathiRoutes.TEXT_CHAT)
                },
                onOpenVoiceSelect = {
                    viewModel.stopVoiceOnlyForModeSwitch()
                    navController.navigate(SaathiRoutes.VOICE_SELECT)
                },
                onOpenSettings = {
                    viewModel.stopVoiceOnlyForModeSwitch()
                    navController.navigate(SaathiRoutes.SETTINGS)
                },
                onOpenMemories = {
                    viewModel.stopVoiceOnlyForModeSwitch()
                    navController.navigate(SaathiRoutes.MEMORIES)
                },
                onOpenHistory = {
                    viewModel.stopVoiceOnlyForModeSwitch()
                    navController.navigate(SaathiRoutes.HISTORY)
                },
                onAcceptMemory = viewModel::acceptMemorySuggestion,
                onDismissMemory = viewModel::dismissMemorySuggestion,
                onRetryError = viewModel::retryLastFailedMessage,
                onDismissError = viewModel::clearError,
                onVoiceScreenReady = viewModel::onVoiceScreenReady,
                onMicPermissionResult = viewModel::onMicPermissionResult,
                onVoiceScreenDisposed = viewModel::stopVoiceOnlyForModeSwitch
            )
        }

        composable(SaathiRoutes.TEXT_CHAT) {
            TextChatScreen(
                uiState = uiState,
                preferences = preferences,
                messages = messages,
                onBackToVoice = { navController.popBackStack() },
                onSendMessage = viewModel::sendTextMessage,
                onRetryFailed = viewModel::retryLastFailedMessage,
                onNewChat = viewModel::startNewConversation,
                onLanguageSelected = viewModel::setLanguage,
                onAcceptMemory = viewModel::acceptMemorySuggestion,
                onDismissMemory = viewModel::dismissMemorySuggestion,
                onDismissError = viewModel::clearError
            )
        }

        composable(SaathiRoutes.VOICE_SELECT) {
            VoiceSelectionScreen(
                preferences = preferences,
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onSelectVoice = viewModel::selectVoice,
                onPreviewVoice = viewModel::previewVoice,
                onSpeedChange = viewModel::setSpeechSpeed,
                onVolumeChange = viewModel::setSpeechVolume,
                onAutoPlayChange = viewModel::setAutoPlayVoice,
                onAutoTurnTakingChange = viewModel::setAutoTurnTaking,
                onAutoStartConversationChange = viewModel::setAutoStartConversation,
                onSttProviderChange = viewModel::setSttProvider,
                onTestBackend = viewModel::testVoiceBackendHealth,
                onSearchLibrary = viewModel::searchIndianLibraryVoices,
                onConfigureBackendVoice = viewModel::configureBackendVoice,
                onRefreshVoices = { viewModel.refreshBackendVoices() }
            )
        }

        composable(SaathiRoutes.LANGUAGE_SELECT) {
            LanguageSelectionScreen(
                selectedLanguage = preferences.preferredLanguage,
                onSelectLanguage = viewModel::setLanguage,
                onBack = { navController.popBackStack() }
            )
        }

        composable(SaathiRoutes.HISTORY) {
            HistoryScreen(
                conversations = conversations,
                searchQuery = uiState.historySearchQuery,
                cloudSyncEnabled = preferences.cloudSyncEnabled,
                isGuestSession = isLocalSession,
                onSearchChange = viewModel::updateSearchQuery,
                onOpenConversation = { convId ->
                    viewModel.selectConversation(convId)
                    navController.navigate(SaathiRoutes.TEXT_CHAT) {
                        popUpTo(SaathiRoutes.VOICE)
                    }
                },
                onRenameConversation = viewModel::renameConversation,
                onDeleteConversation = viewModel::deleteConversation,
                onToggleCloudSync = viewModel::setCloudSyncEnabled,
                onBack = { navController.popBackStack() }
            )
        }

        composable(SaathiRoutes.MEMORIES) {
            MemoryScreen(
                memories = memories,
                memoryEnabled = preferences.memoryEnabled,
                isGuestSession = isLocalSession,
                onToggleMemoryEnabled = viewModel::setMemoryEnabled,
                onSaveMemory = viewModel::saveMemory,
                onDeleteMemory = viewModel::deleteMemory,
                onClearAllMemories = viewModel::clearAllMemories,
                onBack = { navController.popBackStack() }
            )
        }

        composable(SaathiRoutes.SETTINGS) {
            SettingsScreen(
                preferences = preferences,
                onOpenProfile = { navController.navigate(SaathiRoutes.PROFILE) },
                onOpenVoiceSelect = { navController.navigate(SaathiRoutes.VOICE_SELECT) },
                onOpenLanguageSelect = { navController.navigate(SaathiRoutes.LANGUAGE_SELECT) },
                onOpenMemories = { navController.navigate(SaathiRoutes.MEMORIES) },
                onOpenHistory = { navController.navigate(SaathiRoutes.HISTORY) },
                onToggleDailyReminders = viewModel::setDailyRemindersEnabled,
                onBack = { navController.popBackStack() }
            )
        }

        composable(SaathiRoutes.PROFILE) {
            ProfileScreen(
                displayName = displayName,
                email = email,
                photoUrl = photoUrl,
                preferences = preferences,
                isDeleting = uiState.isDeletingAccount,
                isGuestSession = isLocalSession,
                onUpdatePreferredName = { name ->
                    viewModel.updatePreferredName(name)
                    Toast.makeText(context, "Preferred name saved", Toast.LENGTH_SHORT).show()
                },
                onToggleMemory = viewModel::setMemoryEnabled,
                onToggleCloudSync = viewModel::setCloudSyncEnabled,
                onOpenLanguageSelect = { navController.navigate(SaathiRoutes.LANGUAGE_SELECT) },
                onOpenVoiceSelect = { navController.navigate(SaathiRoutes.VOICE_SELECT) },
                onDeleteAccount = {
                    viewModel.deleteAccountAndData(
                        onComplete = {
                            Toast.makeText(
                                context,
                                "Local data cleared",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onError = { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    )
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
