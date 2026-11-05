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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/*
 * Bundled faces (tools/work/ui-fonts.md, set A): Inter for Latin and digits,
 * Noto Sans SC (思源黑体) for Chinese, JetBrains Mono for readouts. All OFL 1.1,
 * licences in assets/fonts/licenses, listed in NOTICE. The same on every phone,
 * whatever the maker's system font.
 *
 * Compose picks a font by weight only, never by script, so each weight is one
 * platform Typeface built with CustomFallbackBuilder (API 29, our minSdk): the
 * Latin face first, Noto Sans SC for any glyph it lacks, then the system font.
 * The files are stored uncompressed (build.gradle.kts noCompress), so a Typeface
 * maps the APK in place; each weight is built once, on first use or by [UiFonts.warm].
 */
internal object UiFonts {
    private val WEIGHTS = intArrayOf(400, 500, 600, 700)
    private val cache = HashMap<String, android.graphics.Typeface>()

    private fun face(c: android.content.Context, path: String, weight: Int, axis: Int = weight) =
        android.graphics.fonts.Font.Builder(c.assets, path)
            .setWeight(weight)
            .setFontVariationSettings("'wght' $axis")
            .build()

    fun typeface(c: android.content.Context, latin: String, weight: Int): android.graphics.Typeface =
        synchronized(cache) {
            cache.getOrPut("$latin@$weight") {
                runCatching {
                    val first = android.graphics.fonts.FontFamily.Builder(face(c, latin, weight)).build()
                    val cjk = android.graphics.fonts.FontFamily.Builder(face(c, CJK, weight)).build()
                    android.graphics.Typeface.CustomFallbackBuilder(first)
                        .addCustomFallback(cjk)
                        .setSystemFallback("sans-serif")
                        .setStyle(android.graphics.fonts.FontStyle(weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
                        .build()
                }.getOrElse { android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, weight, false) }
            }
        }

    /** Builds every weight ahead of the first frame that needs it; safe on any thread. */
    fun warm(c: android.content.Context) {
        val app = c.applicationContext
        for (w in WEIGHTS) { typeface(app, SANS, w); typeface(app, MONO, w) }
    }

    const val SANS = "fonts/Inter-VF.ttf"
    const val MONO = "fonts/JetBrainsMono-VF.ttf"
    const val CJK = "fonts/NotoSansSC-VF.ttf"

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private class Bundled(val latin: String, override val weight: FontWeight) :
        androidx.compose.ui.text.font.AndroidFont(
            androidx.compose.ui.text.font.FontLoadingStrategy.Blocking, Loader,
            androidx.compose.ui.text.font.FontVariation.Settings(),
        ) {
        override val style: androidx.compose.ui.text.font.FontStyle = androidx.compose.ui.text.font.FontStyle.Normal
    }

    private object Loader : androidx.compose.ui.text.font.AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: android.content.Context, font: androidx.compose.ui.text.font.AndroidFont) =
            (font as Bundled).let { typeface(context, it.latin, it.weight.weight) }
        override suspend fun awaitLoad(context: android.content.Context, font: androidx.compose.ui.text.font.AndroidFont) =
            loadBlocking(context, font)
    }

    fun family(latin: String) = androidx.compose.ui.text.font.FontFamily(WEIGHTS.map { Bundled(latin, FontWeight(it)) })
}

/** Headings, labels and body: Inter with Noto Sans SC behind it. */
val UiSans = UiFonts.family(UiFonts.SANS)
/** Readouts, film codes, frame numbers: JetBrains Mono with Noto Sans SC behind it. */
val UiMono = UiFonts.family(UiFonts.MONO)

private fun TextStyle.zh() = copy(letterSpacing = 0.sp, localeList = Zh, fontFamily = UiSans)

private val Type = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.zh(),
        displayMedium = t.displayMedium.zh(),
        displaySmall = t.displaySmall.zh(),
        headlineLarge = t.headlineLarge.zh(),
        headlineMedium = t.headlineMedium.zh(),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold).zh(),
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

/** Any readout that changes while a finger moves: monospaced, so its width never jitters. */
val Readout = TextStyle(fontFamily = UiMono, fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium,
    fontSize = 13.sp, localeList = Zh)

@Composable
fun SpektraTheme(content: @Composable () -> Unit) {
    // Build the four weights on a worker while the first frame lays out (ui-fonts.md 5).
    val c = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember(c) { Thread({ UiFonts.warm(c) }, "ui-fonts").apply { isDaemon = true; start() } }
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

/**
 * What sits behind the photo (UI2.md 1.1; ui2_snapseed.md 5, a user asked for a
 * grey to judge colour against). Settings key [SURROUND_KEY] in "settings":
 * "dark" (default), "grey18" (18 % reflectance, L* 50, sRGB #777777, the
 * photographer's mid grey), "black", "white". Neutral every one of them.
 */
internal const val SURROUND_KEY = "surround"
internal val SURROUNDS = listOf(
    "dark" to Palette.Surround, "grey18" to Color(0xFF777777),
    "black" to Color(0xFF000000), "white" to Color(0xFFFFFFFF),
)

/** The chosen surround, following the setting while it is changed elsewhere. */
@Composable
internal fun rememberSurround(): Color {
    val c = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember(c) { c.getSharedPreferences("settings", 0) }
    fun read() = SURROUNDS.firstOrNull { it.first == prefs.getString(SURROUND_KEY, "dark") }?.second ?: Palette.Surround
    var colour by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(read()) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == SURROUND_KEY) colour = read() }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return colour
}
