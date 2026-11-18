package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.local.UserPreferencesState
import com.example.security.DebugGuestPolicy
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.VoiceCompanionScreen
import com.example.ui.theme.SaathiTheme
import com.example.viewmodel.CompanionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DebugGuestPolicyAndAuthUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `debug build policy enables Skip and Guest session while release policy blocks both`() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(DebugGuestPolicy.isSkipOptionVisible(isDebugBuild = true))
        assertTrue(DebugGuestPolicy.isGuestSessionAllowed(isDebugBuild = true, guestRequested = true))

        // Simulated release build flag (BuildConfig.DEBUG && false -> false)
        assertFalse(DebugGuestPolicy.isSkipOptionVisible(isDebugBuild = false))
        assertFalse(DebugGuestPolicy.isGuestSessionAllowed(isDebugBuild = false, guestRequested = true))
    }

    @Test
    fun `debug AuthScreen shows Skip for Now below Google Sign-In and enters guest testing mode`() {
        var skipTappedCount = 0

        composeRule.setContent {
            SaathiTheme {
                AuthScreen(
                    onAuthSuccess = {},
                    isDebugBuild = true,
                    onSkipForNow = { skipTappedCount++ }
                )
            }
        }

        composeRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("skip_for_now_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Skip for Now").performScrollTo().performClick()

        assertEquals(1, skipTappedCount)
    }

    @Test
    fun `release AuthScreen hides Skip for Now completely even if callback passed`() {
        composeRule.setContent {
            SaathiTheme {
                AuthScreen(
                    onAuthSuccess = {},
                    isDebugBuild = false,
                    onSkipForNow = {}
                )
            }
        }

        composeRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("skip_for_now_button").assertDoesNotExist()
        composeRule.onNodeWithText("Skip for Now").assertDoesNotExist()
    }

    @Test
    fun `VoiceCompanionScreen clearly identifies Guest Testing session and allows returning to Sign In`() {
        var exitGuestCount = 0

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
                    onExitGuest = { exitGuestCount++ }
                )
            }
        }

        composeRule.onNodeWithText("Guest / Testing").assertIsDisplayed()
        composeRule.onNodeWithTag("guest_sign_in_chip").assertIsDisplayed().performClick()
        assertEquals(1, exitGuestCount)
    }
}
