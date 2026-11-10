package com.dearded.spektralab

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace

/**
 * The editor's screen space. Xiaomi's flagships have P3 panels, and a default
 * window converts everything to sRGB, so the film and print dyes past sRGB
 * (slide films and 2383 most of all) were never visible in the app. With a
 * wide-gamut window the print is shown in Display P3, as the desktop shows it
 * on a P3 screen. Android grants the mode only on a colour-managed display.
 */
object DisplayColor {
    /** "Display P3" or "sRGB", the engine's names. */
    @Volatile var space: String = "sRGB"
        private set

    fun attach(activity: Activity) {
        val wide = runCatching { activity.resources.configuration.isScreenWideColorGamut }.getOrDefault(false)
        if (wide) activity.window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        space = if (wide) "Display P3" else "sRGB"
    }

    private val p3 by lazy { ColorSpace.get(ColorSpace.Named.DISPLAY_P3) }

    /** Tags a bitmap the display transform packed, so Skia reads its codes as P3. */
    fun tag(bitmap: Bitmap) {
        if (space == "Display P3" && bitmap.colorSpace != p3) runCatching { bitmap.setColorSpace(p3) }
    }

    /** Native packed file-target codes; setColorSpace labels, rather than converts, them. */
    fun tagOutput(bitmap: Bitmap, target: String) {
        val colorSpace = when (target) {
            "sRGB" -> ColorSpace.get(ColorSpace.Named.SRGB)
            "Display P3" -> p3
            else -> throw IllegalArgumentException("Unsupported output color space: $target")
        }
        bitmap.setColorSpace(colorSpace)
        check(bitmap.colorSpace == colorSpace) { "Output color space was not applied" }
    }
}
