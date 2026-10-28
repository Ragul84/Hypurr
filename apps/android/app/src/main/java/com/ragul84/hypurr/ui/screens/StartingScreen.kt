package com.ragul84.hypurr.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.theme.Hypurr

/**
 * Shown the instant a task is submitted, while the computer sets up the agent: the Hypurr cat
 * fills in dot by dot, so the tap never feels ignored.
 */
@Composable
fun StartingScreen(goal: String, title: String = "Starting your agent…", animate: Boolean = true) {
    val c = Hypurr.colors
    val t = rememberInfiniteTransition(label = "starting")
    val reveal by t.animateFloat(0.15f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "reveal")
    val pulse by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        DotCat(c.text, 96.dp, Modifier.scale(if (animate) pulse else 1f), off = c.dotOff, reveal = if (animate) reveal else 1f)
        Spacer(Modifier.height(24.dp))
        Text(title, color = c.text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Setting up a safe copy of your project. This takes a few seconds.", color = c.secondary,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (goal.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("“${goal.trim()}”", color = c.tertiary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
