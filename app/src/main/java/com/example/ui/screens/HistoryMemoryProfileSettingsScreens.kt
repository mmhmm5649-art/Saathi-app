package com.example.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.ConversationEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.UserPreferencesState
import com.example.model.SaathiVoiceCatalog
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    conversations: List<ConversationEntity>,
    searchQuery: String,
    cloudSyncEnabled: Boolean,
    isGuestSession: Boolean = false,
    onSearchChange: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onToggleCloudSync: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val dateFmt = remember { SimpleDateFormat("MMM d • h:mm a", Locale.getDefault()) }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = CharcoalSurface,
            title = { Text("Rename Conversation", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameConversation(target.id, renameDraft)
                    renameTarget = null
                }) {
                    Text("Save", color = SoftBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
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
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("history_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Conversation History", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                    Text("Resume, rename, or delete saved chats", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_input"),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                placeholder = { Text("Search past conversations...", color = TextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CharcoalSurface,
                    unfocusedContainerColor = CharcoalSurface,
                    focusedBorderColor = ElectricViolet,
                    unfocusedBorderColor = Color(0x28FFFFFF),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CharcoalSurface.copy(alpha = 0.75f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isGuestSession) "Cloud Sync (Disabled in Guest Mode)" else "Cloud Synchronization",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isGuestSession) "Guest / Testing uses temporary local storage only. Sign in with Google for cloud backup."
                        else "Sync conversation threads to your Firestore account",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = !isGuestSession && cloudSyncEnabled,
                    enabled = !isGuestSession,
                    onCheckedChange = onToggleCloudSync,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricViolet)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No conversations yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                        Text("Tap the mic or start a text chat to talk with SAATHI.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        SaathiGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenConversation(conv.id) }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = conv.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = conv.lastMessagePreview,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "${conv.languageCode} • ${dateFmt.format(Date(conv.updatedAtEpochMs))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoftBlue
                                    )
                                }
                                IconButton(onClick = {
                                    renameTarget = conv
                                    renameDraft = conv.title
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = TextSecondary)
                                }
                                IconButton(onClick = { onDeleteConversation(conv.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CrimsonRose)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen(
    memories: List<MemoryEntity>,
    memoryEnabled: Boolean,
    isGuestSession: Boolean = false,
    onToggleMemoryEnabled: (Boolean) -> Unit,
    onSaveMemory: (String, String, String?) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearAllMemories: () -> Unit,
    onBack: () -> Unit
) {
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var categoryDraft by remember { mutableStateOf("Interest") }
    var contentDraft by remember { mutableStateOf("") }

    if (showAddDialog || editingMemory != null) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingMemory = null
            },
            containerColor = CharcoalSurface,
            title = { Text(if (editingMemory == null) "Add Memory" else "Edit Memory", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = categoryDraft,
                        onValueChange = { categoryDraft = it },
                        label = { Text("Category (Name, Interest, Project...)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = contentDraft,
                        onValueChange = { contentDraft = it },
                        label = { Text("What should SAATHI remember?") },
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveMemory(categoryDraft, contentDraft, editingMemory?.id)
                    showAddDialog = false
                    editingMemory = null
                }) {
                    Text("Save", color = SoftBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    editingMemory = null
                }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = CharcoalSurface,
            title = { Text("Clear All Memories?", color = TextPrimary) },
            text = { Text("SAATHI will immediately forget all saved memories in future conversations.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAllMemories()
                    showClearConfirm = false
                }) {
                    Text("Clear All", color = CrimsonRose)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    SaathiCosmicBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("memory_back_button")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Memory & Privacy", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                            Text("You control everything SAATHI remembers", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    }
                    if (memories.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text("Clear", color = CrimsonRose)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Long-Term Memory", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                text = if (isGuestSession) "Guest / Testing: saved locally only (cloud memory disabled)."
                                else "When off, SAATHI only sees the current chat.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = memoryEnabled,
                            onCheckedChange = onToggleMemoryEnabled,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricViolet),
                            modifier = Modifier.testTag("memory_enabled_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (memories.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ElectricViolet, modifier = Modifier.size(44.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No saved memories yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                            Text("SAATHI will ask permission before saving useful facts, or tap + to add one.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 84.dp)
                    ) {
                        items(memories, key = { it.id }) { mem ->
                            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mem.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = SoftBlue)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(mem.content, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                    }
                                    IconButton(onClick = {
                                        editingMemory = mem
                                        categoryDraft = mem.category
                                        contentDraft = mem.content
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Memory", tint = TextSecondary)
                                    }
                                    IconButton(onClick = { onDeleteMemory(mem.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Memory", tint = CrimsonRose)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    editingMemory = null
                    categoryDraft = "Interest"
                    contentDraft = ""
                    showAddDialog = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .testTag("add_memory_fab"),
                containerColor = ElectricViolet,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Memory")
            }
        }
    }
}

@Composable
fun SettingsScreen(
    preferences: UserPreferencesState,
    onOpenProfile: () -> Unit,
    onOpenVoiceSelect: () -> Unit,
    onOpenLanguageSelect: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenHistory: () -> Unit,
    onToggleDailyReminders: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var showAboutDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            containerColor = CharcoalSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SaathiInfinityLogo(size = 28.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("SAATHI", color = TextPrimary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SAATHI", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Created by Usman", style = MaterialTheme.typography.titleMedium, color = SoftBlue)
                    Text("Your Friend. Your World.", style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "A warm, privacy-first multilingual AI voice companion in Telugu, Hindi, and English.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close", color = SoftBlue)
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
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            }

            SettingsRowItem(
                icon = Icons.Default.Person,
                title = "Profile",
                subtitle = "Account, preferred name & cloud sync",
                testTag = "settings_profile",
                onClick = onOpenProfile
            )

            val activeVoice = SaathiVoiceCatalog.findById(preferences.selectedVoiceId)
            SettingsRowItem(
                icon = Icons.Default.GraphicEq,
                title = "Voice selection",
                subtitle = "${activeVoice.name} (${activeVoice.gender.displayName}) • Speed ${preferences.speechSpeed}x",
                testTag = "settings_voice",
                onClick = onOpenVoiceSelect
            )

            SettingsRowItem(
                icon = Icons.Default.Language,
                title = "Language selection",
                subtitle = "${preferences.preferredLanguage.displayName} (${preferences.preferredLanguage.nativeName})",
                testTag = "settings_language",
                onClick = onOpenLanguageSelect
            )

            SettingsRowItem(
                icon = Icons.Default.PrivacyTip,
                title = "Memory and privacy",
                subtitle = if (preferences.memoryEnabled) "Memory active • View, edit or delete" else "Memory paused",
                testTag = "settings_memory",
                onClick = onOpenMemories
            )

            SettingsRowItem(
                icon = Icons.Default.History,
                title = "Conversation history",
                subtitle = "Search, rename, resume or clear past chats",
                testTag = "settings_history",
                onClick = onOpenHistory
            )

            // Notifications (optional, off by default, zero manipulative streaks)
            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = SoftBlue)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Notifications", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text("Optional gentle check-in preference (off by default)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                    Switch(
                        checked = preferences.dailyRemindersEnabled,
                        onCheckedChange = onToggleDailyReminders,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricViolet)
                    )
                }
            }

            SettingsRowItem(
                icon = Icons.Default.Info,
                title = "About SAATHI",
                subtitle = "SAATHI • Created by Usman",
                testTag = "settings_about",
                onClick = { showAboutDialog = true }
            )
        }
    }
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    SaathiGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ElectricViolet.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = SoftBlue, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
        }
    }
}

@Composable
fun ProfileScreen(
    displayName: String,
    email: String,
    photoUrl: String?,
    preferences: UserPreferencesState,
    isDeleting: Boolean,
    isGuestSession: Boolean = false,
    onUpdatePreferredName: (String) -> Unit,
    onToggleMemory: (Boolean) -> Unit,
    onToggleCloudSync: (Boolean) -> Unit,
    onOpenLanguageSelect: () -> Unit,
    onOpenVoiceSelect: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onBack: () -> Unit
) {
    var nameDraft by remember(preferences.preferredName, displayName) {
        mutableStateOf(preferences.preferredName.ifBlank { displayName })
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = CharcoalSurface,
            title = { Text("Delete Account & Data?", color = CrimsonRose) },
            text = {
                Text(
                    "This permanently deletes your cloud conversations, saved memories, profile, and local cache.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteAccount()
                }) {
                    Text("Delete Everything", color = CrimsonRose, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
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
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("profile_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Profile & Account", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            }

            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = "Profile photo",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .border(2.dp, ElectricViolet, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(ElectricViolet, SoftBlue))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.firstOrNull()?.uppercase() ?: "S",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(displayName.ifBlank { "SAATHI Friend" }, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(email.ifBlank { "Google Account" }, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }

            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What should SAATHI call you?", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Preferred Name") }
                    )
                    Button(
                        onClick = { onUpdatePreferredName(nameDraft) },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                    ) {
                        Text("Save Name")
                    }
                }
            }

            val activeVoice = SaathiVoiceCatalog.findById(preferences.selectedVoiceId)
            SettingsRowItem(
                icon = Icons.Default.Language,
                title = "Preferred Language",
                subtitle = "${preferences.preferredLanguage.displayName} (${preferences.preferredLanguage.nativeName})",
                testTag = "profile_language",
                onClick = onOpenLanguageSelect
            )
            SettingsRowItem(
                icon = Icons.Default.GraphicEq,
                title = "Selected Voice",
                subtitle = "${activeVoice.name} • ${activeVoice.gender.displayName}",
                testTag = "profile_voice",
                onClick = onOpenVoiceSelect
            )

            SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Long-Term Memory", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text("Remember facts you approve", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Switch(checked = preferences.memoryEnabled, onCheckedChange = onToggleMemory)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isGuestSession) "Cloud Sync (Requires Sign-In)" else "Cloud Sync",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = if (isGuestSession) "Disabled in temporary Guest / Testing mode" else "Sync profile, memories & chats to Firestore",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = !isGuestSession && preferences.cloudSyncEnabled,
                            enabled = !isGuestSession,
                            onCheckedChange = onToggleCloudSync
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button"),
                shape = RoundedCornerShape(25.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isGuestSession) "Exit Guest Mode / Sign In with Google" else "Sign Out",
                    color = TextPrimary
                )
            }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = !isDeleting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("delete_account_button"),
                shape = RoundedCornerShape(25.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = CrimsonRose)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isDeleting -> "Clearing..."
                        isGuestSession -> "Clear Local Guest Data"
                        else -> "Delete Account & Data"
                    },
                    color = CrimsonRose
                )
            }
        }
    }
}
