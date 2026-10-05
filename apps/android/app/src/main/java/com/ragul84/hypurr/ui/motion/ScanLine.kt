package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.delay

/** Thinking: thin cyan scan band sweeping a rule + mono phase ticker. */
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
    LaunchedEffect(phases, reduce) {
        if (reduce || phases.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(HypurrMotion.LOOP_MS.toLong())
            phaseIdx = (phaseIdx + 1) % phases.size
        }
    }
    val phase = phases.getOrElse(phaseIdx) { phases.firstOrNull().orEmpty() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val y = size.height / 2f
            drawLine(c.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square)
            val bandW = size.width * 0.22f
            val start = if (reduce) size.width * 0.35f else (size.width + bandW) * (if (reduce) 0.4f else x) - bandW
            drawLine(
                c.accent.copy(alpha = if (reduce) 0.4f else 0.95f),
                Offset(start, y), Offset(start + bandW, y),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Square,
            )
        }
        if (phase.isNotBlank()) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(HypurrMotion.snap()) togetherWith fadeOut(HypurrMotion.snap()) },
                label = "phase",
            ) { text ->
                Text(
                    text,
                    color = c.accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

@Composable
fun ScanLine(modifier: Modifier = Modifier, animate: Boolean = true) {
    ThinkingScan(phases = listOf(""), modifier = modifier, animate = animate)
}
