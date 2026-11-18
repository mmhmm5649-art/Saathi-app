package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.BuildConfig
import com.example.model.VoiceSessionState
import com.example.security.DebugGuestPolicy
import com.example.security.onGoogleSignInClicked
import com.example.ui.components.SaathiAnimatedVoiceOrb
import com.example.ui.components.SaathiCosmicBackground
import com.example.ui.components.SaathiGlassCard
import com.example.ui.components.SaathiInfinityLogo
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CrimsonRose
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.SoftBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    onSkipForNow: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    SaathiCosmicBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SaathiInfinityLogo(size = 36.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SAATHI",
                        style = MaterialTheme.typography.displayLarge.copy(
                            brush = Brush.linearGradient(listOf(TextPrimary, SoftBlue))
                        )
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your Friend. Your World.",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = TextSecondary,
                        fontStyle = FontStyle.Italic
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                SaathiAnimatedVoiceOrb(
                    sessionState = VoiceSessionState.IDLE,
                    amplitude = 0.22f,
                    orbSize = 156.dp
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Sign in once to sync your memories, conversations & preferences",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = SoftBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Private to your account • Never used for ads",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }

                        if (!errorMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CrimsonRose,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onAuthSuccess = {
                                        isLoading = false
                                        onAuthSuccess()
                                    },
                                    onAuthError = { err ->
                                        isLoading = false
                                        errorMessage = err
                                    },
                                    scope = scope,
                                    onAuthCancelled = {
                                        isLoading = false
                                    }
                                )
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("google_sign_in_button"),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.horizontalGradient(listOf(ElectricViolet, SoftBlue))),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Login,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Sign in with Google",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        if (DebugGuestPolicy.isSkipOptionVisible(isDebugBuild) && onSkipForNow != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onSkipForNow,
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("skip_for_now_button"),
                                shape = RoundedCornerShape(26.dp),
                                border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = CharcoalSurface.copy(alpha = 0.6f),
                                    contentColor = TextPrimary
                                )
                            ) {
                                Text(
                                    text = "Skip for Now",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Debug Testing Only • Opens temporary local guest mode (no cloud sync)",
                                style = MaterialTheme.typography.labelSmall,
                                color = SoftBlue.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Note: Google Sign-In is enabled automatically. Email/Password or Guest providers can be enabled in your Firebase Console if needed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Created by Usman",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
            }
        }
    }
}
