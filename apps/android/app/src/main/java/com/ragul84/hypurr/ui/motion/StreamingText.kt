package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr

/** Streaming: resolved ink + 1px cyan caret at the frontier. */
@Composable
fun StreamingText(
    text: String,
    revealed: Int = text.length,
    style: TextStyle,
    modifier: Modifier = Modifier,
    showCaret: Boolean = true,
) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val blink = rememberInfiniteTransition(label = "caret")
    val alpha by blink.animateFloat(
        1f, 0.15f,
        infiniteRepeatable(tween(HypurrMotion.SNAP_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "a",
    )
    val shown = text.take(revealed.coerceIn(0, text.length))
    val pending = text.drop(shown.length)
    Row(modifier) {
        if (shown.isNotEmpty()) Text(shown, style = style, color = c.text)
        if (pending.isNotEmpty()) Text(pending.take(24), style = style, color = c.tertiary)
        if (showCaret) {
            Box(
                Modifier
                    .width(1.dp)
                    .height((style.fontSize.value * 1.15f).dp)
                    .background(c.accent.copy(alpha = if (reduce) 0.7f else alpha)),
            )
        }
    }
}
