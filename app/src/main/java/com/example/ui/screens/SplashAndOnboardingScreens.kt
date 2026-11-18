package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SaathiLanguage
import com.example.model.VoiceGender
import com.example.model.VoiceSessionState
import com.example.ui.components.SaathiAnimatedVoiceOrb
import com.example.ui.components.SaathiCosmicBackground
import com.example.ui.components.SaathiGlassCard
import com.example.ui.components.SaathiInfinityLogo
import com.example.ui.theme.CharcoalElevated
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.SoftBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(1500L)
        onFinished()
    }

    SaathiCosmicBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SaathiAnimatedVoiceOrb(
                        sessionState = VoiceSessionState.IDLE,
                        amplitude = 0.2f,
                        orbSize = 200.dp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SaathiInfinityLogo(size = 36.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "SAATHI",
                            style = MaterialTheme.typography.displayLarge.copy(
                                brush = Brush.linearGradient(listOf(TextPrimary, SoftBlue))
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your Friend. Your World.",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = TextSecondary,
                            fontStyle = FontStyle.Italic
                        )
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                    Text(
                        text = "Created by Usman",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = SoftBlue.copy(alpha = 0.85f),
                            letterSpacing = 1.2.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(
    initialLanguage: SaathiLanguage,
    initialGender: VoiceGender,
    onComplete: (SaathiLanguage, VoiceGender) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedLanguage by remember { mutableStateOf(initialLanguage) }
    var selectedGender by remember { mutableStateOf(initialGender) }

    SaathiCosmicBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    IconButton(
                        onClick = { step -= 1 },
                        modifier = Modifier.testTag("onboarding_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SaathiInfinityLogo(size = 28.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SAATHI",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                    }
                }

                // Step progress dots
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { idx ->
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (idx == step) 24.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx <= step) ElectricViolet else CharcoalElevated
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (step) {
                0 -> {
                    SaathiAnimatedVoiceOrb(
                        sessionState = VoiceSessionState.IDLE,
                        amplitude = 0.25f,
                        orbSize = 175.dp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Meet SAATHI.\nSomeone you can talk to, anytime.",
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Your Friend. Your World. — Created by Usman",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoftBlue,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(22.dp))

                    SaathiGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            OnboardingFeatureRow(Icons.Default.Mic, "Talk naturally by voice or switch to text anytime")
                            OnboardingFeatureRow(Icons.Default.RecordVoiceOver, "Choose a warm Male or Female voice")
                            OnboardingFeatureRow(Icons.Default.Language, "Speak pure Telugu, Hindi, or English")
                            OnboardingFeatureRow(Icons.Default.AutoAwesome, "Save helpful memories with your permission")
                        }
                    }
                }

                1 -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Choose your language",
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SAATHI speaks natural everyday Telugu, Hindi, or English. You can switch anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SaathiLanguage.entries.forEach { lang ->
                            val isSelected = selectedLanguage == lang
                            OnboardingChoiceCard(
                                title = "${lang.displayName}  •  ${lang.nativeName}",
                                subtitle = lang.sampleReply,
                                badge = lang.badgeCode,
                                selected = isSelected,
                                testTag = "onboarding_lang_${lang.name.lowercase()}",
                                onClick = { selectedLanguage = lang }
                            )
                        }
                    }
                }

                2 -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Choose SAATHI's voice",
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pick a natural Male or Female Indian companion voice. The same voice speaks Telugu, Hindi, and English seamlessly, and you can preview & customize voices anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OnboardingChoiceCard(
                            title = "Female Voice  •  Natural Indian",
                            subtitle = "Warm, expressive conversational delivery across ${selectedLanguage.displayName}, Hindi & English",
                            badge = "F",
                            selected = selectedGender == VoiceGender.FEMALE,
                            testTag = "onboarding_voice_female",
                            onClick = { selectedGender = VoiceGender.FEMALE }
                        )
                        OnboardingChoiceCard(
                            title = "Male Voice  •  Natural Indian",
                            subtitle = "Grounded, friendly everyday tone across ${selectedLanguage.displayName}, Hindi & English",
                            badge = "M",
                            selected = selectedGender == VoiceGender.MALE,
                            testTag = "onboarding_voice_male",
                            onClick = { selectedGender = VoiceGender.MALE }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (step < 2) step += 1
                    else onComplete(selectedLanguage, selectedGender)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("onboarding_continue_button"),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = androidx.compose.foundation.layout.PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(ElectricViolet, SoftBlue))),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (step < 2) "Continue" else "Start with SAATHI",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (step < 2) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingFeatureRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(ElectricViolet.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = SoftBlue, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

@Composable
private fun OnboardingChoiceCard(
    title: String,
    subtitle: String,
    badge: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) CharcoalElevated else CharcoalSurface.copy(alpha = 0.8f))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                brush = if (selected) {
                    Brush.horizontalGradient(listOf(ElectricViolet, SoftBlue))
                } else {
                    Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF)))
                },
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(18.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (selected) Brush.linearGradient(listOf(ElectricViolet, SoftBlue))
                    else Brush.linearGradient(listOf(CharcoalElevated, CharcoalElevated))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = SoftBlue
            )
        }
    }
}
