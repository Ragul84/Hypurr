package com.ragul84.hypurr.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.R
import com.ragul84.hypurr.data.ThemeMode

/**
 * Hypurr Sunfield Panel tokens (docs/design/hypurr-design-system.md):
 * sunflower ground, cream plates, forest primary, Cabinet Grotesk.
 * No teal. No purple. Shared with Swift / GTK / web.
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
    /** Needs-you chip */
    val warning: Color,
    val danger: Color,
    val success: Color,
    val glass: Color,
    /** Chunky press shadow colour */
    val press: Color,
    val dark: Boolean,
)

val LightTokens = HypurrColors(
    bg = Color(0xFFF2B90D),
    surface = Color(0xFFFFF8E8),
    bubbleAgent = Color(0xFFFFF8E8),
    bubbleUser = Color(0xFF17140A),
    border = Color(0x1F17140A),
    text = Color(0xFF17140A),
    secondary = Color(0xFF5C5640),
    tertiary = Color(0xFF8A8168),
    accent = Color(0xFF0E4A38),
    onAccent = Color(0xFFFFF8E8),
    warning = Color(0xFF0E4A38),
    danger = Color(0xFFA1281C),
    success = Color(0xFF0E4A38),
    glass = Color(0xE6FFF8E8),
    press = Color(0x3317140A),
    dark = false,
)

val DarkTokens = HypurrColors(
    bg = Color(0xFF0E4A38),
    surface = Color(0xFF143D30),
    bubbleAgent = Color(0xFF1A4A3A),
    bubbleUser = Color(0xFFF2B90D),
    border = Color(0x1FFFF8E8),
    text = Color(0xFFFFF8E8),
    secondary = Color(0xFFC8E0D4),
    tertiary = Color(0xFF7A9E8E),
    accent = Color(0xFFF2B90D),
    onAccent = Color(0xFF0E4A38),
    warning = Color(0xFFF2B90D),
    danger = Color(0xFFF5A090),
    success = Color(0xFF5DDB9A),
    glass = Color(0xE6143D30),
    press = Color(0x59000000),
    dark = true,
)

/**
 * Sunfield signal language (replaces teal ColourFlow).
 * Kept as [ColorFlow] so call sites compile; fills are forest / sunflower only.
 */
object ColorFlow {
    val signal = Color(0xFF0E4A38)
    val signalDeep = Color(0xFF0A3428)
    val sunflower = Color(0xFFF2B90D)
    val cream = Color(0xFFFFF8E8)
    val cyan = sunflower // legacy name → sunflower
    /** @deprecated kept for compile; resolves to forest */
    val violet = signal
    /** @deprecated kept for compile; resolves to sunflower */
    val magenta = sunflower
    val stops = listOf(sunflower, signal, signalDeep)
    val FlowInk = Color(0xFFFFF8E8)
    val SignalInk = FlowInk

    fun linear(): Brush = Brush.linearGradient(listOf(sunflower, signal))
    fun sweep(): Brush = Brush.sweepGradient(listOf(sunflower, signal, sunflower))
    fun solid(): Brush = Brush.linearGradient(listOf(signal, signal))
}

/** M3 Expressive springs; press is chunkier for Sunfield. */
object Motion {
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 1400f)
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> spatialSlow(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    val offset: SpringSpec<IntOffset> = spring(dampingRatio = 0.8f, stiffness = 380f)
    const val PRESS_SCALE = 0.94f
    /** Press-Allow collapse. */
    fun <T> signalStrike(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = 850f)
}

val LocalHypurr = staticCompositionLocalOf { LightTokens }

object Hypurr {
    val colors: HypurrColors @Composable get() = LocalHypurr.current
}

val CabinetFamily = FontFamily(
    Font(R.font.cabinet_regular, FontWeight.Normal),
    Font(R.font.cabinet_medium, FontWeight.Medium),
    Font(R.font.cabinet_bold, FontWeight.Bold),
    Font(R.font.cabinet_extrabold, FontWeight.ExtraBold),
)

private fun HypurrColors.scheme(): ColorScheme = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = bubbleUser, onPrimaryContainer = onAccent,
        secondary = success, tertiary = accent, background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = secondary,
        surfaceContainer = surface, surfaceContainerHigh = bubbleAgent, surfaceContainerLow = surface,
        outline = tertiary, outlineVariant = border, error = danger,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = bubbleUser, onPrimaryContainer = onAccent,
        secondary = success, tertiary = accent, background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = secondary,
        surfaceContainer = surface, surfaceContainerHigh = bubbleAgent, surfaceContainerLow = surface,
        outline = tertiary, outlineVariant = border, error = danger,
    )
}

private fun HypurrColors.withDynamic(s: ColorScheme): HypurrColors = copy(
    bg = s.background, surface = s.surfaceContainer, bubbleAgent = s.surfaceContainerHigh, bubbleUser = s.primaryContainer,
    border = s.outlineVariant, text = s.onSurface, secondary = s.onSurfaceVariant, tertiary = s.outline,
    accent = s.primary, onAccent = s.onPrimary,
)

private val HypurrTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
        headlineLarge = t.headlineLarge.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
        headlineMedium = t.headlineMedium.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = t.titleLarge.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
        titleMedium = t.titleMedium.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.Bold),
        bodyLarge = TextStyle(fontFamily = CabinetFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = CabinetFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.Bold),
        labelMedium = t.labelMedium.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.Bold),
        labelSmall = t.labelSmall.copy(fontFamily = CabinetFamily, fontWeight = FontWeight.Bold),
    )
}

/** Soft chunky rounds for cream cards. */
private val HypurrShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun HypurrTheme(mode: ThemeMode = ThemeMode.System, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val base = if (dark) DarkTokens else LightTokens
    // Sunfield refuses wallpaper dynamic colour — it would dilute sunflower/forest.
    val tokens = if (false && dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        base.withDynamic(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
    } else {
        base
    }
    CompositionLocalProvider(LocalHypurr provides tokens) {
        MaterialTheme(colorScheme = tokens.scheme(), typography = HypurrTypography, shapes = HypurrShapes, content = content)
    }
}
