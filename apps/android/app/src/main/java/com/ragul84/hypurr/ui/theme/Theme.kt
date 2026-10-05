package com.ragul84.hypurr.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.data.ThemeMode

/**
 * Hypurr wet-asphalt neon tokens (docs/design/hypurr-design-system.md): deep asphalt ground,
 * flat electric teal signal, sharp 0–4px plates. Shared with Swift / GTK / web.
 */
@Immutable
data class HypurrColors(
    val bg: Color,
    val surface: Color,
    val bubbleAgent: Color,
    val bubbleUser: Color,
    val border: Color,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** "Needs you" label only (amber attention) */
    val warning: Color,
    val danger: Color,
    val success: Color,
    val glass: Color,
    val dark: Boolean,
)

val LightTokens = HypurrColors(
    bg = Color(0xFFF4F6F5), surface = Color(0xFFFFFFFF), bubbleAgent = Color(0xFFE8EEEC), bubbleUser = Color(0xFFD5E8E5),
    border = Color(0xFFD5DEDB), text = Color(0xFF0A1210), secondary = Color(0xFF4A5E5A), tertiary = Color(0xFF8A9995),
    accent = Color(0xFF007A73), onAccent = Color(0xFFF4F6F5), warning = Color(0xFFB86A00), danger = Color(0xFFC23B2E),
    success = Color(0xFF1F7A55), glass = Color(0xB8FFFFFF), dark = false,
)

val DarkTokens = HypurrColors(
    bg = Color(0xFF05070A), surface = Color(0xFF0B1211), bubbleAgent = Color(0xFF0E1614), bubbleUser = Color(0xFF12302C),
    border = Color(0xFF14201E), text = Color(0xFFE8FFFC), secondary = Color(0xFF7FA8A3), tertiary = Color(0xFF3D5552),
    accent = Color(0xFF00D4C8), onAccent = Color(0xFF021412), warning = Color(0xFFFFB020), danger = Color(0xFFFF6B5A),
    success = Color(0xFF5EAD8A), glass = Color(0x9E0B1211), dark = true,
)

/**
 * Teal signal language (replaces the old violet → magenta → cyan colour flow).
 * Kept as [ColorFlow] so call sites compile; fills are solid/near-solid teal, never purple.
 */
object ColorFlow {
    val signal = Color(0xFF00D4C8)
    val signalDeep = Color(0xFF007A73)
    val cyan = signal
    /** @deprecated kept for compile; resolves to teal signal */
    val violet = signal
    /** @deprecated kept for compile; resolves to teal signal */
    val magenta = signal
    val stops = listOf(signal, Color(0xFF5EAD8A), signalDeep)
    val FlowInk = Color(0xFF021412)
    val SignalInk = FlowInk

    fun linear(): Brush = Brush.linearGradient(listOf(signal, signalDeep))
    fun sweep(): Brush = Brush.sweepGradient(listOf(signal, signalDeep, signal))
    fun solid(): Brush = Brush.linearGradient(listOf(signal, signal))
}

/** M3 Expressive spring tokens (stiffness / damping ratio), shared with Swift `Motion`. */
object Motion {
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 1400f)
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> spatialSlow(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    val offset: SpringSpec<IntOffset> = spring(dampingRatio = 0.8f, stiffness = 380f)
    const val PRESS_SCALE = 0.96f
    /** Signal-strike collapse (~280ms snappy). */
    fun <T> signalStrike(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 900f)
}

val LocalHypurr = staticCompositionLocalOf { LightTokens }

object Hypurr {
    val colors: HypurrColors @Composable get() = LocalHypurr.current
}

private fun HypurrColors.scheme(): ColorScheme = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = bubbleUser, onPrimaryContainer = text,
        secondary = success, tertiary = accent, background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = secondary,
        surfaceContainer = surface, surfaceContainerHigh = bubbleAgent, surfaceContainerLow = surface,
        outline = tertiary, outlineVariant = border, error = danger,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = bubbleUser, onPrimaryContainer = text,
        secondary = success, tertiary = accent, background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = secondary,
        surfaceContainer = surface, surfaceContainerHigh = bubbleAgent, surfaceContainerLow = surface,
        outline = tertiary, outlineVariant = border, error = danger,
    )
}

/** Android 12+ dynamic colour, opt-in: wallpaper colours replace the accent roles, tokens keep the rest. */
private fun HypurrColors.withDynamic(s: ColorScheme): HypurrColors = copy(
    bg = s.background, surface = s.surfaceContainer, bubbleAgent = s.surfaceContainerHigh, bubbleUser = s.primaryContainer,
    border = s.outlineVariant, text = s.onSurface, secondary = s.onSurfaceVariant, tertiary = s.outline,
    accent = s.primary, onAccent = s.onPrimary,
)

private val HypurrTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    )
}

/** Sharp plates: 0–4dp content radii (nav pills may still use ~22dp at call sites). */
private val HypurrShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp), extraLarge = RoundedCornerShape(4.dp),
)

@Composable
fun HypurrTheme(mode: ThemeMode = ThemeMode.System, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val base = if (dark) DarkTokens else LightTokens
    val tokens = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        base.withDynamic(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
    } else {
        base
    }
    CompositionLocalProvider(LocalHypurr provides tokens) {
        MaterialTheme(colorScheme = tokens.scheme(), typography = HypurrTypography, shapes = HypurrShapes, content = content)
    }
}
