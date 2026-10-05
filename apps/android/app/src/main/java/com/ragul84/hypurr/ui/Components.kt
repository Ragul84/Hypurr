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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.theme.ColorFlow
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion
import com.ragul84.hypurr.ui.motion.HypurrMotion
import com.ragul84.hypurr.ui.motion.reduceMotion

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

/** The primary action: forest (light) / sunflower (dark) fill, chunky round. */
@Composable
fun FlowButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = Hypurr.colors
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, Motion.effects(), label = "enabled")
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.accent.copy(alpha = alpha))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    }
}

/** Secondary action: tinted surface, no border. */
@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Hypurr.colors.accent, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
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
        Text(text, color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Sunfield orb: solid forest (light) / sunflower (dark), soft cream highlight, gentle breath. */
@Composable
fun FlowOrb(size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
    val c = Hypurr.colors
    val still = LocalInspectionMode.current || !animate || reduceMotion()
    val t = rememberInfiniteTransition(label = "orb")
    val breath by t.animateFloat(0.94f, 1.03f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "breath")
    val fill = if (c.dark) ColorFlow.sunflower else ColorFlow.signal
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).scale(if (still) 1f else breath)) {
            drawCircle(fill)
            drawCircle(
                Brush.radialGradient(
                    listOf(ColorFlow.cream.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(this.size.width * 0.35f, this.size.height * 0.3f),
                    radius = this.size.minDimension * 0.55f,
                ),
            )
        }
        CatFace(if (c.dark) ColorFlow.signal else ColorFlow.cream, size * 0.48f)
    }
}

/** Avatar palette ids (kit AvatarPalette) → colour. */
fun avatarColor(id: String): Color = when (id) {
    "black", "asphalt", "ink" -> Color(0xFF17140A)
    "green", "forest" -> Color(0xFF0E4A38)
    "cyan", "teal", "sunflower", "yellow" -> Color(0xFFF2B90D)
    "violet", "cream" -> Color(0xFFFFE7A8)
    "magenta", "amber" -> Color(0xFFE8C46A)
    "gray" -> Color(0xFF5C5640)
    "blue" -> Color(0xFF143D30)
    else -> Color(0xFF17140A)
}

private fun avatarShape(id: String): Shape = when (id) {
    "circle" -> CircleShape
    "pebble" -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 40, bottomEndPercent = 50, bottomStartPercent = 45)
    else -> RoundedCornerShape(30) // Sunfield cat tile
}

@Composable
fun BotAvatar(bot: Bot, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val color = avatarColor(bot.avatarColor)
    val on = if (color.luminance() > 0.55f) Color(0xFF17140A) else Color(0xFFFFF8E8)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(avatarShape(bot.avatarShape)).background(color),
            contentAlignment = Alignment.Center,
        ) {
            CatFace(on, size * 0.62f)
        }
        if (bot.isWorking) {
            val dot by animateColorAsState(if (bot.needsInput) Hypurr.colors.warning else Hypurr.colors.accent, Motion.effects(), label = "dot")
            Box(Modifier.align(Alignment.BottomEnd).size(size * 0.28f).clip(CircleShape).background(Hypurr.colors.bg).padding(2.dp)) {
                Box(Modifier.matchParentSize().clip(CircleShape).background(dot))
            }
        }
    }
}

/** Sunfield cat glyph drawn in [ink] stroke. */
@Composable
fun CatFace(ink: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.18f, s * 0.42f)
            lineTo(s * 0.30f, s * 0.08f)
            lineTo(s * 0.42f, s * 0.34f)
            lineTo(s * 0.58f, s * 0.34f)
            lineTo(s * 0.70f, s * 0.08f)
            lineTo(s * 0.82f, s * 0.42f)
            lineTo(s * 0.82f, s * 0.62f)
            cubicTo(s * 0.82f, s * 0.92f, s * 0.18f, s * 0.92f, s * 0.18f, s * 0.62f)
            close()
        }
        drawPath(path, color = ink, style = stroke)
        drawCircle(ink, radius = s * 0.05f, center = Offset(s * 0.38f, s * 0.52f))
        drawCircle(ink, radius = s * 0.05f, center = Offset(s * 0.62f, s * 0.52f))
        drawLine(ink, Offset(s * 0.42f, s * 0.68f), Offset(s * 0.58f, s * 0.68f), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
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


/**
 * Signature "Press Allow" on Allow: press-down → forest/sunflower band flash (~120ms) → invoke [onAllow].
 * The approval plate then collapses via host status update; a 3px left rail marks the live row.
 */
/** Solid forest/sunflower Allow — Sunfield primary CTA (replaces pale SoftButton strike). */
@Composable
fun SignalAllowButton(text: String, modifier: Modifier = Modifier, onAllow: () -> Unit) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    var striking by remember { mutableStateOf(false) }
    LaunchedEffect(striking) {
        if (!striking) return@LaunchedEffect
        delay((if (reduce) HypurrMotion.REDUCED_MS else HypurrMotion.STRIKE_MS).toLong())
        onAllow()
        striking = false
    }
    val label = sunfieldOptionLabel(text)
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.accent)
            .pressable(enabled = !striking, onClick = { if (!striking) striking = true })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

/** Shorten host option names to Sunfield labels. */
fun sunfieldOptionLabel(name: String): String = when {
    name.equals("Always allow", ignoreCase = true) -> "Always"
    name.equals("Allow once", ignoreCase = true) -> "Allow once"
    else -> name
}

/** Cream secondary button (Always). */
@Composable
fun CreamButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    val fill = if (c.dark) c.bg.copy(alpha = 0.35f) else Color(0xFFF3E6C8)
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(sunfieldOptionLabel(text), color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Text-only Deny. */
@Composable
fun TextDenyButton(text: String = "Deny", modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    Text(
        sunfieldOptionLabel(text),
        color = c.secondary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = modifier.pressable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/**
 * One-row approval actions: forest Allow once · cream Always · text Deny.
 * Maps host [PermissionOption] kinds onto Sunfield chrome.
 */
@Composable
fun ApprovalActions(
    options: List<com.ragul84.hypurr.model.PermissionOption>,
    modifier: Modifier = Modifier,
    onChoose: (String?) -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val reject = option.kind.startsWith("reject")
            val always = option.kind.contains("always", ignoreCase = true) ||
                option.name.contains("always", ignoreCase = true)
            when {
                reject -> TextDenyButton(option.name, Modifier, onClick = { onChoose(option.optionId) })
                always -> CreamButton(option.name, Modifier.weight(1f, fill = false), onClick = { onChoose(option.optionId) })
                else -> SignalAllowButton(option.name, Modifier.weight(1f, fill = false), onAllow = { onChoose(option.optionId) })
            }
        }
    }
}

/** Chunky ink square tile (back / cat header). */
@Composable
fun InkTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Dp = 40.dp, onClick: () -> Unit) {
    val c = Hypurr.colors
    val fill = if (c.dark) c.surface else Color(0xFF15130F)
    val ink = if (c.dark) c.text else Color(0xFFFFF8E8)
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(fill).pressable(label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.48f))
    }
}

/** Cream plate with a chunky offset press shadow (Sunfield cards). */
@Composable
fun CreamPlate(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(20.dp), content: @Composable () -> Unit) {
    val c = Hypurr.colors
    Box(modifier) {
        Box(Modifier.matchParentSize().padding(top = 4.dp).clip(shape).background(c.press.copy(alpha = if (c.dark) 0.45f else 0.22f)))
        Box(Modifier.clip(shape).background(c.surface)) { content() }
    }
}

/** Friendly working phase: Cabinet text + ink-dot blink (no mono scanner). */
@Composable
fun WorkingPhase(text: String, modifier: Modifier = Modifier, animate: Boolean = true) {
    val c = Hypurr.colors
    val still = LocalInspectionMode.current || !animate || reduceMotion()
    val t = rememberInfiniteTransition(label = "blink")
    val alpha by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(c.accent.copy(alpha = if (still) 0.85f else alpha)),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.secondary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 4px forest/sunflower signal band across a plate (Needs-you / live). */
@Composable
fun SignalBand(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Hypurr.colors.accent.copy(alpha = alpha)))
}

/** 3px left signal rail on a live / Needs-you row. */
@Composable
fun SignalRail(modifier: Modifier = Modifier) {
    Box(modifier.width(3.dp).height(40.dp).background(Hypurr.colors.accent))
}
