package com.ragul84.hypurr.ui.theme

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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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
 * Hypurr Signal tokens (docs/design/hypurr-design-system.md).
 *
 * Monochrome: ink on light grey with big white cards (light), white on true black with #111 cards (dark).
 * Red is the only colour and always means "needs you": risk labels, the stop button, the needs-you
 * badge, today's chart bar. Inter carries all text; Doto (dot matrix) is for big figures only.
 * Every text pair is WCAG AA on the surface it sits on (see the contrast table in the doc).
 */
@Immutable
data class HypurrColors(
    /** Screen ground. */
    val bg: Color,
    /** Cards, tab bar, composer. */
    val surface: Color,
    /** Tiles, inline code, segmented track, quiet fills. */
    val surface2: Color,
    val bubbleAgent: Color,
    val bubbleUser: Color,
    val onBubbleUser: Color,
    /** Hairline card border. */
    val border: Color,
    /** Outline buttons; the 1.5dp emphasis border uses [text]. */
    val borderStrong: Color,
    val text: Color,
    val secondary: Color,
    /** Muted text: still ≥ 4.5:1 on bg, surface and surface2. */
    val tertiary: Color,
    /** Primary pill fill (ink in light, white in dark). */
    val accent: Color,
    val onAccent: Color,
    /** Signal red fill: badge, stop, today's bar, high-risk tag. White text on it is AA. */
    val needs: Color,
    val onNeeds: Color,
    /** Signal red for text and icons on bg/surface (AA). */
    val needsText: Color,
    /** Off dots in dot-matrix figures and bars, progress track. */
    val dotOff: Color,
    /** Legacy names, all monochrome or red. */
    val warning: Color,
    val danger: Color,
    val success: Color,
    val dark: Boolean,
)

val LightTokens = HypurrColors(
    bg = Color(0xFFEDEDEA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF2F2EF),
    bubbleAgent = Color(0xFFFFFFFF),
    bubbleUser = Color(0xFF0E0E0E),
    onBubbleUser = Color(0xFFFFFFFF),
    border = Color(0xFFE0E0DC),
    borderStrong = Color(0xFFCFCFCA),
    text = Color(0xFF0E0E0E),
    secondary = Color(0xFF34343A),
    tertiary = Color(0xFF5C5C63),
    accent = Color(0xFF0E0E0E),
    onAccent = Color(0xFFFFFFFF),
    needs = Color(0xFFD21F26),
    onNeeds = Color(0xFFFFFFFF),
    needsText = Color(0xFFC41A20),
    dotOff = Color(0xFFD6D6D1),
    warning = Color(0xFFC41A20),
    danger = Color(0xFFC41A20),
    success = Color(0xFF0E0E0E),
    dark = false,
)

val DarkTokens = HypurrColors(
    bg = Color(0xFF000000),
    surface = Color(0xFF111111),
    surface2 = Color(0xFF1C1C1E),
    bubbleAgent = Color(0xFF111111),
    bubbleUser = Color(0xFFF5F5F2),
    onBubbleUser = Color(0xFF0E0E0E),
    border = Color(0xFF242426),
    borderStrong = Color(0xFF3A3A3D),
    text = Color(0xFFF5F5F2),
    secondary = Color(0xFFC9C9CE),
    tertiary = Color(0xFF9A9AA1),
    accent = Color(0xFFF5F5F2),
    onAccent = Color(0xFF0E0E0E),
    needs = Color(0xFFD21F26),
    onNeeds = Color(0xFFFFFFFF),
    needsText = Color(0xFFFF4D4F),
    dotOff = Color(0xFF2E2E31),
    warning = Color(0xFFFF4D4F),
    danger = Color(0xFFFF4D4F),
    success = Color(0xFFF5F5F2),
    dark = true,
)

/** M3 Expressive springs, tuned crisp: Signal motion is precise, not bouncy. */
object Motion {
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 1400f)
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 420f)
    fun <T> spatialSlow(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 220f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
    val offset: SpringSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 420f)
    const val PRESS_SCALE = 0.97f
}

val LocalHypurr = staticCompositionLocalOf { LightTokens }

object Hypurr {
    val colors: HypurrColors @Composable get() = LocalHypurr.current
}

/** Inter (OFL): every word of UI text. */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Doto (OFL), static 800 / ROND 100 instance: big figures and the wordmark only. */
val DotoFamily = FontFamily(Font(R.font.doto, FontWeight.SemiBold))

/** Mono for paths, branches and commands. */
val MonoFamily: FontFamily = FontFamily.Monospace

object SignalType {
    /** ₹1,240 on Spend. */
    val hero = TextStyle(fontFamily = DotoFamily, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-1).sp)
    /** Summary counters (Needs you / Running / Done today). */
    val counter = TextStyle(fontFamily = DotoFamily, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 36.sp)
    /** ₹746 in a widget, per-bot totals. */
    val figure = TextStyle(fontFamily = DotoFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 28.sp)
    val figureSmall = TextStyle(fontFamily = DotoFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 22.sp)
    val wordmark = TextStyle(fontFamily = DotoFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 24.sp, letterSpacing = 0.4.sp)
    /** ARJUN'S LAPTOP IS ONLINE / NEEDS YOU / CREDITS LEFT. */
    val eyebrow = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp)
    val mono = TextStyle(fontFamily = MonoFamily, fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = (-0.1).sp)
}

private fun HypurrColors.scheme(): ColorScheme = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = surface2, onPrimaryContainer = text,
        secondary = secondary, tertiary = accent, background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = secondary,
        surfaceContainer = surface, surfaceContainerHigh = surface2, surfaceContainerLow = surface,
        outline = borderStrong, outlineVariant = border, error = needsText, onError = onNeeds,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = surface2, onPrimaryContainer = text,
        secondary = secondary, tertiary = accent, background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = secondary,
        surfaceContainer = surface, surfaceContainerHigh = surface2, surfaceContainerLow = surface,
        outline = borderStrong, outlineVariant = border, error = needsText, onError = onNeeds,
    )
}

private fun inter(weight: FontWeight, size: Float, line: Float, tracking: Float = 0f) =
    TextStyle(fontFamily = InterFamily, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

/** Inter everywhere; tight negative tracking on headings like the mockup (h1 600/29, -0.035em). */
private val HypurrTypography = Typography(
    displayLarge = inter(FontWeight.SemiBold, 48f, 52f, -1.6f),
    displayMedium = inter(FontWeight.SemiBold, 40f, 44f, -1.3f),
    displaySmall = inter(FontWeight.SemiBold, 34f, 38f, -1.1f),
    headlineLarge = inter(FontWeight.SemiBold, 29f, 34f, -1.0f),
    headlineMedium = inter(FontWeight.SemiBold, 25f, 30f, -0.8f),
    headlineSmall = inter(FontWeight.SemiBold, 21f, 26f, -0.5f),
    titleLarge = inter(FontWeight.SemiBold, 18.5f, 24f, -0.35f),
    titleMedium = inter(FontWeight.SemiBold, 16f, 21f, -0.16f),
    titleSmall = inter(FontWeight.SemiBold, 14.5f, 19f, -0.1f),
    bodyLarge = inter(FontWeight.Normal, 15.5f, 23.5f, -0.1f),
    bodyMedium = inter(FontWeight.Normal, 14f, 20f, -0.05f),
    bodySmall = inter(FontWeight.Normal, 12.5f, 17f),
    labelLarge = inter(FontWeight.SemiBold, 14f, 18f),
    labelMedium = inter(FontWeight.Medium, 12.5f, 16f),
    labelSmall = inter(FontWeight.Medium, 11.5f, 14f, 0.1f),
)

/** Big rounded widget cards (24dp), pill controls. */
private val HypurrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Signal never takes wallpaper colour: monochrome is the brand, red is reserved for "needs you".
 * [dynamic] stays in the signature so settings and callers compile; it is ignored on purpose.
 */
@Composable
fun HypurrTheme(mode: ThemeMode = ThemeMode.System, @Suppress("UNUSED_PARAMETER") dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val tokens = if (dark) DarkTokens else LightTokens
    CompositionLocalProvider(LocalHypurr provides tokens) {
        MaterialTheme(colorScheme = tokens.scheme(), typography = HypurrTypography, shapes = HypurrShapes, content = content)
    }
}
