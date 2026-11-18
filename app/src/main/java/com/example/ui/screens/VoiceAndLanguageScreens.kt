package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.UserPreferencesState
import com.example.model.SaathiLanguage
import com.example.model.SttProvider
import com.example.model.VoiceGender
import com.example.ui.components.SaathiCosmicBackground
import com.example.ui.components.SaathiGlassCard
import com.example.ui.theme.CharcoalElevated
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CrimsonRose
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.SoftBlue
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmAmber
import com.example.viewmodel.BackendHealthState
import com.example.viewmodel.CompanionUiState
import kotlin.math.roundToInt

@Composable
fun VoiceSelectionScreen(
    preferences: UserPreferencesState,
    uiState: CompanionUiState,
    onBack: () -> Unit,
    onSelectVoice: (String, VoiceGender) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onAutoPlayChange: (Boolean) -> Unit,
    onAutoTurnTakingChange: (Boolean) -> Unit,
    onAutoStartConversationChange: (Boolean) -> Unit = {},
    onSttProviderChange: (SttProvider) -> Unit,
    onTestBackend: (String) -> Unit,
    onSearchLibrary: (VoiceGender, String) -> Unit,
    onConfigureBackendVoice: (VoiceGender, String, String) -> Unit,
    onRefreshVoices: () -> Unit = {}
) {
    var backendUrlDraft by remember(preferences.voiceBackendUrl) {
        mutableStateOf(preferences.voiceBackendUrl)
    }
    var configuringGender by remember { mutableStateOf<VoiceGender?>(null) }
    var customVoiceIdDraft by remember { mutableStateOf("") }
    var customVoiceNameDraft by remember { mutableStateOf("") }
    var librarySearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(preferences.voiceBackendUrl) {
        if (preferences.voiceBackendUrl.isNotBlank()) {
            onRefreshVoices()
        }
    }

    if (configuringGender != null) {
        val targetGender = configuringGender!!
        AlertDialog(
            onDismissRequest = { configuringGender = null },
            containerColor = CharcoalSurface,
            title = {
                Text(
                    text = "Configure ${targetGender.displayName} Indian Voice",
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Enter a Voice ID from the ElevenLabs Indian Voice Library or search your connected backend below:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = customVoiceIdDraft,
                        onValueChange = { customVoiceIdDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_voice_id_input"),
                        label = { Text("ElevenLabs Voice ID") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricViolet,
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = customVoiceNameDraft,
                        onValueChange = { customVoiceNameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Display Name (optional)") },
                        placeholder = {
                            Text(if (targetGender == VoiceGender.FEMALE) "Ananya (Indian Female)" else "Arjun (Indian Male)")
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricViolet,
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = librarySearchQuery,
                            onValueChange = { librarySearchQuery = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Search Indian Library") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SoftBlue,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        IconButton(
                            onClick = { onSearchLibrary(targetGender, librarySearchQuery) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ElectricViolet)
                        ) {
                            if (uiState.isSearchingLibrary) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = "Search Voice Library", tint = Color.White)
                            }
                        }
                    }

                    if (uiState.voiceLibraryCandidates.isNotEmpty()) {
                        Text(
                            text = "ElevenLabs Indian Voices:",
                            style = MaterialTheme.typography.labelLarge,
                            color = SoftBlue
                        )
                        uiState.voiceLibraryCandidates.take(6).forEach { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CharcoalElevated)
                                    .clickable {
                                        customVoiceIdDraft = candidate.voiceId
                                        customVoiceNameDraft = candidate.name
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(candidate.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${candidate.accent} • ${candidate.tone}", style = MaterialTheme.typography.labelSmall, color = SoftBlue)
                                }
                                Text("Use", style = MaterialTheme.typography.labelLarge, color = ElectricViolet)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = com.example.model.SaathiVoiceCatalog.extractVoiceId(customVoiceIdDraft).isNotBlank() && !uiState.isSavingBackendVoice,
                    onClick = {
                        val cleanId = com.example.model.SaathiVoiceCatalog.extractVoiceId(customVoiceIdDraft)
                        onConfigureBackendVoice(targetGender, cleanId, customVoiceNameDraft)
                        configuringGender = null
                    }
                ) {
                    Text(if (uiState.isSavingBackendVoice) "Saving..." else "Save to Backend", color = SoftBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { configuringGender = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    SaathiCosmicBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .widthIn(max = 640.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("voice_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Voice & Speech", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                    Text(
                        text = "Natural Indian Voices • ${uiState.activeTtsModel} • Consistent across te / hi / en",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Both Female & Male Voices displayed together for effortless preview-before-selecting
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                uiState.availableVoices.forEach { voice ->
                    val isSelected = preferences.selectedVoiceGender == voice.gender
                    val isPreviewing = uiState.previewingVoiceId == voice.slot
                    SaathiGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectVoice(voice.configuredVoiceId.ifBlank { voice.slot }, voice.gender)
                            }
                            .testTag("voice_card_${voice.gender.slotKey}"),
                        borderBrush = if (isSelected) {
                            Brush.horizontalGradient(listOf(ElectricViolet, SoftBlue))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF)))
                        }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) Brush.linearGradient(listOf(ElectricViolet, SoftBlue))
                                            else Brush.linearGradient(listOf(CharcoalElevated, CharcoalElevated))
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${voice.gender.displayName}: ${voice.name}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = if (isSelected) "Active" else "Tap to Select",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) OnlineGreen else TextSecondary
                                        )
                                    }
                                    Text(
                                        text = "${voice.accent} Accent • ${voice.tone}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SoftBlue
                                    )
                                    Text(
                                        text = voice.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { onPreviewVoice(voice.slot) },
                                    modifier = Modifier
                                        .height(42.dp)
                                        .testTag("preview_voice_${voice.gender.slotKey}")
                                ) {
                                    if (isPreviewing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = SoftBlue
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Previewing...", color = SoftBlue)
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SoftBlue, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Preview (${preferences.preferredLanguage.displayName})", color = TextPrimary)
                                    }
                                }

                                TextButton(
                                    onClick = {
                                        customVoiceIdDraft = voice.configuredVoiceId
                                        customVoiceNameDraft = voice.name
                                        configuringGender = voice.gender
                                        if (preferences.voiceBackendUrl.isNotBlank()) {
                                            onSearchLibrary(voice.gender, "")
                                        }
                                    },
                                    modifier = Modifier.testTag("configure_voice_${voice.gender.slotKey}")
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = SoftBlue, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (voice.configured) "Change ID" else "Set Voice ID",
                                        color = SoftBlue
                                    )
                                }
                            }

                            Text(
                                text = if (voice.configured && voice.configuredVoiceId.isNotBlank()) {
                                    "Backend Voice ID: ${voice.configuredVoiceId} (${voice.source})"
                                } else {
                                    "Voice ID resolved dynamically by backend settings or Indian Voice Library"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (voice.configured) OnlineGreen else WarmAmber
                            )
                        }
                    }
                }
            }

            if (!uiState.voiceBackendNotice.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = uiState.voiceBackendNotice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrimsonRose
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Playback Speed, Volume, Auto-Play & Conversational Turn-Taking
            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Expressive Delivery & Turn-Taking", style = MaterialTheme.typography.titleMedium, color = TextPrimary)

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Speech Speed", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Text("${((preferences.speechSpeed * 100).roundToInt() / 100f)}x", style = MaterialTheme.typography.labelLarge, color = SoftBlue)
                        }
                        Slider(
                            value = preferences.speechSpeed,
                            onValueChange = onSpeedChange,
                            valueRange = 0.75f..1.25f,
                            colors = SliderDefaults.colors(thumbColor = SoftBlue, activeTrackColor = ElectricViolet)
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Voice Volume", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Text("${(preferences.speechVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = SoftBlue)
                        }
                        Slider(
                            value = preferences.speechVolume,
                            onValueChange = onVolumeChange,
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = SoftBlue, activeTrackColor = ElectricViolet)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-play voice replies", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text("Speak SAATHI replies via Eleven v4 Turbo", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Switch(
                            checked = preferences.autoPlayVoice,
                            onCheckedChange = onAutoPlayChange,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricViolet)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Conversational turn-taking", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text("Automatically listen after natural speech pauses & replies", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Switch(
                            checked = preferences.autoTurnTaking,
                            onCheckedChange = onAutoTurnTakingChange,
                            modifier = Modifier.testTag("auto_turn_taking_switch"),
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricViolet)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic voice conversation", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text("Greet you aloud and start listening when opening SAATHI", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Switch(
                            checked = preferences.autoStartConversation,
                            onCheckedChange = onAutoStartConversationChange,
                            modifier = Modifier.testTag("auto_start_conversation_switch"),
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricViolet)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Speech Recognition Provider
            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Speech Recognition (Voice Input)", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    SttProvider.entries.forEach { provider ->
                        val selected = preferences.sttProvider == provider
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) CharcoalElevated else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (selected) SoftBlue else Color(0x22FFFFFF),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onSttProviderChange(provider) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.displayName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Text(provider.subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                            if (selected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = SoftBlue)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Secure ElevenLabs Backend Setup Card
            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (preferences.voiceBackendUrl.isNotBlank()) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if (preferences.voiceBackendUrl.isNotBlank()) OnlineGreen else SoftBlue
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ElevenLabs Secure Backend", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    }
                    Text(
                        text = "Your ElevenLabs API key stays strictly on the backend (backend/server.js). Voice IDs can be configured via backend environment variables or synced above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = backendUrlDraft,
                        onValueChange = { backendUrlDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_backend_url_input"),
                        label = { Text("Voice Backend HTTPS URL") },
                        placeholder = { Text("https://saathi-voice-xxxx.run.app") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricViolet,
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Button(
                        onClick = { onTestBackend(backendUrlDraft) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("test_backend_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                    ) {
                        if (uiState.healthState is BackendHealthState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Save & Test Connection", fontWeight = FontWeight.Bold)
                        }
                    }
                    when (val health = uiState.healthState) {
                        is BackendHealthState.Ready -> Text(health.message, style = MaterialTheme.typography.bodyMedium, color = OnlineGreen)
                        is BackendHealthState.Failed -> Text(health.message, style = MaterialTheme.typography.bodyMedium, color = CrimsonRose)
                        else -> {}
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun LanguageSelectionScreen(
    selectedLanguage: SaathiLanguage,
    onSelectLanguage: (SaathiLanguage) -> Unit,
    onBack: () -> Unit
) {
    SaathiCosmicBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("language_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Conversation Language", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                    Text("Pure, natural everyday speech", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SaathiLanguage.entries.forEach { lang ->
                    val selected = selectedLanguage == lang
                    SaathiGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectLanguage(lang)
                                onBack()
                            }
                            .testTag("select_language_${lang.name.lowercase()}"),
                        borderBrush = if (selected) {
                            Brush.horizontalGradient(listOf(ElectricViolet, SoftBlue))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF)))
                        }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${lang.displayName}  •  ${lang.nativeName} (${lang.isoCode})",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary
                                )
                                if (selected) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = SoftBlue)
                                }
                            }
                            Text(
                                text = "You: \"${lang.sampleUserPrompt}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "SAATHI: \"${lang.sampleReply}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SoftBlue
                            )
                        }
                    }
                }
            }
        }
    }
}
