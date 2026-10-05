package com.ragul84.hypurr.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay

/** App launch: glyph neon flicker-on (2 gentle flashes) then steady. */
@Composable
fun LaunchFlicker(play: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduce = reduceMotion()
    val a = remember { Animatable(0f) }
    LaunchedEffect(play) {
        if (!play) { a.snapTo(0f); return@LaunchedEffect }
        if (reduce) { a.snapTo(1f); return@LaunchedEffect }
        a.snapTo(1f); delay(HypurrMotion.STRIKE_MS.toLong())
        a.snapTo(0.25f); delay(HypurrMotion.STRIKE_MS.toLong())
        a.snapTo(1f); delay(HypurrMotion.STRIKE_MS.toLong())
        a.snapTo(0.35f); delay(HypurrMotion.STRIKE_MS.toLong())
        a.snapTo(1f)
    }
    Box(modifier.alpha(a.value), content = content)
}
