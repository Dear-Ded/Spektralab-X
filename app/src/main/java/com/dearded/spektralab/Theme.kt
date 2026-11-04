package com.dearded.spektralab

import androidx.compose.animation.core.VisibilityThreshold
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * UI2.md 1.1: no hue anywhere in the chrome. Layers are the ground plus white
 * at 6 / 10 / 14 % (written out opaque here), so a panel never tints the
 * photo's surround. The one "accent" is near-white: a selected item is an
 * inverted solid pill (light fill, dark text), not a colour.
 */
object Palette {
    val Surround = Color(0xFF1E1E1E)       // behind the photo only: neutral mid-dark grey
    val Background = Color(0xFF0E0E10)     // Ground
    val Surface = Color(0xFF141416)        // top bar, toolbar
    val Panel = Color(0xFF1D1D1F)          // Layer1: white 6 % on Ground
    val PanelHigh = Color(0xFF262628)      // Layer2: white 10 %
    val PanelHighest = Color(0xFF303032)   // Layer3: white 14 %
    val OnSurface = Color(0xFFEBEBEB)      // text, white 92 %
    val OnSurfaceSoft = Color(0xFFA8A8A8)  // secondary text, white 64 %
    val Outline = Color(0xFF3A3A3C)
    /** A slider's unfilled track: 3:1 against the panel. */
    val Track = Color(0xFF6A6A6C)
    val OutlineSoft = Color(0xFF2A2A2C)    // hairline, white 8 %
    /** Selected and primary: the inverted pill's fill, with [OnAccent] text. */
    val Accent = Color(0xFFF2F2F2)
    val OnAccent = Color(0xFF111113)
    /** A selected item's quiet container (chips that are on but not the focus). */
    val AccentDim = Color(0xFF3C3C3E)
    /** Only the confirm text of 重置 / 删除. */
    val Warning = Color(0xFFFF6B5E)
}

private val Scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Palette.OnSurface,
    secondary = Palette.OnSurfaceSoft,
    onSecondary = Palette.Background,
    secondaryContainer = Palette.PanelHighest,
    onSecondaryContainer = Palette.OnSurface,
    tertiary = Palette.Accent,
    background = Palette.Background,
    onBackground = Palette.OnSurface,
    surface = Palette.Surface,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.PanelHigh,
    onSurfaceVariant = Palette.OnSurfaceSoft,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Palette.PanelHigh,
    surfaceContainerHighest = Palette.PanelHighest,
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineSoft,
    error = Palette.Warning,
)

// UI.md 1: no letter spacing on Chinese, nothing under 12 sp, and zh-CN so
// shared Han characters never fall back to Japanese glyph shapes.
private val Zh = androidx.compose.ui.text.intl.LocaleList("zh-CN")

private fun TextStyle.zh() = copy(letterSpacing = 0.sp, localeList = Zh)

private val Type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.zh(),
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold).zh(),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold).zh(),
        titleSmall = t.titleSmall.zh(),
        bodyLarge = t.bodyLarge.zh(),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp).zh(),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp).zh(),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium).zh(),
        labelMedium = t.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum").zh(),
        labelSmall = t.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp).zh(),
    )
}

/** Tabular figures for any readout that changes while a finger moves. */
val Readout = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium, fontSize = 13.sp)

@Composable
fun SpektraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}

/**
 * M3 Expressive motion tokens (UI2.md 3.2), copied by hand from material3's
 * ExpressiveMotionTokens since 1.4.0 keeps MotionScheme.expressive() internal.
 * Spatial springs move things with a touch of overshoot so a release feels
 * alive; effect springs fade colour and alpha and never overshoot.
 */
object Motion {
    fun <T> fastSpatial() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> defaultSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> slowSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> fastEffects() = spring<T>(dampingRatio = 1f, stiffness = 3800f)
    fun <T> defaultEffects() = spring<T>(dampingRatio = 1f, stiffness = 1600f)
    /** defaultSpatial for slides, settling at one pixel rather than ringing on. */
    fun defaultSpatialOffset() = spring(
        dampingRatio = 0.8f, stiffness = 380f,
        visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
    )
}

/** One haptic tick: a firmer segment tick on API 34+, a clock tick before. */
fun View.tick(strong: Boolean = false) {
    val c = when {
        Build.VERSION.SDK_INT >= 34 -> if (strong) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        else -> HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(c)
}
