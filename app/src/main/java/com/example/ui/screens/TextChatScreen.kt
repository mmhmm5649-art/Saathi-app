package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.data.local.MessageEntity
import com.example.data.local.UserPreferencesState
import com.example.model.SaathiLanguage
import com.example.ui.components.SaathiCosmicBackground
import com.example.ui.components.SaathiGlassCard
import com.example.ui.components.SaathiInfinityLogo
import com.example.ui.theme.CharcoalElevated
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CrimsonRose
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.SoftBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.CompanionUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TextChatScreen(
    uiState: CompanionUiState,
    preferences: UserPreferencesState,
    messages: List<MessageEntity>,
    onBackToVoice: () -> Unit,
    onSendMessage: (String) -> Unit,
    onRetryFailed: () -> Unit,
    onNewChat: () -> Unit,
    onLanguageSelected: (SaathiLanguage) -> Unit,
    onAcceptMemory: () -> Unit,
    onDismissMemory: () -> Unit,
    onDismissError: () -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    LaunchedEffect(messages.size, uiState.isTextThinking) {
        val total = messages.size + (if (uiState.streamingReply.isNotEmpty() || uiState.isTextThinking) 1 else 0)
        if (total > 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(total - 1)
        }
    }

    LaunchedEffect(uiState.streamingReply.length) {
        if (uiState.streamingReply.isNotEmpty() && !listState.isScrollInProgress) {
            val total = messages.size + 1
            listState.scrollToItem(total - 1)
        }
    }

    SaathiCosmicBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .widthIn(max = 700.dp)
                .align(Alignment.Center)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBackToVoice,
                        modifier = Modifier.testTag("chat_back_to_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Voice",
                            tint = TextPrimary
                        )
                    }
                    SaathiInfinityLogo(size = 28.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("SAATHI Chat", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(
                            text = "${preferences.preferredLanguage.displayName} • Synced with Voice",
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftBlue
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNewChat, modifier = Modifier.testTag("new_chat_button")) {
                        Icon(
                            imageVector = Icons.Default.AddComment,
                            contentDescription = "New Conversation",
                            tint = TextPrimary
                        )
                    }
                    IconButton(onClick = onBackToVoice, modifier = Modifier.testTag("switch_to_voice_button")) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Switch to Voice Mode",
                            tint = ElectricViolet
                        )
                    }
                }
            }

            // Quick Language Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SaathiLanguage.entries.forEach { lang ->
                    val selected = preferences.preferredLanguage == lang
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (selected) Brush.horizontalGradient(listOf(ElectricViolet, SoftBlue))
                                else Brush.horizontalGradient(listOf(CharcoalSurface, CharcoalSurface))
                            )
                            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp))
                            .clickable { onLanguageSelected(lang) }
                            .testTag("chat_lang_${lang.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Color.White else TextSecondary
                        )
                    }
                }
            }

            // Memory permission banner
            AnimatedVisibility(visible = uiState.pendingMemorySuggestion != null) {
                val suggestion = uiState.pendingMemorySuggestion
                if (suggestion != null) {
                    SaathiGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Save to SAATHI Memory?", style = MaterialTheme.typography.labelLarge, color = SoftBlue)
                                Text(suggestion.content, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            }
                            Row {
                                TextButton(onClick = onAcceptMemory) { Text("Save", color = SoftBlue) }
                                TextButton(onClick = onDismissMemory) { Text("Dismiss", color = TextSecondary) }
                            }
                        }
                    }
                }
            }

            // Chat Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("chat_messages_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (messages.isEmpty() && !uiState.isTextThinking && uiState.streamingReply.isEmpty()) {
                    item {
                        SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${preferences.preferredLanguage.greetingTitle} 👋",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = preferences.preferredLanguage.sampleReply,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Try saying: \"${preferences.preferredLanguage.sampleUserPrompt}\"",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SoftBlue,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(CharcoalElevated)
                                        .clickable {
                                            if (!uiState.isTextThinking) {
                                                onSendMessage(preferences.preferredLanguage.sampleUserPrompt)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("sample_prompt_chip")
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    val isUser = msg.role == "user"
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 310.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 20.dp,
                                        topEnd = 20.dp,
                                        bottomStart = if (isUser) 20.dp else 6.dp,
                                        bottomEnd = if (isUser) 6.dp else 20.dp
                                    )
                                )
                                .background(
                                    if (isUser) {
                                        Brush.linearGradient(listOf(ElectricViolet, SoftBlue.copy(alpha = 0.85f)))
                                    } else {
                                        Brush.linearGradient(listOf(CharcoalSurface, CharcoalElevated))
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isUser) Color.Transparent else Color(0x28FFFFFF),
                                    shape = RoundedCornerShape(
                                        topStart = 20.dp,
                                        topEnd = 20.dp,
                                        bottomStart = if (isUser) 20.dp else 6.dp,
                                        bottomEnd = if (isUser) 6.dp else 20.dp
                                    )
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                        }

                        Row(
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = buildString {
                                    append(timeFormatter.format(Date(msg.timestampEpochMs)))
                                    if (msg.isVoice) append(" • Voice")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            if (!isUser) {
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(msg.text))
                                        Toast.makeText(context, "Copied reply", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("copy_message_${msg.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy response",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Streaming / Typing Indicator
                if (uiState.streamingReply.isNotEmpty() || uiState.isTextThinking) {
                    item(key = "streaming_bubble") {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 310.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(CharcoalSurface)
                                    .border(1.dp, ElectricViolet.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                    .testTag("streaming_typing_indicator")
                            ) {
                                if (uiState.streamingReply.isNotEmpty()) {
                                    Text(
                                        text = uiState.streamingReply,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = SoftBlue
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("SAATHI is typing...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Error & Retry Banner
            AnimatedVisibility(visible = !uiState.errorMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CrimsonRose.copy(alpha = 0.16f))
                        .border(1.dp, CrimsonRose.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.lastFailedPrompt != null) {
                            TextButton(onClick = onRetryFailed, modifier = Modifier.testTag("retry_chat_button")) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = SoftBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Retry", color = SoftBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(onClick = onDismissError, modifier = Modifier.testTag("dismiss_chat_error_button")) {
                            Text("Dismiss", color = TextSecondary)
                        }
                    }
                }
            }

            // Bottom Input Composer
            val canSubmitText = inputText.trim().isNotEmpty() && !uiState.isTextThinking
            val submitCurrentInput = {
                val trimmed = inputText.trim()
                if (trimmed.isNotEmpty() && !uiState.isTextThinking) {
                    inputText = ""
                    onSendMessage(trimmed)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_message_input"),
                    placeholder = {
                        Text(
                            text = "Message SAATHI in ${preferences.preferredLanguage.displayName}...",
                            color = TextMuted
                        )
                    },
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        submitCurrentInput()
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CharcoalSurface,
                        unfocusedContainerColor = CharcoalSurface,
                        focusedBorderColor = ElectricViolet,
                        unfocusedBorderColor = Color(0x30FFFFFF),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = submitCurrentInput,
                    enabled = canSubmitText,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSubmitText) Brush.linearGradient(listOf(ElectricViolet, SoftBlue))
                            else Brush.linearGradient(listOf(CharcoalElevated, CharcoalElevated))
                        )
                        .testTag("send_message_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
