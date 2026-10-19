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
 * Signal motion tokens (docs/design/hypurr-motion.md). Subtle and precise: short emphasized tweens,
 * critically damped springs, dot-matrix loops. No glow, no bounce, no confetti. Reduced motion
 * (animator scale 0) collapses every moment to a 150ms fade or its end state.
 */
object HypurrMotion {
    const val PRESS_MS = 120
    const val SNAP_MS = 180
    const val SETTLE_MS = 240
    const val DRAW_MS = 420
    const val ASSEMBLE_MS = 760
    const val PULSE_MS = 1400
    const val SCAN_MS = 1100
    const val REDUCED_MS = 150

    val linear: Easing = LinearEasing
    /** M3 emphasized decelerate. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 1400f)
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 420f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> press() = tween<T>(PRESS_MS, easing = emphasized)
    fun <T> snap() = tween<T>(SNAP_MS, easing = emphasized)
    fun <T> settle() = tween<T>(SETTLE_MS, easing = emphasized)
    fun <T> draw() = tween<T>(DRAW_MS, easing = linear)
    fun <T> assemble() = tween<T>(ASSEMBLE_MS, easing = linear)
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
