package com.dearded.spektralab

import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal enum class ViewfinderStyle(val title: String) {
    OFF("关闭"), LEICA("Leica M"), GR("Ricoh GR"), FUJI("Fujifilm X"), HASSEL("Hasselblad 6×6"), NIKON("Nikon FM2");
    companion object {
        const val KEY = "preview_viewfinder"
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: OFF
    }
}

@Composable
internal fun rememberViewfinder(): ViewfinderStyle {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("settings", 0) }
    var value by remember(prefs) { mutableStateOf(ViewfinderStyle.fromKey(prefs.getString(ViewfinderStyle.KEY, null))) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == ViewfinderStyle.KEY) value = ViewfinderStyle.fromKey(p.getString(key, null))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/** Optical references only: no simulated AF lock, live metering, or invented camera metadata. */
@Composable
internal fun ViewfinderOverlay(
    style: ViewfinderStyle, exif: ExifCopy, filmLabel: String, exposureEv: Float,
    photoWidth: Int, photoHeight: Int, frame: FrameSpec, cameraStyleId: String,
    modifier: Modifier = Modifier,
) {
    val off = rememberReduceMotion()
    val curtain = remember { Animatable(0f) }
    var previous by remember { mutableStateOf(style to cameraStyleId) }
    LaunchedEffect(style, cameraStyleId, off) {
        val changed = previous != (style to cameraStyleId)
        previous = style to cameraStyleId
        if (!changed || off || style == ViewfinderStyle.OFF) { curtain.snapTo(0f); return@LaunchedEffect }
        curtain.snapTo(0f)
        curtain.animateTo(1f, tween(45))
        curtain.animateTo(0f, tween(95))
    }
    val context = LocalContext.current
    val iso = exif.iso()?.toString() ?: "--"
    val shutter = exif.shutter() ?: "--"
    val ev = String.format(Locale.ROOT, "%+.2f", exposureEv)
    val typeface = remember(context) { UiFonts.typeface(context, UiFonts.MONO, 500) }
    Box(modifier.testTag("viewfinder-${style.name.lowercase(Locale.ROOT)}").drawWithCache {
        val r = FrameGeometry.photoRect(size.width, size.height, photoWidth, photoHeight, frame)
        val w = r.width; val h = r.height
        val stroke = 0.75.dp.toPx()
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 11.sp.toPx().coerceAtMost(minOf(w, h) / 16f)
            fontFeatureSettings = "tnum"
            setShadowLayer(1.dp.toPx(), 0f, 0f, android.graphics.Color.BLACK)
        }
        onDrawBehind {
            if (style != ViewfinderStyle.OFF && w > 0f && h > 0f) {
                translate(r.left, r.top) {
                    clipRect(0f, 0f, w, h) {
                        val white = Color(0xFFFFF4D2).copy(alpha = 0.65f)
                        val red = Color(0xFFD94A38)
                        val amber = Color(0xFFE5A93C)
                        val cx = w / 2f; val cy = h / 2f
                        fun line(x: Float, y: Float, xx: Float, yy: Float, ink: Color = white) =
                            drawLine(ink, Offset(x, y), Offset(xx, yy), stroke)
                        fun corners(inset: Float, ink: Color, extent: Float = 0.055f) {
                            val x = w * inset; val y = h * inset
                            val len = minOf(w, h) * extent
                            for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
                                val px = if (right) w - x else x; val py = if (bottom) h - y else y
                                line(px, py, px + if (right) -len else len, py, ink)
                                line(px, py, px, py + if (bottom) -len else len, ink)
                            }
                        }
                        fun text(value: String, x: Float, y: Float, ink: Color = white, right: Boolean = false) {
                            textPaint.color = android.graphics.Color.argb((ink.alpha * 255).toInt(),
                                (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
                            textPaint.textAlign = if (right) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
                            val metrics = textPaint.fontMetrics
                            val inset = minOf(1.dp.toPx(), h * .02f)
                            val top = inset - metrics.ascent
                            val bottom = (h - inset - metrics.descent).coerceAtLeast(top)
                            drawContext.canvas.nativeCanvas.drawText(value, x, y.coerceIn(top, bottom), textPaint)
                        }
                        when (style) {
                            ViewfinderStyle.LEICA -> {
                                corners(0.07f, white); corners(0.19f, white.copy(alpha = 0.35f), 0.035f)
                                text("35", w * .07f, h * .07f - 4.dp.toPx())
                                text("50", w * .19f, h * .19f - 4.dp.toPx(), white.copy(alpha = .45f))
                                val patch = Size(w * .11f, h * .075f)
                                drawRect(amber.copy(alpha = .055f), Offset(cx - patch.width / 2, cy - patch.height / 2), patch)
                                drawRect(amber.copy(alpha = .65f), Offset(cx - patch.width / 2, cy - patch.height / 2), patch, style = Stroke(stroke))
                                drawRect(amber.copy(alpha = .24f), Offset(cx - patch.width * .44f, cy - patch.height / 2), patch, style = Stroke(stroke))
                                val ledY = h * .94f
                                val ledX = w * .32f
                                val ledSize = 3.dp.toPx()
                                val leftArrow = Path().apply {
                                    moveTo(ledX - ledSize * 3f, ledY)
                                    lineTo(ledX - ledSize * 2f, ledY - ledSize)
                                    lineTo(ledX - ledSize * 2f, ledY + ledSize); close()
                                }
                                val rightArrow = Path().apply {
                                    moveTo(ledX + ledSize * 3f, ledY)
                                    lineTo(ledX + ledSize * 2f, ledY - ledSize)
                                    lineTo(ledX + ledSize * 2f, ledY + ledSize); close()
                                }
                                drawPath(leftArrow, red.copy(alpha = .5f))
                                drawCircle(red.copy(alpha = .5f), ledSize * .55f, Offset(ledX, ledY))
                                drawPath(rightArrow, red.copy(alpha = .5f))
                                drawSevenSegment(shutter.removeSuffix("s"), Offset(w * .46f, ledY - 4.dp.toPx()),
                                    minOf(8.dp.toPx(), h * .035f), red)
                            }
                            ViewfinderStyle.GR -> {
                                val green = Color(0xFF76B88B).copy(alpha = .7f)
                                corners(.16f, green, .035f)
                                text("SNAP", w * .16f, h * .16f - 4.dp.toPx(), green)
                                for (i in -4..4) {
                                    val x = cx + i * w * .025f
                                    line(x, cy - if (i == 0) 4.dp.toPx() else 2.dp.toPx(), x, cy + 2.dp.toPx(), white.copy(alpha = .4f))
                                }
                                text("GR  ISO $iso  EV $ev", w * .94f, h * .94f, green, right = true)
                            }
                            ViewfinderStyle.FUJI -> {
                                val cyan = Color(0xFF80BEC5).copy(alpha = .65f)
                                corners(.09f, white)
                                line(cx - w * .012f, cy, cx + w * .012f, cy, cyan)
                                line(cx, cy - h * .018f, cx, cy + h * .018f, cyan)
                                line(w * .06f, h * .89f, w * .94f, h * .89f, cyan.copy(alpha = .25f))
                                text("$shutter  ISO $iso", w * .06f, h * .95f, cyan)
                                text(filmLabel.take(22), w * .94f, h * .08f, amber.copy(alpha = .65f), right = true)
                            }
                            ViewfinderStyle.HASSEL -> {
                                val side = minOf(w, h) * .8f
                                val x = cx - side / 2; val y = cy - side / 2
                                drawRect(white.copy(alpha = .25f), Offset(x, y), Size(side, side), style = Stroke(stroke))
                                for (i in 1..3) {
                                    val t = side * i / 4
                                    line(x + t, y, x + t, y + side, white.copy(alpha = .12f))
                                    line(x, y + t, x + side, y + t, white.copy(alpha = .12f))
                                }
                                line(cx - 6.dp.toPx(), cy, cx + 6.dp.toPx(), cy)
                                line(cx, cy - 6.dp.toPx(), cx, cy + 6.dp.toPx())
                                drawCircle(amber.copy(alpha = .65f), 12.dp.toPx(), Offset(cx, cy), style = Stroke(stroke))
                            }
                            ViewfinderStyle.NIKON -> {
                                val radius = minOf(w, h) * .09f
                                drawCircle(white.copy(alpha = .3f), radius * 1.4f, Offset(cx, cy), style = Stroke(stroke))
                                drawCircle(white.copy(alpha = .65f), radius, Offset(cx, cy), style = Stroke(stroke))
                                line(cx - radius, cy, cx + radius, cy, white.copy(alpha = .5f))
                                for (i in 0 until 24) {
                                    val a = i * Math.PI / 12
                                    line(cx + kotlin.math.cos(a).toFloat() * radius * 1.15f,
                                        cy + kotlin.math.sin(a).toFloat() * radius * 1.15f,
                                        cx + kotlin.math.cos(a).toFloat() * radius * 1.35f,
                                        cy + kotlin.math.sin(a).toFloat() * radius * 1.35f, white.copy(alpha = .16f))
                                }
                                text("+", w * .94f, h * .43f, red.copy(alpha = .6f))
                                drawCircle(red.copy(alpha = .6f), 2.dp.toPx(), Offset(w * .948f, h * .5f), style = Stroke(stroke))
                                text("-", w * .94f, h * .59f, red.copy(alpha = .6f))
                            }
                            ViewfinderStyle.OFF -> Unit
                        }
                        val closed = curtain.value
                        if (closed > 0f) {
                            val curtainHeight = h * .5f * closed
                            drawRect(Palette.Background, size = Size(w, curtainHeight))
                            drawRect(Palette.Background, Offset(0f, h - curtainHeight), Size(w, curtainHeight))
                        }
                    }
                }
            }
        }
    })
}

private val LedSegments = mapOf(
    '0' to "abcedf", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
    '5' to "afgcd", '6' to "afgecd", '7' to "abc", '8' to "abcdefg", '9' to "abfgcd", '-' to "g",
)

private fun DrawScope.drawSevenSegment(value: String, origin: Offset, height: Float, ink: Color) {
    val width = height * .52f
    val pen = maxOf(.6.dp.toPx(), height * .075f)
    val endpoints = mapOf(
        'a' to (Offset(0f, 0f) to Offset(width, 0f)),
        'b' to (Offset(width, 0f) to Offset(width, height / 2)),
        'c' to (Offset(width, height / 2) to Offset(width, height)),
        'd' to (Offset(0f, height) to Offset(width, height)),
        'e' to (Offset(0f, height / 2) to Offset(0f, height)),
        'f' to (Offset(0f, 0f) to Offset(0f, height / 2)),
        'g' to (Offset(0f, height / 2) to Offset(width, height / 2)),
    )
    value.take(10).forEachIndexed { i, digit ->
        val start = origin + Offset(i * (width + height * .24f), 0f)
        when (digit) {
            '/' -> drawLine(ink, start + Offset(0f, height), start + Offset(width, 0f), pen, StrokeCap.Round)
            '.' -> drawCircle(ink, pen / 2, start + Offset(width / 2, height))
            else -> LedSegments[digit]?.forEach { segment ->
                val ends = endpoints.getValue(segment)
                drawLine(ink, start + ends.first, start + ends.second, pen, StrokeCap.Round)
            }
        }
    }
}
