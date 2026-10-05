package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr

/** Streaming: per-word resolve from ink-3 → ink with 1px ink caret. */
@Composable
fun StreamingText(
    text: String,
    revealedWords: Int = Int.MAX_VALUE,
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
    val words = rememberWords(text)
    val n = revealedWords.coerceIn(0, words.size)
    val shown = words.take(n).joinToString(" ")
    val pending = words.drop(n).joinToString(" ")
    Row(modifier) {
        if (shown.isNotEmpty()) Text(shown + if (pending.isNotEmpty()) " " else "", style = style, color = c.text)
        if (pending.isNotEmpty()) Text(pending, style = style, color = c.tertiary)
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

@Composable
private fun rememberWords(text: String): List<String> =
    androidx.compose.runtime.remember(text) { text.split(Regex("\\s+")).filter { it.isNotEmpty() } }
