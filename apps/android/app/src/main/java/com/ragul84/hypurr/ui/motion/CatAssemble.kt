package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr

/** Creating a bot: rain streaks converge into a flat cyan cat silhouette, then signal confirm. */
@Composable
fun CatAssemble(playing: Boolean, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val progress = remember { Animatable(0f) }
    val band = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (!playing) { progress.snapTo(0f); band.snapTo(0f); return@LaunchedEffect }
        if (reduce) {
            progress.snapTo(1f); band.snapTo(1f)
        } else {
            progress.animateTo(1f, HypurrMotion.assemble())
            band.snapTo(1f)
            band.animateTo(0f, HypurrMotion.strike())
        }
    }
    Canvas(modifier.size(size)) {
        val p = progress.value
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // rain converging
        val streaks = 7
        for (i in 0 until streaks) {
            val t = i / (streaks - 1f)
            val ang = -0.6f + t * 1.2f
            val len = this.size.minDimension * (0.55f - 0.35f * p)
            val ox = cx + kotlin.math.cos(ang) * len
            val oy = cy + kotlin.math.sin(ang) * len
            drawLine(
                c.accent.copy(alpha = 0.35f * (1f - p * 0.7f)),
                Offset(ox, oy), Offset(cx, cy),
                strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square,
            )
        }
        // cat head silhouette (simplified)
        if (p > 0.35f) {
            val a = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val path = Path().apply {
                val r = this@Canvas.size.minDimension * 0.32f
                moveTo(cx, cy - r * 0.95f)
                lineTo(cx - r * 0.55f, cy - r * 0.35f)
                quadraticTo(cx - r, cy + r * 0.1f, cx - r * 0.7f, cy + r * 0.7f)
                quadraticTo(cx, cy + r * 1.05f, cx + r * 0.7f, cy + r * 0.7f)
                quadraticTo(cx + r, cy + r * 0.1f, cx + r * 0.55f, cy - r * 0.35f)
                close()
            }
            drawPath(path, c.accent.copy(alpha = a), style = Stroke(width = 2.5.dp.toPx()))
        }
        if (band.value > 0.01f) {
            drawLine(
                c.accent.copy(alpha = band.value),
                Offset(0f, this.size.height * 0.12f),
                Offset(this.size.width, this.size.height * 0.12f),
                strokeWidth = 4.dp.toPx(),
            )
        }
    }
}
