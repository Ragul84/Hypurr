package com.ragul84.hypurr.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.motion.AllowPressEffect
import com.ragul84.hypurr.ui.motion.DenyNudge
import com.ragul84.hypurr.ui.motion.NeedsYouPulse
import com.ragul84.hypurr.ui.motion.ThinkingRow
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.InterFamily
import com.ragul84.hypurr.ui.theme.Motion
import com.ragul84.hypurr.ui.theme.SignalType

/** Card corner radius from the mockup (--r: 24px). */
val CardRadius = 24.dp
val CardShape = RoundedCornerShape(CardRadius)

/** A flat panel for sheets and menus: solid fill, hairline border. Signal has no glass. */
@Composable
fun Modifier.panel(shape: Shape, fill: Color = Hypurr.colors.surface): Modifier {
    val c = Hypurr.colors
    return this.clip(shape).background(fill).border(1.dp, c.border, shape)
}

/** Press feedback shared by every tappable control: a crisp spring scale to 0.97. */
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

/**
 * The Signal card: white (light) or #111 (dark), 24dp corners, hairline border, no shadow.
 * [emphasis] swaps the hairline for a 1.5dp ink border (the bot or approval that needs you).
 */
@Composable
fun SignalCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CardShape,
    emphasis: Boolean = false,
    fill: Color = Hypurr.colors.surface,
    content: @Composable () -> Unit,
) {
    val c = Hypurr.colors
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(if (emphasis) 1.5.dp else 1.dp, if (emphasis) c.text else c.border, shape),
    ) { content() }
}

/** Icon-only button. Bare by default, like the mockup's search and more. Always 40dp+ to touch. */
@Composable
fun IconBubble(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Hypurr.colors.text,
               fill: Color = Color.Transparent, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(fill).pressable(label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Back / header icon: a bare 40dp target with the 22dp line icon. */
@Composable
fun IconTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = Hypurr.colors.text,
             onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).pressable(label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Primary action: the black pill (white in dark), 48dp tall. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = Hypurr.colors
    // No fade: the primary action must read as ready the instant it is (onboarding Continue).
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(c.accent.copy(alpha = alpha))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c.onAccent, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize))
    }
}

/** Secondary action: an outline pill on the card colour. [tint] colours the label (red only for needs-you). */
@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Hypurr.colors.text, onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(c.surface)
            .border(1.dp, c.borderStrong, RoundedCornerShape(50))
            .pressable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

/** Uppercase tracked label: "ARJUN'S LAPTOP IS ONLINE", "CREDITS LEFT". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Hypurr.colors.tertiary, dot: Color? = null, pulse: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (pulse) {
            NeedsYouPulse(size = 7.dp, animate = !LocalInspectionMode.current)
            Spacer(Modifier.width(4.dp))
        } else if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), color = color, style = SignalType.eyebrow, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Your bots ........ + New bot" */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, actionIcon: ImageVector? = null,
                  trailingIcon: ImageVector? = null, onAction: () -> Unit = {}) {
    val c = Hypurr.colors
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * 0.94f),
            modifier = Modifier.weight(1f))
        if (action != null) {
            Row(Modifier.clip(RoundedCornerShape(50)).pressable(action, onClick = onAction).padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (actionIcon != null) {
                    Icon(actionIcon, null, tint = c.text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(action, color = c.text, style = MaterialTheme.typography.labelLarge)
                if (trailingIcon != null) {
                    Spacer(Modifier.width(2.dp))
                    Icon(trailingIcon, null, tint = c.text, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Status words with a dot: "● Needs you" (red), "● Running" (ink), "● Done" / "● Paused" (muted). */
enum class BotState { NeedsYou, Running, Done, Paused, Idle, Failed }

fun Bot.signalState(): BotState = when {
    needsInput -> BotState.NeedsYou
    failed -> BotState.Failed
    status == "working" -> BotState.Running
    task?.isActive == false -> BotState.Done
    else -> BotState.Idle
}

@Composable
fun StatusLabel(state: BotState, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val (label, color) = when (state) {
        BotState.NeedsYou -> "Needs you" to c.needsText
        BotState.Failed -> "Failed" to c.needsText
        BotState.Running -> "Running" to c.text
        BotState.Done -> "Done" to c.tertiary
        BotState.Paused -> "Paused" to c.tertiary
        BotState.Idle -> "Ready" to c.tertiary
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (state == BotState.NeedsYou) NeedsYouPulse(size = 6.dp, animate = !LocalInspectionMode.current)
        else Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(if (state == BotState.NeedsYou) 3.dp else 6.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

/** Folder / branch chip: pill, hairline, mono text. */
@Composable
fun SignalChip(icon: ImageVector, text: String, modifier: Modifier = Modifier, quiet: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Hypurr.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = 32.dp).clip(shape)
            .background(if (quiet) Color.Transparent else c.surface)
            .border(1.dp, c.border, shape)
            .then(if (onClick != null) Modifier.pressable(text, onClick = onClick) else Modifier)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (quiet) c.secondary else c.text, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp))
    }
}

/** Two-way segmented pill: "Week | Month". */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit = {}) {
    val c = Hypurr.colors
    Row(modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(50)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(o, color = if (on) c.onAccent else c.tertiary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else Color.Transparent)
                    .pressable(o) { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/**
 * A big figure in Doto. Doto has no ₹ glyph, so currency signs fall back to Inter at the same size
 * (the mockup does the same).
 */
@Composable
fun DotFigure(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val annotated = buildAnnotatedString {
        text.forEach { ch ->
            if (ch == '₹' || ch == '€' || ch == '£' || ch == '.') withStyle(SpanStyle(fontFamily = InterFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(ch) }
            else append(ch)
        }
    }
    Text(annotated, style = style, color = color, modifier = modifier, maxLines = 1)
}

/** Dot-matrix bar chart: columns of dots; [highlight] (today) is red. */
@Composable
fun DotBars(values: List<Float>, labels: List<String>, highlight: Int, modifier: Modifier = Modifier, rows: Int = 7) {
    val c = Hypurr.colors
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            values.forEachIndexed { i, v ->
                val lit = if (v <= 0f) 0 else ((v / max) * rows).toInt().coerceIn(1, rows)
                val on = if (i == highlight) c.needs else c.text.copy(alpha = if (c.dark) 0.55f else 0.22f)
                Canvas(Modifier.width(24.dp).height((rows * 9).dp)) {
                    val r = 2.6.dp.toPx(); val step = 9.dp.toPx()
                    for (row in 0 until rows) for (col in 0 until 2) {
                        val y = size.height - r - row * step
                        val x = size.width / 2 + (col - 0.5f) * step
                        drawCircle(if (row < lit) on else c.dotOff.copy(alpha = 0.55f), r, Offset(x, y))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEachIndexed { i, l ->
                Text(l, color = if (i == highlight) c.text else c.tertiary, style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (i == highlight) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium),
                    modifier = Modifier.width(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Dotted progress line: ink dots for used, light dots for the rest. */
@Composable
fun DotProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = Hypurr.colors.text) {
    val c = Hypurr.colors
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val step = 7.dp.toPx(); val r = 2.2.dp.toPx()
        val n = (size.width / step).toInt().coerceAtLeast(1)
        val on = (n * fraction.coerceIn(0f, 1f)).toInt()
        for (i in 0 until n) drawCircle(if (i < on) color else c.dotOff, r, Offset(r + i * step, size.height / 2))
    }
}

/** Thin solid progress bar (bot card). */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.dotOff)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.text))
    }
}

/** Avatar palette ids (kit AvatarPalette) → a monochrome tone for the bot's dot cat when idle. */
fun avatarColor(id: String): Color = when (id) {
    "black", "asphalt", "ink" -> Color(0xFF0E0E0E)
    "green", "forest", "blue" -> Color(0xFF3A3A40)
    "cyan", "teal", "sunflower", "yellow" -> Color(0xFF5C5C63)
    "violet", "cream", "magenta", "amber" -> Color(0xFF7A7A82)
    "gray" -> Color(0xFF8A8A8F)
    else -> Color(0xFF5C5C63)
}

private fun avatarShape(id: String): Shape = when (id) {
    "circle" -> CircleShape
    else -> RoundedCornerShape(28)
}

/**
 * Bot tile: a quiet grey tile with the dot cat. The cat is red when the bot needs you, ink while it
 * runs, grey otherwise, exactly like the mockup.
 */
@Composable
fun BotAvatar(bot: Bot, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val dots = when {
        bot.needsInput -> c.needs
        bot.status == "working" -> c.text
        else -> if (c.dark) Color(0xFF8E8E95) else Color(0xFF8A8A8F)
    }
    Box(modifier.size(size).clip(avatarShape(bot.avatarShape)).background(c.surface2), contentAlignment = Alignment.Center) {
        DotCat(dots, size * 0.62f, off = c.dotOff.copy(alpha = if (c.dark) 0.9f else 0.7f))
    }
}

/** The link state as a quiet pill: Direct / Cloud relay / Offline / Connecting. */
@Composable
fun LinkPill(state: LinkState, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val (label, dot, ink) = when (state) {
        is LinkState.Ready -> Triple(if (state.route == Route.Direct) "Direct" else "Cloud relay", c.text, c.text)
        LinkState.Connecting -> Triple("Connecting", c.tertiary, c.secondary)
        is LinkState.HostOffline -> Triple("Computer offline", c.needs, c.needsText)
        is LinkState.Unauthorized -> Triple("Not allowed", c.needs, c.needsText)
        is LinkState.Failed -> Triple("Can't reach", c.needs, c.needsText)
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
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

/** Risk colour: high = signal red (needs you), medium = ink, low = muted. */
@Composable
fun riskColor(risk: String?): Color = when (risk) {
    "low" -> Hypurr.colors.tertiary
    "high" -> Hypurr.colors.needsText
    else -> Hypurr.colors.text
}

fun riskLabel(risk: String?): String = when (risk) {
    "low" -> "Low risk"
    "high" -> "High risk"
    else -> "Medium risk"
}

/** "HIGH RISK" tag: filled red for high, outline for medium/low. Icon and word, never colour alone. */
@Composable
fun RiskTag(risk: String?, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val high = risk == "high"
    val shape = RoundedCornerShape(6.dp)
    Text(
        riskLabel(risk).uppercase(),
        color = if (high) c.onNeeds else c.secondary,
        style = SignalType.eyebrow.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize, letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 8),
        modifier = modifier.clip(shape).background(if (high) c.needs else Color.Transparent)
            .then(if (high) Modifier else Modifier.border(1.dp, c.borderStrong, shape))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** A small quiet pill: task badge, cost. Grey fill, label in [color]. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Hypurr.colors
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.surface2).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = color)
    }
}

/** Template icon ids (host `templates`) → icons. */
@Composable
fun templateIcon(id: String): ImageVector = signalIcon(templateIconRes(id))

/** Shorten host option names for the approval buttons. */
fun optionLabel(name: String): String = when {
    name.equals("Always allow", ignoreCase = true) -> "Always"
    name.equals("Allow once", ignoreCase = true) -> "Allow once"
    else -> name
}

/** Allow: the black pill, presses in then fires [onAllow]. */
@Composable
fun SignalAllowButton(text: String, modifier: Modifier = Modifier, onAllow: () -> Unit) {
    val c = Hypurr.colors
    var striking by remember { mutableStateOf(false) }
    AllowPressEffect(striking = striking, modifier = modifier, onSettled = { if (striking) { onAllow(); striking = false } }) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(c.accent)
                .pressable(enabled = !striking, onClick = { if (!striking) striking = true }).padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(optionLabel(text), color = c.onAccent, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                maxLines = 1)
        }
    }
}

/** Outline pill: Deny and Always. Deny nudges, then dims. */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, deny: Boolean = false, onClick: () -> Unit) {
    val c = Hypurr.colors
    var denying by remember { mutableStateOf(false) }
    DenyNudge(denying, modifier) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(c.surface)
                .border(1.dp, c.borderStrong, RoundedCornerShape(50))
                .pressable(onClick = { if (deny) denying = true; onClick() }).padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(optionLabel(text), color = c.text, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                maxLines = 1)
        }
    }
}

/**
 * Approval actions, equal-size pills: Deny · (Always) · Allow once, Allow on the right like the mockup.
 * Maps host [PermissionOption] kinds; unknown kinds count as allow.
 */
@Composable
fun ApprovalActions(
    options: List<com.ragul84.hypurr.model.PermissionOption>,
    modifier: Modifier = Modifier,
    onChoose: (String?) -> Unit,
) {
    fun rank(o: com.ragul84.hypurr.model.PermissionOption) = when {
        o.kind.startsWith("reject") -> 0
        o.kind.contains("always", true) || o.name.contains("always", true) -> 1
        else -> 2
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.sortedBy(::rank).forEach { option ->
            when (rank(option)) {
                0 -> OutlineButton(option.name, Modifier.weight(1f), deny = true) { onChoose(option.optionId) }
                1 -> OutlineButton(option.name, Modifier.weight(1f)) { onChoose(option.optionId) }
                else -> SignalAllowButton(option.name, Modifier.weight(1f)) { onChoose(option.optionId) }
            }
        }
    }
}

/** Friendly working phase: the dot-matrix thinking row. */
@Composable
fun WorkingPhase(text: String, modifier: Modifier = Modifier, animate: Boolean = true) {
    ThinkingRow(phase = text, modifier = modifier, animate = animate)
}

/** Row helper for card list rows separated by hairlines. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Hypurr.colors.border))
}

