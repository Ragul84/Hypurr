package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.drawDotCat
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Signal moments (docs/design/hypurr-motion.md): dot-matrix, monochrome, red only for "needs you".
 * Every moment honours reduced motion and renders its end state in previews and screenshots.
 */

@Composable
private fun still(animate: Boolean = true): Boolean = !animate || reduceMotion() || LocalInspectionMode.current

/** Light haptic; no-op in inspection / reduced motion. */
@Composable
fun RememberHaptic(trigger: Any?, success: Boolean = false) {
    val h = LocalHapticFeedback.current
    val reduce = reduceMotion()
    val inspect = LocalInspectionMode.current
    LaunchedEffect(trigger) {
        if (trigger == null || reduce || inspect) return@LaunchedEffect
        h.performHapticFeedback(if (success) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
    }
}

/**
 * Thinking: a row of five dots lit by a scanning head, like a dot-matrix display redrawing.
 * Unlit dots stay visible so the row never jumps.
 */
@Composable
fun DotPulse(modifier: Modifier = Modifier, animate: Boolean = true, color: Color = Hypurr.colors.text, dots: Int = 5) {
    val c = Hypurr.colors
    val frozen = still(animate)
    val t = rememberInfiniteTransition(label = "pulse")
    val head by t.animateFloat(-1.5f, dots + 1.5f, infiniteRepeatable(tween(HypurrMotion.SCAN_MS, easing = LinearEasing)), label = "head")
    Canvas(modifier.width((dots * 9 - 3).dp).height(6.dp)) {
        val r = 3.dp.toPx()
        val step = 9.dp.toPx()
        for (i in 0 until dots) {
            val lit = if (frozen) (if (i == 1) 1f else if (i == 0 || i == 2) 0.45f else 0f)
            else (1f - abs(i - head) / 1.6f).coerceIn(0f, 1f)
            drawCircle(lerp(c.dotOff, color, lit), r, Offset(r + i * step, size.height / 2))
        }
    }
}

/** Thinking row: a small dot cat that blinks, the dot pulse, and the phase text crossfading. */
@Composable
fun ThinkingRow(phase: String, modifier: Modifier = Modifier, animate: Boolean = true) {
    val c = Hypurr.colors
    val frozen = still(animate)
    val t = rememberInfiniteTransition(label = "think")
    val blink by t.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = 2600
            0f at 0; 0f at 2200; 1f at 2280; 1f at 2380; 0f at 2460
        }),
        label = "blink",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DotCat(c.text, 18.dp, blink = if (frozen) 0f else blink)
        Spacer(Modifier.width(10.dp))
        DotPulse(animate = !frozen)
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(HypurrMotion.snap()) togetherWith fadeOut(HypurrMotion.snap()) },
            label = "phase",
            modifier = Modifier.weight(1f, fill = false),
        ) { text ->
            Text(text, color = c.secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

/** Needs you: a red dot with one ring breathing out every 1.4s. The only animated red in the app. */
@Composable
fun NeedsYouPulse(modifier: Modifier = Modifier, size: Dp = 8.dp, animate: Boolean = true) {
    val c = Hypurr.colors
    val frozen = still(animate)
    val t = rememberInfiniteTransition(label = "needs")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(HypurrMotion.PULSE_MS, easing = HypurrMotion.emphasized)), label = "ring")
    Canvas(modifier.size(size * 2)) {
        val core = size.toPx() / 2
        val center = Offset(this.size.width / 2, this.size.height / 2)
        if (!frozen) drawCircle(c.needs.copy(alpha = 0.35f * (1f - p)), core + (this.size.minDimension / 2 - core) * p, center)
        drawCircle(c.needs, core, center)
    }
}

/** Streaming caret: a red block at the end of the agent's live text, blinking at 530ms. */
@Composable
fun StreamCaret(modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val frozen = still()
    val t = rememberInfiniteTransition(label = "caret")
    val a by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(modifier.width(8.dp).height(17.dp).clip(RoundedCornerShape(2.dp)).background(c.needs.copy(alpha = if (frozen) 1f else a)))
}

/** Approval card: a crisp 16dp rise with a fade, 240ms emphasized. No squash. */
@Composable
fun ApprovalDropIn(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduce = reduceMotion() || LocalInspectionMode.current
    val y = remember { Animatable(if (reduce) 0f else 1f) }
    LaunchedEffect(Unit) { if (!reduce) y.animateTo(0f, HypurrMotion.settle()) }
    Box(modifier.graphicsLayer { translationY = y.value * 16.dp.toPx(); alpha = 1f - y.value }, content = content)
}

/** Allow once: the pill presses in to 0.96 and releases (120ms each way), then [onSettled]. */
@Composable
fun AllowPressEffect(
    striking: Boolean,
    modifier: Modifier = Modifier,
    onSettled: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val reduce = reduceMotion()
    val press = remember { Animatable(0f) }
    val h = LocalHapticFeedback.current
    LaunchedEffect(striking) {
        if (!striking) { press.snapTo(0f); return@LaunchedEffect }
        if (!reduce) {
            h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            press.animateTo(1f, HypurrMotion.press())
            press.animateTo(0f, HypurrMotion.press())
        }
        onSettled()
    }
    Box(modifier.graphicsLayer { val s = 1f - 0.04f * press.value; scaleX = s; scaleY = s }, content = content)
}

/** After Allow: an ink pill with a check that scales in. */
@Composable
fun AllowedChip(modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val reduce = reduceMotion() || LocalInspectionMode.current
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) p.animateTo(1f, HypurrMotion.settle()) }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.accent).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(signalIcon(SignalIcons.Check), null, tint = c.onAccent,
            modifier = Modifier.size(16.dp).graphicsLayer { scaleX = p.value; scaleY = p.value; alpha = p.value })
        Spacer(Modifier.width(6.dp))
        Text("Allowed", color = c.onAccent, style = MaterialTheme.typography.labelLarge)
    }
}

/** Deny: two short 3dp nudges, then the control dims. */
@Composable
fun DenyNudge(active: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduce = reduceMotion()
    val x = remember { Animatable(0f) }
    val a = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) { x.snapTo(0f); a.snapTo(1f); return@LaunchedEffect }
        if (reduce) { a.animateTo(0.5f, HypurrMotion.reduced()); return@LaunchedEffect }
        repeat(2) { x.animateTo(3f, tween(50)); x.animateTo(-3f, tween(50)) }
        x.animateTo(0f, tween(50))
        a.animateTo(0.5f, HypurrMotion.snap())
    }
    Box(modifier.offset(x = x.value.dp).alpha(a.value), content = content)
}

/** Task done: the cat squints (eyes filled), and a dotted rule fills in under "Done". */
@Composable
fun DoneStamp(visible: Boolean, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val reduce = reduceMotion() || LocalInspectionMode.current
    val p = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (!visible) { p.snapTo(0f); return@LaunchedEffect }
        if (reduce) p.snapTo(1f) else p.animateTo(1f, HypurrMotion.draw())
    }
    if (!visible && p.value < 0.01f) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DotCat(c.text, 22.dp, blink = (p.value * 2f).coerceAtMost(1f))
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon(SignalIcons.Check), null, tint = c.text, modifier = Modifier.size(16.dp).alpha(p.value))
                Spacer(Modifier.width(4.dp))
                Text("Done", color = c.text, style = MaterialTheme.typography.labelLarge)
            }
            DottedRule(p.value, Modifier.padding(top = 4.dp).width(64.dp))
        }
    }
}

/** A dotted rule that fills left to right with [progress]. */
@Composable
fun DottedRule(progress: Float, modifier: Modifier = Modifier, color: Color = Hypurr.colors.text) {
    val c = Hypurr.colors
    Canvas(modifier.height(4.dp)) {
        val step = 6.dp.toPx()
        val r = 1.5.dp.toPx()
        val n = (size.width / step).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val on = i < (n * progress).toInt() || progress >= 1f
            drawCircle(if (on) color else c.dotOff, r, Offset(r + i * step, size.height / 2))
        }
    }
}

/** Task done rule used by the learning card. */
@Composable
fun DoneRule(visible: Boolean, modifier: Modifier = Modifier) {
    val reduce = reduceMotion() || LocalInspectionMode.current
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(visible) { if (visible && !reduce) p.animateTo(1f, HypurrMotion.draw()) }
    if (visible) DottedRule(p.value, modifier.fillMaxWidth())
}

/**
 * Creating a bot: the dots of the cat fly in from a scatter and lock to the grid (760ms), then the
 * name types in at 28ms a letter.
 */
@Composable
fun CatAssemble(playing: Boolean, modifier: Modifier = Modifier, size: Dp = 96.dp, name: String = "") {
    val c = Hypurr.colors
    val reduce = reduceMotion() || LocalInspectionMode.current
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    var shown by remember { mutableStateOf(if (reduce) name else "") }
    LaunchedEffect(playing, name) {
        if (!playing) return@LaunchedEffect
        if (reduce) { p.snapTo(1f); shown = name; return@LaunchedEffect }
        p.snapTo(0f); shown = ""
        launch { p.animateTo(1f, HypurrMotion.assemble()) }
        delay(HypurrMotion.ASSEMBLE_MS * 2L / 3)
        for (ch in name) { shown += ch; delay(28) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(c.surface2), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size * 0.62f)) { drawDotCat(c.text, c.dotOff.copy(alpha = p.value), p.value) }
        }
        if (name.isNotEmpty()) {
            Text(shown, color = c.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp).height(26.dp))
        }
    }
}

/** Pairing success: content settles from 0.96 with the cat's dots lighting up in [content]. */
@Composable
fun PairingSuccess(success: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = reduceMotion() || LocalInspectionMode.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(success) {
        if (!success || reduce) return@LaunchedEffect
        scale.animateTo(0.96f, HypurrMotion.press())
        scale.animateTo(1f, HypurrMotion.settle())
    }
    Box(modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, contentAlignment = Alignment.Center) { content() }
}

/** Pull to refresh: the dot cat assembles as you pull. */
@Composable
fun CatPeek(progress: Float, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.01f) return
    Box(modifier.fillMaxWidth().padding(top = 8.dp).height((40 * p).dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            DotCat(c.text, 22.dp, reveal = p)
        }
    }
}

/** Loading: a faint dot grid with a slow diagonal brightness wave; [content] sits on top. */
@Composable
fun DotShimmer(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val c = Hypurr.colors
    val frozen = still()
    val t = rememberInfiniteTransition(label = "shimmer")
    val wave by t.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "wave")
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val step = 14.dp.toPx()
            val r = 1.6.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    val d = (x / size.width + y / size.height) / 2f
                    val lit = if (frozen) 0f else (1f - abs(d - wave) * 6f).coerceIn(0f, 1f)
                    drawCircle(lerp(c.dotOff.copy(alpha = 0.6f), c.tertiary, lit * 0.6f), r, Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        content()
    }
}

/** Brand mark: dot cat on a tile, used as the loading mark. The cat blinks unless [animate] is false. */
@Composable
fun DotCatMark(size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
    val c = Hypurr.colors
    val frozen = still(animate)
    val t = rememberInfiniteTransition(label = "mark")
    val blink by t.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes { durationMillis = 3000; 0f at 0; 0f at 2600; 1f at 2680; 1f at 2780; 0f at 2860 }),
        label = "blink",
    )
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(c.surface), contentAlignment = Alignment.Center) {
        DotCat(c.text, size * 0.6f, off = c.dotOff, blink = if (frozen) 0f else blink)
    }
}
