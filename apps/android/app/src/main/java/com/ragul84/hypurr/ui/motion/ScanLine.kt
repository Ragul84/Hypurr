package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.delay

/** Thinking: cyan scan band with flat opacity falloff trail + mono phase + elapsed. */
@Composable
fun ThinkingScan(
    phases: List<String> = listOf("Reading files", "Planning", "Editing"),
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val c = Hypurr.colors
    val reduce = reduceMotion() || !animate
    val t = rememberInfiniteTransition(label = "scan")
    val x by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(HypurrMotion.LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    var phaseIdx by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phases, reduce) {
        if (reduce) return@LaunchedEffect
        val start = System.nanoTime()
        while (true) {
            delay(100)
            elapsedMs = (System.nanoTime() - start) / 1_000_000L
            phaseIdx = ((elapsedMs / 1000L) % phases.size.coerceAtLeast(1)).toInt()
        }
    }
    val phase = phases.getOrElse(phaseIdx) { phases.firstOrNull().orEmpty() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val y = size.height / 2f
            drawLine(c.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square)
            val bandW = size.width * 0.28f
            val start = if (reduce) size.width * 0.35f else (size.width + bandW) * x - bandW
            // Flat colour-step falloff (not a glow blur)
            drawLine(
                Brush.horizontalGradient(
                    0f to c.accent.copy(alpha = 0f),
                    0.18f to c.accent.copy(alpha = 0.35f),
                    0.55f to c.accent.copy(alpha = 0.95f),
                    0.82f to c.accent.copy(alpha = 0.35f),
                    1f to c.accent.copy(alpha = 0f),
                    startX = start, endX = start + bandW,
                ),
                Offset(start, y), Offset(start + bandW, y),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Square,
            )
        }
        if (phase.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { fadeIn(HypurrMotion.snap()) togetherWith fadeOut(HypurrMotion.snap()) },
                    label = "phase",
                ) { text ->
                    Text(text, color = c.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Text(
                    "${"%.1f".format(elapsedMs / 1000f)}s",
                    color = c.tertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun ScanLine(modifier: Modifier = Modifier, animate: Boolean = true) {
    ThinkingScan(phases = listOf(""), modifier = modifier, animate = animate)
}
