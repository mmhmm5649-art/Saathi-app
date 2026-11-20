package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.local.UserPreferencesState
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.VoiceCompanionScreen
import com.example.ui.theme.SaathiTheme
import com.example.viewmodel.CompanionUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalCompanionAuthlessFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `voice companion renders online without any Google Sign-In chip`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SaathiTheme {
                VoiceCompanionScreen(
                    uiState = CompanionUiState(isGuestSession = true),
                    preferences = UserPreferencesState(),
                    onMicClick = {},
                    onLanguageSelected = {},
                    onEndConversation = {},
                    onToggleMute = {},
                    onOpenTextChat = {},
                    onOpenVoiceSelect = {},
                    onOpenSettings = {},
                    onOpenMemories = {},
                    onOpenHistory = {},
                    onAcceptMemory = {},
                    onDismissMemory = {},
                    onRetryError = {},
                    onDismissError = {},
                    onVoiceScreenReady = {},
                    onMicPermissionResult = {}
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(120L)

        composeRule.onNodeWithText("SAATHI").assertIsDisplayed()
        composeRule.onNodeWithText("Online").assertIsDisplayed()
        composeRule.onNodeWithTag("guest_sign_in_chip").assertDoesNotExist()
        composeRule.onNodeWithText("Continue with Google", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Sign In", substring = true).assertDoesNotExist()
    }

    @Test
    fun `profile screen renders local private profile without sign-out or Google login actions`() {
        var cleared = false

        composeRule.setContent {
            SaathiTheme {
                ProfileScreen(
                    displayName = "SAATHI Friend",
                    email = "Private on-device profile",
                    photoUrl = null,
                    preferences = UserPreferencesState(preferredName = "Usman"),
                    isDeleting = false,
                    isGuestSession = true,
                    onUpdatePreferredName = {},
                    onToggleMemory = {},
                    onToggleCloudSync = {},
                    onOpenLanguageSelect = {},
                    onOpenVoiceSelect = {},
                    onDeleteAccount = { cleared = true },
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Private on-device profile").assertIsDisplayed()
        composeRule.onNodeWithTag("sign_out_button").assertDoesNotExist()
        composeRule.onNodeWithText("Sign Out", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Sign in with Google", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("delete_account_button").performScrollTo().performClick()
        composeRule.onNodeWithText("Clear Local Data?").assertIsDisplayed()
        composeRule.onNodeWithText("Clear Everything").performClick()
        assertTrue(cleared)
    }

    @Test
    fun `history screen explains local on-device storage without Google sign-in prompts`() {
        composeRule.setContent {
            SaathiTheme {
                HistoryScreen(
                    conversations = emptyList(),
                    searchQuery = "",
                    cloudSyncEnabled = false,
                    isGuestSession = true,
                    onSearchChange = {},
                    onOpenConversation = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onToggleCloudSync = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Local Storage (On Device)").assertIsDisplayed()
        composeRule.onNodeWithText("Conversations are saved privately on this device.").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with Google", substring = true).assertDoesNotExist()
    }
}
