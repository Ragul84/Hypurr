package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.delay

/**
 * Creating a bot: diagonal rain streaks converge into the Hypurr cat glyph
 * (docs/brand/hypurr-glyph.svg paths), signal band confirms, optional name types in.
 */
@Composable
fun CatAssemble(
    playing: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    name: String = "",
) {
    val c = Hypurr.colors
    val reduce = reduceMotion()
    val progress = remember { Animatable(0f) }
    val band = remember { Animatable(0f) }
    val typed = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (!playing) {
            progress.snapTo(0f); band.snapTo(0f); typed.snapTo(0f); return@LaunchedEffect
        }
        if (reduce) {
            progress.snapTo(1f); band.snapTo(1f); typed.snapTo(1f); return@LaunchedEffect
        }
        progress.animateTo(1f, HypurrMotion.assemble())
        band.snapTo(1f)
        delay(HypurrMotion.STRIKE_MS.toLong())
        band.animateTo(0f, HypurrMotion.strike())
        if (name.isNotEmpty()) typed.animateTo(1f, HypurrMotion.settle())
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size)) {
            val p = progress.value
            // Rain streaks (glyph viewBox space), converging via scale
            val rainScale = 1.15f - 0.6f * p.coerceIn(0f, 1f)
            val rainAlpha = (0.22f * (1f - ((p - 0.25f) / 0.4f).coerceIn(0f, 1f))).coerceAtLeast(0f)
            // Draw rain in viewBox-mapped coordinates
            fun vx(x: Float) = x / 1024f * this.size.width
            fun vy(y: Float) = y / 1024f * this.size.height
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            for (i in 0 until 14) {
                val x = 80f + i * 64f
                val x0 = vx(x)
                val y0 = vy(40f)
                val x1 = vx(x + 120f)
                val y1 = vy(980f)
                // pull endpoints toward center as p rises
                val ax = x0 + (cx - x0) * (1f - rainScale.coerceIn(0.4f, 1.2f) / 1.15f)
                val ay = y0 + (cy - y0) * (1f - rainScale.coerceIn(0.4f, 1.2f) / 1.15f)
                val bx = x1 + (cx - x1) * (1f - rainScale.coerceIn(0.4f, 1.2f) / 1.15f)
                val by = y1 + (cy - y1) * (1f - rainScale.coerceIn(0.4f, 1.2f) / 1.15f)
                drawLine(
                    c.accent.copy(alpha = rainAlpha * (0.7f + (i % 3) * 0.1f)),
                    Offset(ax, ay), Offset(bx, by),
                    strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square,
                )
            }
            // Cat body path from glyph (viewBox 1024)
            val bodyAlpha = ((p - 0.28f) / 0.35f).coerceIn(0f, 1f)
            if (bodyAlpha > 0.01f) {
                val path = Path().apply {
                    // M262 640 C262 470 296 300 330 214 L452 352 C492 344 532 344 572 352 L694 214 C728 300 762 470 762 640 C762 792 650 864 512 864 C374 864 262 792 262 640 Z
                    moveTo(vx(262f), vy(640f))
                    cubicTo(vx(262f), vy(470f), vx(296f), vy(300f), vx(330f), vy(214f))
                    lineTo(vx(452f), vy(352f))
                    cubicTo(vx(492f), vy(344f), vx(532f), vy(344f), vx(572f), vy(352f))
                    lineTo(vx(694f), vy(214f))
                    cubicTo(vx(728f), vy(300f), vx(762f), vy(470f), vx(762f), vy(640f))
                    cubicTo(vx(762f), vy(792f), vx(650f), vy(864f), vx(512f), vy(864f))
                    cubicTo(vx(374f), vy(864f), vx(262f), vy(792f), vx(262f), vy(640f))
                    close()
                }
                drawPath(path, c.accent.copy(alpha = bodyAlpha))
            }
            val faceAlpha = ((p - 0.48f) / 0.22f).coerceIn(0f, 1f)
            if (faceAlpha > 0.01f) {
                val ink = c.onAccent.copy(alpha = faceAlpha)
                // eyes
                val eyeL = Path().apply {
                    moveTo(vx(372f), vy(604f)); quadraticTo(vx(414f), vy(556f), vx(456f), vy(604f))
                }
                val eyeR = Path().apply {
                    moveTo(vx(568f), vy(604f)); quadraticTo(vx(610f), vy(556f), vx(652f), vy(604f))
                }
                drawPath(eyeL, ink, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round))
                drawPath(eyeR, ink, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round))
                // nose
                val nose = Path().apply {
                    moveTo(vx(490f), vy(660f)); lineTo(vx(534f), vy(660f)); lineTo(vx(512f), vy(684f)); close()
                }
                drawPath(nose, ink)
                // smile
                val smile = Path().apply {
                    moveTo(vx(446f), vy(704f))
                    quadraticTo(vx(479f), vy(744f), vx(512f), vy(704f))
                    quadraticTo(vx(545f), vy(744f), vx(578f), vy(704f))
                }
                drawPath(smile, ink, style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round))
            }
            if (band.value > 0.01f) {
                drawLine(
                    c.accent.copy(alpha = band.value),
                    Offset(0f, this.size.height * 0.06f),
                    Offset(this.size.width, this.size.height * 0.06f),
                    strokeWidth = 4.dp.toPx(),
                )
            }
        }
        if (name.isNotEmpty() && typed.value > 0.01f) {
            val n = (name.length * typed.value).toInt().coerceIn(0, name.length)
            Text(
                name.take(n),
                color = c.accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
    }
}
