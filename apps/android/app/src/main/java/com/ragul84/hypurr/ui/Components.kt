package com.ragul84.hypurr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.theme.ColorFlow
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

/** A glass panel: translucent fill with a soft top highlight. Filled, so no border line. */
fun Modifier.glass(shape: Shape, tint: Color, highlight: Boolean = true): Modifier = this
    .clip(shape)
    .background(tint)
    .then(
        if (highlight) Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)))
        else Modifier,
    )

/** Press feedback shared by every tappable control: a spring scale to 0.96. */
@Composable
fun Modifier.pressable(label: String? = null, role: Role = Role.Button, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) Motion.PRESS_SCALE else 1f, Motion.spatialFast(), label = "press")
    return this
        .scale(scale)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** Icon-only button (CLAUDE.md: icon where an icon can express it, with an accessibility label). */
@Composable
fun IconBubble(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Hypurr.colors.text,
               fill: Color = Hypurr.colors.surface, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(fill).pressable(label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** The primary action: filled with the colour flow, ink text. */
@Composable
fun FlowButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, Motion.effects(), label = "enabled")
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(ColorFlow.linear(), alpha = alpha)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ColorFlow.FlowInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = ColorFlow.FlowInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** Secondary action: tinted surface, no border. */
@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Hypurr.colors.accent, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .pressable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** The thinking orb (Swift `ThinkingOrb(flow:)`): the colour flow turning, breathing while working. */
@Composable
fun FlowOrb(size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
    val still = LocalInspectionMode.current || !animate
    val t = rememberInfiniteTransition(label = "orb")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "angle")
    val breath by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breath")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).scale(if (still) 1f else breath).rotate(if (still) 30f else angle)) {
            drawCircle(ColorFlow.sweep())
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(this.size.width * 0.35f, this.size.height * 0.3f), radius = this.size.minDimension * 0.6f),
            )
        }
    }
}

/** Avatar palette ids (kit AvatarPalette) → colour. */
fun avatarColor(id: String): Color = when (id) {
    "black" -> Color(0xFF2B2B2B)
    "brown" -> Color(0xFF936439)
    "red" -> Color(0xFFFF263C)
    "orange" -> Color(0xFFFF6700)
    "yellow" -> Color(0xFFFF9800)
    "green" -> Color(0xFF00C972)
    "cyan" -> Color(0xFF00BCA6)
    "violet" -> Color(0xFF9159FE)
    "magenta" -> Color(0xFFFF309B)
    "gray" -> Color(0xFF777777)
    else -> Color(0xFF1084FE)
}

private fun avatarShape(id: String): Shape = when (id) {
    "squircle", "tablet" -> RoundedCornerShape(30)
    "pebble" -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 40, bottomEndPercent = 50, bottomStartPercent = 45)
    "wedge", "teardrop" -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 20, bottomStartPercent = 50)
    "hex" -> RoundedCornerShape(26)
    else -> CircleShape
}

@Composable
fun BotAvatar(bot: Bot, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val color = avatarColor(bot.avatarColor)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(avatarShape(bot.avatarShape))
                .background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(bot.name.take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp)
        }
        if (bot.isWorking) {
            // Working: a flow ring; needs you: the warning dot.
            val dot by animateColorAsState(if (bot.needsInput) Hypurr.colors.warning else ColorFlow.cyan, Motion.effects(), label = "dot")
            Box(Modifier.align(Alignment.BottomEnd).size(size * 0.32f).clip(CircleShape).background(Hypurr.colors.bg).padding(2.dp)) {
                Box(Modifier.matchParentSize().clip(CircleShape).background(dot))
            }
        }
    }
}

/** The link state as a small pill: Direct / Relay / Offline / Connecting. */
@Composable
fun LinkPill(state: LinkState, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val (label, color) = when (state) {
        is LinkState.Ready -> (if (state.route == Route.Direct) "Direct" else "Cloud relay") to c.success
        LinkState.Connecting -> "Connecting" to c.tertiary
        is LinkState.HostOffline -> "Computer offline" to c.warning
        is LinkState.Unauthorized -> "Not allowed" to c.danger
        is LinkState.Failed -> "Can't reach" to c.danger
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        CompositionLocalProvider(LocalContentColor provides color) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** Grok-style compact age: now · 5m · 3h · 2d · Sep 3. */
fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        s < 7 * 86_400 -> "${s / 86_400}d"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

/** Risk level colour: low = success, medium = warning, high = danger. */
@Composable
fun riskColor(risk: String?): Color = when (risk) {
    "low" -> Hypurr.colors.success
    "high" -> Hypurr.colors.danger
    else -> Hypurr.colors.warning
}

fun riskLabel(risk: String?): String = when (risk) {
    "low" -> "Low risk"
    "high" -> "High risk"
    else -> "Medium risk"
}

/** A small tinted pill: risk level, task badge. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Template icon ids (host `templates`) → icons. */
fun templateIcon(id: String): ImageVector = when (id) {
    "test" -> androidx.compose.material.icons.Icons.Rounded.Science
    "bug" -> androidx.compose.material.icons.Icons.Rounded.BugReport
    "review" -> androidx.compose.material.icons.Icons.Rounded.RateReview
    "deps" -> androidx.compose.material.icons.Icons.Rounded.Update
    "explain" -> androidx.compose.material.icons.Icons.Rounded.School
    else -> androidx.compose.material.icons.Icons.Rounded.AutoAwesome
}
