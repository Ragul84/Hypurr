package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr

/** Skeleton / refresh: diagonal 1px cyan rain streaks drifting (never grey pulse). */
@Composable
fun RainShimmer(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val t = rememberInfiniteTransition(label = "rain")
    val drift by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "d",
    )
    Box(modifier) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            val step = 18.dp.toPx()
            val stroke = 1.dp.toPx()
            val off = if (reduce) 0f else drift * step
            var x = -size.height - step
            while (x < size.width + size.height) {
                val x0 = x + off
                drawLine(
                    c.accent.copy(alpha = 0.14f),
                    Offset(x0, 0f),
                    Offset(x0 + size.height * 0.35f, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                x += step
            }
        }
    }
}
