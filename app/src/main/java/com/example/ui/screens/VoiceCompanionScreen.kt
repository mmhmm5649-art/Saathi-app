package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.local.UserPreferencesState
import com.example.model.SaathiLanguage
import com.example.model.VoiceSessionState
import com.example.ui.components.SaathiAnimatedVoiceOrb
import com.example.ui.components.SaathiAudioWaveform
import com.example.ui.components.SaathiCosmicBackground
import com.example.ui.components.SaathiGlassCard
import com.example.ui.components.SaathiInfinityLogo
import com.example.ui.theme.CharcoalElevated
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CrimsonRose
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.SoftBlue
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmAmber
import com.example.viewmodel.CompanionUiState

@Composable
fun VoiceCompanionScreen(
    uiState: CompanionUiState,
    preferences: UserPreferencesState,
    onMicClick: () -> Unit,
    onLanguageSelected: (SaathiLanguage) -> Unit,
    onEndConversation: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenTextChat: () -> Unit,
    onOpenVoiceSelect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenHistory: () -> Unit,
    onAcceptMemory: () -> Unit,
    onDismissMemory: () -> Unit,
    onRetryError: () -> Unit,
    onDismissError: () -> Unit,
    onVoiceScreenReady: (Boolean) -> Unit = {},
    onMicPermissionResult: (Boolean) -> Unit = {},
    onVoiceScreenDisposed: () -> Unit = {}
) {
    val context = LocalContext.current
    var showMicRationale by remember { mutableStateOf(false) }

    val hasMicPermNow = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(uiState.activeConversationId, preferences.autoStartConversation) {
        onVoiceScreenReady(hasMicPermNow())
    }

    DisposableEffect(Unit) {
        onDispose {
            onVoiceScreenDisposed()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onMicPermissionResult(granted)
        if (!granted) {
            showMicRationale = true
        }
    }

    val handleMicTap = {
        if (hasMicPermNow()) {
            onMicClick()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            containerColor = CharcoalSurface,
            title = { Text("Microphone Access", color = TextPrimary) },
            text = {
                Text(
                    "SAATHI needs microphone access to listen when you speak. Your voice is never stored permanently, and SAATHI never records without your permission. You can also switch to Text Chat anytime.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text("Allow Mic", color = SoftBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    onOpenTextChat()
                }) {
                    Text("Use Text Chat", color = TextSecondary)
                }
            }
        )
    }

    SaathiCosmicBackground {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
            // Top Header matching uploaded reference
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SaathiInfinityLogo(size = 40.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(32.dp)
                            .background(Color(0x33FFFFFF))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Bold)) {
                                    append("SAA")
                                }
                                withStyle(
                                    SpanStyle(
                                        brush = Brush.linearGradient(listOf(ElectricViolet, SoftBlue)),
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append("THI")
                                }
                            },
                            fontSize = 24.sp
                        )
                        Text(
                            text = "Your Friend. Your World.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontStyle = FontStyle.Italic
                            )
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CharcoalSurface.copy(alpha = 0.85f))
                            .border(1.dp, Color(0x28FFFFFF), CircleShape)
                            .testTag("mute_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (uiState.isMuted) "Unmute voice" else "Mute voice",
                            tint = if (uiState.isMuted) CrimsonRose else TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CharcoalSurface.copy(alpha = 0.85f))
                            .border(1.dp, Color(0x28FFFFFF), CircleShape)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Live status indicator pill + active voice identity
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 66.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (statusDotColor, statusLabel) = when {
                    uiState.voiceSessionState == VoiceSessionState.GREETING -> OnlineGreen to "Greeting you... (Tap to interrupt)"
                    uiState.voiceSessionState == VoiceSessionState.LISTENING -> SoftBlue to "Listening..."
                    uiState.voiceSessionState == VoiceSessionState.THINKING -> ElectricViolet to "Thinking..."
                    uiState.voiceSessionState == VoiceSessionState.SPEAKING -> OnlineGreen to "Speaking... (Tap to interrupt)"
                    uiState.voiceSessionState == VoiceSessionState.INTERRUPTED -> WarmAmber to "Interrupted — Tap to talk"
                    uiState.voiceSessionState == VoiceSessionState.ENDED -> TextSecondary to "Conversation ended"
                    uiState.voiceSessionState == VoiceSessionState.ERROR -> CrimsonRose to "Attention needed"
                    else -> OnlineGreen to "Online"
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusDotColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${preferences.selectedVoiceGender.displayName} • ${preferences.preferredLanguage.isoCode.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            // Central Animated Orb (tap to talk or interrupt)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = handleMicTap)
                    .testTag("orb_tap_target")
            ) {
                SaathiAnimatedVoiceOrb(
                    sessionState = uiState.voiceSessionState,
                    amplitude = uiState.audioAmplitude,
                    orbSize = 198.dp
                )
            }

            // Speech / Greeting Glass Panel
            SaathiGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        uiState.needsMicPermissionForAutoStart -> {
                            Text(
                                text = "Microphone Access Needed",
                                style = MaterialTheme.typography.titleMedium,
                                color = SoftBlue
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Allow microphone access so SAATHI can greet you aloud and listen naturally. Your voice is never recorded without your permission.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    modifier = Modifier.testTag("allow_mic_permission_button")
                                ) {
                                    Text("Allow Microphone", color = SoftBlue, fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = onOpenTextChat,
                                    modifier = Modifier.testTag("mic_prompt_text_chat_button")
                                ) {
                                    Text("Use Text Chat", color = TextPrimary)
                                }
                            }
                        }

                        !uiState.errorMessage.isNullOrBlank() -> {
                            Text(
                                text = uiState.errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CrimsonRose,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                            ) {
                                if (uiState.lastFailedPrompt != null) {
                                    TextButton(
                                        onClick = onRetryError,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("retry_prompt_button")
                                    ) {
                                        Text("Retry", color = SoftBlue)
                                    }
                                } else if (uiState.voiceSessionState == VoiceSessionState.ERROR) {
                                    TextButton(
                                        onClick = handleMicTap,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("retry_mic_button")
                                    ) {
                                        Text("Try Again", color = SoftBlue)
                                    }
                                }
                                TextButton(
                                    onClick = onOpenTextChat,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("error_text_chat_button")
                                ) {
                                    Text("Use Text Chat", color = TextPrimary)
                                }
                                TextButton(
                                    onClick = onDismissError,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("dismiss_error_button")
                                ) {
                                    Text("Dismiss", color = TextSecondary)
                                }
                            }
                        }

                        uiState.voiceSessionState == VoiceSessionState.GREETING -> {
                            Text(
                                text = uiState.lastAssistantReply.ifBlank { preferences.preferredLanguage.spokenGreeting },
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        uiState.voiceSessionState == VoiceSessionState.LISTENING -> {
                            Text(
                                text = if (uiState.isRecordingScribe) "Recording with Scribe..." else "I'm listening...",
                                style = MaterialTheme.typography.titleMedium,
                                color = SoftBlue
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.liveTranscript.ifBlank {
                                    if (uiState.isRecordingScribe) "Tap the mic button when you finish speaking."
                                    else "Speak naturally in ${preferences.preferredLanguage.displayName}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        uiState.streamingReply.isNotBlank() -> {
                            Text(
                                text = uiState.streamingReply,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        uiState.voiceSessionState == VoiceSessionState.THINKING -> {
                            Text(
                                text = "Thinking...",
                                style = MaterialTheme.typography.titleMedium,
                                color = ElectricViolet
                            )
                            if (uiState.liveTranscript.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "\"${uiState.liveTranscript}\"",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        uiState.voiceSessionState == VoiceSessionState.ENDED -> {
                            Text(
                                text = "Conversation Ended",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap the voice orb or mic button whenever you're ready to start a new chat.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }

                        uiState.lastAssistantReply.isNotBlank() -> {
                            Text(
                                text = uiState.lastAssistantReply,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        else -> {
                            Text(
                                text = "${preferences.preferredLanguage.greetingTitle} 👋",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = preferences.preferredLanguage.greetingSubtitle,
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Transparent backend setup notice if ElevenLabs backend URL is not yet configured
                    AnimatedVisibility(visible = !uiState.voiceBackendNotice.isNullOrBlank()) {
                        Column(
                            modifier = Modifier.padding(top = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = uiState.voiceBackendNotice.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = WarmAmber,
                                textAlign = TextAlign.Center
                            )
                            TextButton(onClick = onOpenVoiceSelect) {
                                Text("Configure ElevenLabs Backend", color = SoftBlue)
                            }
                        }
                    }

                    // Memory permission prompt chip
                    AnimatedVisibility(visible = uiState.pendingMemorySuggestion != null) {
                        val suggestion = uiState.pendingMemorySuggestion
                        if (suggestion != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CharcoalElevated)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Remember this?",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoftBlue
                                    )
                                    Text(
                                        text = suggestion.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row {
                                    TextButton(onClick = onAcceptMemory) {
                                        Text("Save", color = SoftBlue)
                                    }
                                    TextButton(onClick = onDismissMemory) {
                                        Text("No", color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Live Audio Waveform
            SaathiAudioWaveform(
                amplitude = uiState.audioAmplitude,
                isActive = uiState.voiceSessionState == VoiceSessionState.GREETING ||
                    uiState.voiceSessionState == VoiceSessionState.LISTENING ||
                    uiState.voiceSessionState == VoiceSessionState.SPEAKING
            )

            // Primary Controls Row: Text Chat | Mic Orb | Voice Select
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onOpenTextChat,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(CharcoalSurface)
                            .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            .testTag("text_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Text Chat",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Text Chat", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val isBusy = uiState.voiceSessionState == VoiceSessionState.GREETING ||
                        uiState.voiceSessionState == VoiceSessionState.SPEAKING ||
                        uiState.voiceSessionState == VoiceSessionState.THINKING
                    val isListening = uiState.voiceSessionState == VoiceSessionState.LISTENING

                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .shadow(18.dp, CircleShape, ambientColor = ElectricViolet, spotColor = SoftBlue)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = if (isListening) listOf(SoftBlue, ElectricViolet)
                                    else listOf(ElectricViolet, SoftBlue)
                                )
                            )
                            .border(2.5.dp, Color.White.copy(alpha = 0.75f), CircleShape)
                            .clickable(onClick = handleMicTap)
                            .testTag("main_mic_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isListening && uiState.isRecordingScribe -> Icons.Default.Stop
                                else -> Icons.Default.Mic
                            },
                            contentDescription = "Tap to talk",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            isListening -> if (uiState.isRecordingScribe) "Tap to send" else "Listening..."
                            isBusy -> "Tap to interrupt"
                            uiState.voiceSessionState == VoiceSessionState.ENDED -> "Tap to start"
                            else -> "Tap to talk"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onOpenVoiceSelect,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(CharcoalSurface)
                            .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            .testTag("voice_select_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Voice Select",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Voice Select", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }

            // 3-Way Language Switcher Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(CharcoalSurface.copy(alpha = 0.9f))
                    .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(30.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SaathiLanguage.entries.forEach { lang ->
                    val selected = preferences.preferredLanguage == lang
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                if (selected) Brush.horizontalGradient(listOf(ElectricViolet, SoftBlue.copy(alpha = 0.85f)))
                                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable { onLanguageSelected(lang) }
                            .testTag("lang_pill_${lang.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (lang == SaathiLanguage.TELUGU) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = if (selected) Color.White else TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Text(
                                    text = lang.badgeCode,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) Color.White else SoftBlue,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = lang.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            // Bottom Action Bar: Memories | End Conversation | History
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenMemories,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(CharcoalSurface)
                        .border(1.dp, Color(0x26FFFFFF), CircleShape)
                        .testTag("memories_shortcut_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Memories",
                        tint = ElectricViolet
                    )
                }

                Button(
                    onClick = onEndConversation,
                    modifier = Modifier
                        .height(50.dp)
                        .border(1.2.dp, CrimsonRose.copy(alpha = 0.65f), RoundedCornerShape(26.dp))
                        .testTag("end_conversation_button"),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CharcoalSurface.copy(alpha = 0.92f),
                        contentColor = TextPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = null,
                        tint = CrimsonRose,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("End Conversation", style = MaterialTheme.typography.labelLarge)
                }

                IconButton(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(CharcoalSurface)
                        .border(1.dp, Color(0x26FFFFFF), CircleShape)
                        .testTag("history_shortcut_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Conversation History",
                        tint = SoftBlue
                    )
                }
            }
        }
        }
    }
}
