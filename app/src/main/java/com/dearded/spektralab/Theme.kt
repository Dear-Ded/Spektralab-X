package com.dearded.spektralab

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
 * A darkroom palette: near-black neutral surfaces, so nothing around the
 * print biases how its colour is judged, and one warm safelight-amber accent.
 * The accent never touches the photo's edge.
 */
object Palette {
    val Surround = Color(0xFF121212)       // behind the photo: neutral grey, not black
    val Background = Color(0xFF0B0B0C)
    val Surface = Color(0xFF141416)
    val Panel = Color(0xFF1B1B1E)
    val PanelHigh = Color(0xFF232327)
    val PanelHighest = Color(0xFF2C2C31)
    val OnSurface = Color(0xFFECE9E3)
    val OnSurfaceSoft = Color(0xFF9E9A93)
    val Outline = Color(0xFF3A3A40)
    val OutlineSoft = Color(0xFF26262A)
    val Amber = Color(0xFFE8A24A)
    val OnAmber = Color(0xFF1A1206)
    val AmberDim = Color(0xFF5A4022)
    val Warning = Color(0xFFE5584A)
}

private val Scheme = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Palette.OnAmber,
    primaryContainer = Palette.AmberDim,
    onPrimaryContainer = Palette.OnSurface,
    secondary = Palette.OnSurfaceSoft,
    onSecondary = Palette.Background,
    secondaryContainer = Palette.PanelHighest,
    onSecondaryContainer = Palette.OnSurface,
    tertiary = Palette.Amber,
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

private val Type = Typography().let { t ->
    t.copy(
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum"),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.2.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
    )
}

/** Tabular figures for any readout that changes while a finger moves. */
val Readout = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium, fontSize = 13.sp)

@Composable
fun SpektraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}

/**
 * Material motion tokens (spatial springs move things, effect springs fade
 * them and never overshoot). Material3 1.4 has no public MotionScheme yet.
 */
object Motion {
    fun <T> fastSpatial() = spring<T>(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> defaultSpatial() = spring<T>(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> slowSpatial() = spring<T>(dampingRatio = 0.9f, stiffness = 300f)
    fun <T> fastEffects() = spring<T>(dampingRatio = 1f, stiffness = 3800f)
    fun <T> defaultEffects() = spring<T>(dampingRatio = 1f, stiffness = 1600f)
}

/** One haptic tick: a firmer segment tick on API 34+, a clock tick before. */
fun View.tick(strong: Boolean = false) {
    val c = when {
        Build.VERSION.SDK_INT >= 34 -> if (strong) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        else -> HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(c)
}
