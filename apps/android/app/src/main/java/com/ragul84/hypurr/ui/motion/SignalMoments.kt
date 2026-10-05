package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.delay

/** 4px forest signal band across a plate. */
@Composable
fun SignalBand(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Hypurr.colors.accent.copy(alpha = alpha)))
}

/** 3px left rail on live / Needs-you rows. */
@Composable
fun SignalRail(modifier: Modifier = Modifier, color: Color = Hypurr.colors.accent) {
    Box(modifier.width(3.dp).fillMaxHeight().background(color))
}

/**
 * Signature Signal strike overlay: flash full-width cyan band then invoke [onDone].
 * Reduced motion: skip flash, invoke after REDUCED_MS.
 */
@Composable
fun SignalStrikeFlash(active: Boolean, onDone: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val band = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) { band.snapTo(0f); return@LaunchedEffect }
        if (reduce) {
            delay(HypurrMotion.REDUCED_MS.toLong())
            onDone()
        } else {
            band.snapTo(1f)
            delay(HypurrMotion.STRIKE_MS.toLong())
            band.animateTo(0f, tween(HypurrMotion.STRIKE_MS, easing = LinearEasing))
            onDone()
        }
    }
    Box {
        content()
        if (band.value > 0.01f) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(4.dp).background(c.accent.copy(alpha = band.value)))
        }
    }
}

/** Deny: shear 4px + dim + red hairline. */
@Composable
fun DenyShear(active: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val shear = remember { Animatable(0f) }
    val dim = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) {
            shear.snapTo(0f); dim.snapTo(1f); return@LaunchedEffect
        }
        if (reduce) {
            dim.animateTo(0.55f, HypurrMotion.reduced())
        } else {
            shear.animateTo(4f, HypurrMotion.snap())
            dim.animateTo(0.55f, HypurrMotion.snap())
        }
    }
    Box(modifier.offset(x = shear.value.dp).alpha(dim.value)) {
        content()
        if (active) {
            Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight().background(c.danger))
        }
    }
}

/** Task done: cyan rule draws L→R. */
@Composable
fun DoneRule(visible: Boolean, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (!visible) { progress.snapTo(0f); return@LaunchedEffect }
        if (reduce) progress.snapTo(1f)
        else progress.animateTo(1f, HypurrMotion.draw())
    }
    Box(modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.border)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(c.accent))
    }
}

/** Tool running: progress hairline under a mono command. */
@Composable
fun ToolHairline(progress: Float, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val p = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(1.dp).background(c.border)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(c.accent))
    }
}

/** Neon status light-up: ≤2 flickers then steady (photosensitivity-safe). */
@Composable
fun NeonStatus(on: Boolean, color: Color = Hypurr.colors.accent, modifier: Modifier = Modifier) {
    val reduce = reduceMotion()
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        if (!on) { lit = false; return@LaunchedEffect }
        if (reduce) { lit = true; return@LaunchedEffect }
        lit = true; delay(HypurrMotion.STRIKE_MS.toLong())
        lit = false; delay(HypurrMotion.STRIKE_MS.toLong())
        lit = true; delay(HypurrMotion.STRIKE_MS.toLong())
        lit = false; delay(HypurrMotion.STRIKE_MS.toLong())
        lit = true
    }
    Box(modifier.width(8.dp).height(8.dp).background(if (lit) color else color.copy(alpha = 0.25f)))
}
