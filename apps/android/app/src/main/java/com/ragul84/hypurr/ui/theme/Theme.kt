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
 * Hypurr design tokens (docs/design/hypurr-design-system.md): the same seed palette, colour flow and
 * spring motion as the Swift `HypurrTheme`/`ColorFlow`/`Motion`, GTK and web clients.
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
    /** "Needs you" */
    val warning: Color,
    val danger: Color,
    val success: Color,
    val glass: Color,
    val dark: Boolean,
)

val LightTokens = HypurrColors(
    bg = Color(0xFFFCFAFF), surface = Color(0xFFF4EFFC), bubbleAgent = Color(0xFFEFE9FA), bubbleUser = Color(0xFFE4D9FB),
    border = Color(0xFFE6DFF3), text = Color(0xFF1E1433), secondary = Color(0xFF5B4F7A), tertiary = Color(0xFF8C82A8),
    accent = Color(0xFF6D3FD9), onAccent = Color(0xFFFFFFFF), warning = Color(0xFFC0267A), danger = Color(0xFFC2304D),
    success = Color(0xFF1F9D6B), glass = Color(0xB8FFFFFF), dark = false,
)

val DarkTokens = HypurrColors(
    bg = Color(0xFF0E0A1C), surface = Color(0xFF161029), bubbleAgent = Color(0xFF1E1736), bubbleUser = Color(0xFF3A2A6B),
    border = Color(0xFF2A2145), text = Color(0xFFF3EEFF), secondary = Color(0xFFA89CC8), tertiary = Color(0xFF75699A),
    accent = Color(0xFFA78BFA), onAccent = Color(0xFF150A33), warning = Color(0xFFF472B6), danger = Color(0xFFFF8FA3),
    success = Color(0xFF5EE0A8), glass = Color(0x9E1E1736), dark = true,
)

/** The colour flow: violet → magenta → cyan. Ink on the flow is [FlowInk]. */
object ColorFlow {
    val violet = Color(0xFFA78BFA)
    val magenta = Color(0xFFF472B6)
    val cyan = Color(0xFF22D3EE)
    val stops = listOf(violet, magenta, cyan)
    val FlowInk = Color(0xFF150A33)

    fun linear(): Brush = Brush.linearGradient(stops)
    fun sweep(): Brush = Brush.sweepGradient(stops + violet)
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
}

val LocalHypurr = staticCompositionLocalOf { LightTokens }

object Hypurr {
    val colors: HypurrColors @Composable get() = LocalHypurr.current
}

private fun HypurrColors.scheme(): ColorScheme = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = bubbleUser, onPrimaryContainer = text,
        secondary = ColorFlow.magenta, tertiary = ColorFlow.cyan, background = bg, onBackground = text,
        surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = secondary,
        surfaceContainer = surface, surfaceContainerHigh = bubbleAgent, surfaceContainerLow = surface,
        outline = tertiary, outlineVariant = border, error = danger,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = bubbleUser, onPrimaryContainer = text,
        secondary = Color(0xFFC0267A), tertiary = Color(0xFF0E9FB5), background = bg, onBackground = text,
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
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    )
}

private val HypurrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
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
