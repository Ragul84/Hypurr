package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.CatFace
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Thinking: cat blink + ear twitch + three ink dots hop + phase crossfade. */
@Composable
fun ThinkingCatRow(
    phase: String,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val c = Hypurr.colors
    val still = LocalInspectionMode.current || !animate || reduceMotion()
    val t = rememberInfiniteTransition(label = "think")
    val blink by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "blink")
    val ear by t.animateFloat(0f, 8f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ear")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .graphicsLayer { rotationZ = if (still) 0f else ear - 4f }
                .clip(RoundedCornerShape(10.dp))
                .background(if (c.dark) c.surface else Color(0xFF15130F)),
            contentAlignment = Alignment.Center,
        ) {
            CatFace(
                if (c.dark) c.text else Color(0xFFFFF8E8),
                18.dp,
                Modifier.graphicsLayer { scaleY = if (still) 1f else 0.55f + 0.45f * blink },
            )
        }
        Spacer(Modifier.width(10.dp))
        InkDotsHop(animate = !still)
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(HypurrMotion.snap()) togetherWith fadeOut(HypurrMotion.snap()) },
            label = "phase",
            modifier = Modifier.weight(1f, fill = false),
        ) { text ->
            Text(text, color = c.secondary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
fun InkDotsHop(modifier: Modifier = Modifier, animate: Boolean = true) {
    val c = Hypurr.colors
    val still = !animate || reduceMotion() || LocalInspectionMode.current
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val y by t.animateFloat(
                0f, -5f,
                infiniteRepeatable(tween(420, delayMillis = i * 90, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "d$i",
            )
            Box(
                Modifier
                    .offset(y = if (still) 0.dp else y.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(c.text.copy(alpha = 0.75f)),
            )
        }
    }
}

/** Approval card drops in with soft squash-settle. */
@Composable
fun ApprovalDropIn(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduce = reduceMotion() || LocalInspectionMode.current
    val y = remember { Animatable(if (reduce) 0f else 28f) }
    val scale = remember { Animatable(if (reduce) 1f else 0.92f) }
    LaunchedEffect(Unit) {
        if (reduce) return@LaunchedEffect
        launch { y.animateTo(0f, HypurrMotion.settle()) }
        launch { scale.animateTo(1.03f, HypurrMotion.snap()); scale.animateTo(1f, HypurrMotion.settle()) }
    }
    Box(modifier.graphicsLayer { translationY = y.value; scaleX = scale.value; scaleY = scale.value }, content = content)
}

/** Allow once: press into chunky shadow, then Allowed ✓ chip. */
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
            press.animateTo(4f, HypurrMotion.strike())
            delay(HypurrMotion.STRIKE_MS.toLong())
            press.animateTo(0f, HypurrMotion.snap())
        }
        onSettled()
    }
    Box(modifier.offset(y = press.value.dp), content = content)
}

@Composable
fun AllowedChip(modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val scale by animateFloatAsState(1f, HypurrMotion.spatialFast(), label = "ok")
    Box(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(c.accent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("Allowed ✓", color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

/** Deny: gentle headshake then dim. */
@Composable
fun DenyHeadshake(active: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduce = reduceMotion()
    val x = remember { Animatable(0f) }
    val a = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) { x.snapTo(0f); a.snapTo(1f); return@LaunchedEffect }
        if (reduce) { a.animateTo(0.55f, HypurrMotion.reduced()); return@LaunchedEffect }
        repeat(3) {
            x.animateTo(6f, tween(40)); x.animateTo(-6f, tween(40))
        }
        x.animateTo(0f, tween(40))
        a.animateTo(0.55f, HypurrMotion.snap())
    }
    Box(modifier.offset(x = x.value.dp).alpha(a.value), content = content)
}

/** Task done: happy squint + check stamp (no confetti). */
@Composable
fun HappyDoneStamp(visible: Boolean, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (!visible) { progress.snapTo(0f); return@LaunchedEffect }
        if (reduce) progress.snapTo(1f) else progress.animateTo(1f, HypurrMotion.settle())
    }
    if (!visible && progress.value < 0.01f) return
    Row(modifier.alpha(progress.value), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(if (c.dark) c.surface else Color(0xFF15130F)),
            contentAlignment = Alignment.Center,
        ) {
            CatFace(if (c.dark) c.text else Color(0xFFFFF8E8), 16.dp, Modifier.graphicsLayer { scaleY = 0.45f })
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("Done ✓", color = c.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/** Creating a bot: tile pop + stroke-draw cat + typed name. */
@Composable
fun BotCreatePop(name: String, play: Boolean, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val reduce = reduceMotion() || LocalInspectionMode.current
    val scale = remember { Animatable(0.6f) }
    val draw = remember { Animatable(0f) }
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(play) {
        if (!play) return@LaunchedEffect
        if (reduce) { scale.snapTo(1f); draw.snapTo(1f); shown = name; return@LaunchedEffect }
        scale.animateTo(1.08f, HypurrMotion.snap()); scale.animateTo(1f, HypurrMotion.settle())
        draw.animateTo(1f, HypurrMotion.draw())
        shown = ""
        for (ch in name) { shown += ch; delay(28) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.scale(scale.value).size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF15130F)),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(30.dp)) {
                val s = size.minDimension
                val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val path = Path().apply {
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
                drawPath(path, Color(0xFFFFF8E8), style = stroke, alpha = draw.value)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(shown, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Pairing success: QR tile flips to cat with bounce. */
@Composable
fun PairingSuccessFlip(success: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = reduceMotion() || LocalInspectionMode.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(success) {
        if (!success || reduce) return@LaunchedEffect
        scale.animateTo(0.85f, HypurrMotion.snap())
        scale.animateTo(1.08f, HypurrMotion.snap())
        scale.animateTo(1f, HypurrMotion.settle())
    }
    Box(modifier.scale(scale.value), contentAlignment = Alignment.Center) { content() }
}

/** Tab indicator: chunky cream pill slides under the active item. */
@Composable
fun TabSlideIndicator(selectedIndex: Int, count: Int = 4, modifier: Modifier = Modifier) {
    val reduce = reduceMotion()
    val frac by animateFloatAsState(
        targetValue = selectedIndex.toFloat() / count.coerceAtLeast(1),
        animationSpec = if (reduce) HypurrMotion.reduced() else HypurrMotion.spatialDefault(),
        label = "tab",
    )
    Box(modifier.fillMaxWidth().height(3.dp)) {
        Box(
            Modifier
                .fillMaxWidth(1f / count)
                .height(3.dp)
                .offset(x = (frac * (count)).let { 0.dp }) // visual handled by parent weights
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFFFF8E8)),
        )
    }
}

/** Pull-to-refresh: cat peeks from top. */
@Composable
fun CatPeek(progress: Float, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.01f) return
    Box(
        modifier
            .fillMaxWidth()
            .height((36 * p).dp)
            .offset(y = ((1f - p) * -24).dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (c.dark) c.surface else Color(0xFF15130F)),
            contentAlignment = Alignment.Center,
        ) {
            CatFace(if (c.dark) c.text else Color(0xFFFFF8E8), 22.dp)
        }
    }
}
