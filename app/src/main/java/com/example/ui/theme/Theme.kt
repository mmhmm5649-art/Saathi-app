package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SaathiDarkColorScheme = darkColorScheme(
    primary = ElectricViolet,
    onPrimary = Color.White,
    primaryContainer = DeepViolet,
    onPrimaryContainer = Color.White,
    secondary = SoftBlue,
    onSecondary = Color(0xFF04121E),
    secondaryContainer = Color(0xFF0F2942),
    onSecondaryContainer = SoftBlue,
    tertiary = NeonMagenta,
    onTertiary = Color(0xFF1E0836),
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = DeepObsidian,
    onSurface = TextPrimary,
    surfaceVariant = CharcoalSurface,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = CharcoalSurface,
    surfaceContainerHigh = CharcoalElevated,
    outline = GlassBorder,
    outlineVariant = Color(0xFF23273D),
    error = CrimsonRose,
    onError = Color.White
)

val SaathiShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SaathiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SaathiDarkColorScheme,
        typography = SaathiTypography,
        shapes = SaathiShapes,
        content = content
    )
}
