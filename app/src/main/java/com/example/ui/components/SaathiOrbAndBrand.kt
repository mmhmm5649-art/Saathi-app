package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.VoiceSessionState
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.SoftBlue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SaathiCosmicBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Top-left violet nebula glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ElectricViolet.copy(alpha = 0.22f),
                        ElectricViolet.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.1f, size.height * 0.08f),
                    radius = size.width * 0.72f
                ),
                center = Offset(size.width * 0.1f, size.height * 0.08f),
                radius = size.width * 0.72f
            )
            // Top-right soft blue glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        SoftBlue.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.92f, size.height * 0.2f),
                    radius = size.width * 0.6f
                ),
                center = Offset(size.width * 0.92f, size.height * 0.2f),
                radius = size.width * 0.6f
            )
            // Bottom ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ElectricIndigo.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.96f),
                    radius = size.width * 0.75f
                ),
                center = Offset(size.width * 0.5f, size.height * 0.96f),
                radius = size.width * 0.75f
            )
        }
        content()
    }
}

@Composable
fun SaathiInfinityLogo(
    modifier: Modifier = Modifier,
    size: Dp = 38.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.5f)
            cubicTo(
                w * 0.16f, h * 0.18f,
                w * 0.45f, h * 0.22f,
                w * 0.5f, h * 0.5f
            )
            cubicTo(
                w * 0.55f, h * 0.78f,
                w * 0.84f, h * 0.82f,
                w * 0.84f, h * 0.5f
            )
            cubicTo(
                w * 0.84f, h * 0.18f,
                w * 0.55f, h * 0.22f,
                w * 0.5f, h * 0.5f
            )
            cubicTo(
                w * 0.45f, h * 0.78f,
                w * 0.16f, h * 0.82f,
                w * 0.16f, h * 0.5f
            )
        }

        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(ElectricViolet, SoftBlue)
            ),
            style = Stroke(width = w * 0.14f, cap = StrokeCap.Round)
        )
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.55f),
            style = Stroke(width = w * 0.04f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun SaathiAnimatedVoiceOrb(
    sessionState: VoiceSessionState,
    amplitude: Float,
    modifier: Modifier = Modifier,
    orbSize: Dp = 210.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_motion")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (sessionState) {
                    VoiceSessionState.THINKING -> 2600
                    VoiceSessionState.GREETING, VoiceSessionState.SPEAKING -> 4200
                    VoiceSessionState.LISTENING -> 5200
                    else -> 9500
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    val breath by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_breath"
    )

    val smoothAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0.05f, 1.0f),
        animationSpec = spring(stiffness = 280f, dampingRatio = 0.7f),
        label = "orb_amp"
    )

    val activeScale = when (sessionState) {
        VoiceSessionState.LISTENING -> breath + smoothAmp * 0.12f
        VoiceSessionState.GREETING, VoiceSessionState.SPEAKING -> breath + smoothAmp * 0.14f
        VoiceSessionState.THINKING -> breath * 1.02f
        else -> breath
    }

    Canvas(modifier = modifier.size(orbSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val coreRadius = (size.minDimension * 0.30f) * activeScale

        // Outer cosmic aura
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ElectricViolet.copy(alpha = 0.42f),
                    SoftBlue.copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = coreRadius * 1.9f
            ),
            center = Offset(cx, cy),
            radius = coreRadius * 1.9f
        )

        // Tilted orbital plasma ring
        rotate(degrees = -16f, pivot = Offset(cx, cy)) {
            drawOval(
                brush = Brush.linearGradient(
                    colors = listOf(
                        ElectricViolet.copy(alpha = 0.8f),
                        SoftBlue.copy(alpha = 0.85f),
                        NeonMagenta.copy(alpha = 0.7f)
                    )
                ),
                topLeft = Offset(cx - coreRadius * 1.55f, cy - coreRadius * 0.56f),
                size = Size(coreRadius * 3.1f, coreRadius * 1.12f),
                style = Stroke(width = 3.2f)
            )
        }

        // Core plasma sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF2B1B54),
                    Color(0xFF14193D),
                    Color(0xFF0A0D22)
                ),
                center = Offset(cx, cy),
                radius = coreRadius
            ),
            center = Offset(cx, cy),
            radius = coreRadius
        )

        // Swirling internal plasma ribbons
        rotate(degrees = rotation, pivot = Offset(cx, cy)) {
            val wavePath1 = Path().apply {
                moveTo(cx - coreRadius * 0.9f, cy)
                cubicTo(
                    cx - coreRadius * 0.35f, cy - coreRadius * (0.75f + smoothAmp * 0.25f),
                    cx + coreRadius * 0.35f, cy + coreRadius * (0.75f + smoothAmp * 0.25f),
                    cx + coreRadius * 0.9f, cy
                )
            }
            drawPath(
                path = wavePath1,
                brush = Brush.linearGradient(listOf(NeonMagenta, SoftBlue)),
                style = Stroke(width = coreRadius * 0.22f, cap = StrokeCap.Round)
            )

            val wavePath2 = Path().apply {
                moveTo(cx, cy - coreRadius * 0.88f)
                cubicTo(
                    cx + coreRadius * 0.7f, cy - coreRadius * 0.2f,
                    cx - coreRadius * 0.7f, cy + coreRadius * 0.2f,
                    cx, cy + coreRadius * 0.88f
                )
            }
            drawPath(
                path = wavePath2,
                brush = Brush.linearGradient(listOf(SoftBlue, ElectricViolet)),
                style = Stroke(width = coreRadius * 0.16f, cap = StrokeCap.Round)
            )
        }

        // Crisp luminous rim
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    ElectricViolet,
                    SoftBlue,
                    Color.White.copy(alpha = 0.85f),
                    NeonMagenta,
                    ElectricViolet
                ),
                center = Offset(cx, cy)
            ),
            center = Offset(cx, cy),
            radius = coreRadius,
            style = Stroke(width = 4.5f)
        )

        // Subtle orbiting star particles
        for (i in 0 until 8) {
            val angle = (rotation * 0.6f + i * 45f) * (PI / 180.0)
            val dist = coreRadius * (1.28f + (i % 3) * 0.16f)
            val px = cx + (cos(angle) * dist).toFloat()
            val py = cy + (sin(angle) * dist * 0.72f).toFloat()
            drawCircle(
                color = if (i % 2 == 0) SoftBlue.copy(alpha = 0.75f) else NeonMagenta.copy(alpha = 0.75f),
                radius = if (i % 3 == 0) 3.2f else 2.0f,
                center = Offset(px, py)
            )
        }
    }
}

@Composable
fun SaathiAudioWaveform(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val ampScale by animateFloatAsState(
        targetValue = if (isActive) amplitude.coerceIn(0.2f, 1.0f) else 0.18f,
        animationSpec = spring(stiffness = 320f, dampingRatio = 0.75f),
        label = "wave_amp"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        val barCount = 35
        val centerIdx = barCount / 2f
        val step = size.width / (barCount + 1)
        val barWidth = (step * 0.46f).coerceIn(4f, 8f)

        for (i in 0 until barCount) {
            val distFromCenter = abs(i - centerIdx) / centerIdx
            val envelope = (1f - distFromCenter * distFromCenter).coerceAtLeast(0.12f)
            val wave = abs(sin(phase + i * 0.48f))
            val barHeight = (size.height * (0.14f + 0.82f * envelope * wave * ampScale))
                .coerceIn(4f, size.height)

            val x = step * (i + 1) - barWidth / 2f
            val y = (size.height - barHeight) / 2f
            val fraction = i.toFloat() / (barCount - 1)
            val color = lerpColor(ElectricViolet, SoftBlue, fraction)

            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * envelope),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun SaathiGlassCard(
    modifier: Modifier = Modifier,
    borderBrush: Brush = Brush.linearGradient(
        colors = listOf(ElectricViolet.copy(alpha = 0.45f), SoftBlue.copy(alpha = 0.45f))
    ),
    cornerRadius: Dp = 22.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CharcoalSurface.copy(alpha = 0.88f),
                        Color(0xFF0A0D1A).copy(alpha = 0.92f)
                    )
                )
            )
            .border(width = 1.dp, brush = borderBrush, shape = shape)
            .padding(16.dp),
        content = content
    )
}

private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (stop.red - start.red) * f,
        green = start.green + (stop.green - start.green) * f,
        blue = start.blue + (stop.blue - start.blue) * f,
        alpha = start.alpha + (stop.alpha - start.alpha) * f
    )
}
