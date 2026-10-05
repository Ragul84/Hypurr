package com.ragul84.hypurr.ui.motion

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Sunfield Panel motion tokens — see docs/design/hypurr-motion.md.
 * Forest/sunflower light, no glow, no bounce-dots. Honour reduced motion everywhere.
 */
object HypurrMotion {
    const val STRIKE_MS = 120
    const val SNAP_MS = 180
    const val SETTLE_MS = 280
    const val DRAW_MS = 420
    const val ASSEMBLE_MS = 720
    const val LOOP_MS = 1600
    const val REDUCED_MS = 150

    val linear: Easing = LinearEasing
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 1400f)
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> signalStrike(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 900f)

    fun <T> strike() = tween<T>(STRIKE_MS, easing = linear)
    fun <T> snap() = tween<T>(SNAP_MS, easing = emphasized)
    fun <T> settle() = tween<T>(SETTLE_MS, easing = emphasized)
    fun <T> draw() = tween<T>(DRAW_MS, easing = linear)
    fun <T> assemble() = tween<T>(ASSEMBLE_MS, easing = emphasized)
    fun <T> loop() = tween<T>(LOOP_MS, easing = linear)
    fun <T> reduced() = tween<T>(REDUCED_MS, easing = emphasized)
}

/** System Reduce Motion when Animator duration scale is 0. */
@Composable
fun reduceMotion(): Boolean {
    val ctx = LocalContext.current
    return try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Throwable) {
        false
    }
}
