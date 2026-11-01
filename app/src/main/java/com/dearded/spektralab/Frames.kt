package com.dearded.spektralab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The paper around the print: mats, instant-film cards, slide mounts, a
 * shooting-data bar. Everything on the *film* (perforations, rebate, edge
 * print, fog, leaks, the date back) is the engine's overscan; everything on
 * paper or card around the print is drawn here, after the engine, so editing
 * it never re-develops the negative.
 *
 * Margins are fractions of the image's short edge, so a frame looks the same
 * at every working size.
 */
data class FrameSpec(
    val id: String = "none",
    val top: Float = 0f,
    val bottom: Float = 0f,
    val side: Float = 0f,
    /** Pad the whole card to this width/height (0 = margins only), e.g. 4:5 for a feed. */
    val canvasAspect: Float = 0f,
    val paper: Int = PAPER_WHITE,
    /** Shooting data on the bottom band. */
    val caption: Boolean = false,
) {
    val isNone get() = top == 0f && bottom == 0f && side == 0f && canvasAspect == 0f && !caption

    /** Where the image sits on the card, in pixels, for a [w] x [h] image. */
    fun layout(w: Int, h: Int): FrameLayout {
        val s = minOf(w, h).toFloat()
        var outW = w + 2 * side * s
        var outH = h + (top + bottom) * s
        var x = side * s
        var y = top * s
        if (canvasAspect > 0f) {
            if (outW / outH < canvasAspect) {
                val nw = outH * canvasAspect; x += (nw - outW) / 2; outW = nw
            } else {
                val nh = outW / canvasAspect; y += (nh - outH) / 2; outH = nh
            }
        }
        return FrameLayout(outW.roundToInt(), outH.roundToInt(), x.roundToInt(), y.roundToInt(), s)
    }

    /** Text on paper this light is dark, and the other way round. */
    val ink: Int get() = if (luminance(paper) > 0.5f) 0xFF1A1A1A.toInt() else 0xFFEDEAE4.toInt()
    val inkSoft: Int get() = if (luminance(paper) > 0.5f) 0xFF8A8A8A.toInt() else 0xFF9C988F.toInt()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("top", top.toDouble()).put("bottom", bottom.toDouble())
        .put("side", side.toDouble()).put("aspect", canvasAspect.toDouble()).put("paper", paper).put("caption", caption)

    companion object {
        const val PAPER_WHITE = 0xFFFFFFFF.toInt()
        const val PAPER_CREAM = 0xFFF4F1EA.toInt()
        const val PAPER_INSTANT = 0xFFF7F6F2.toInt()
        const val PAPER_MOUNT = 0xFFE9E6DF.toInt()
        const val PAPER_BLACK = 0xFF141414.toInt()
        const val PAPER_KRAFT = 0xFFC9A77C.toInt()
        val PAPERS = listOf(PAPER_WHITE, PAPER_CREAM, PAPER_MOUNT, PAPER_KRAFT, PAPER_BLACK)

        fun fromJson(o: JSONObject?): FrameSpec = if (o == null) FrameSpec() else runCatching {
            FrameSpec(
                id = o.optString("id", "custom"), top = o.optDouble("top").toFloat(), bottom = o.optDouble("bottom").toFloat(),
                side = o.optDouble("side").toFloat(), canvasAspect = o.optDouble("aspect").toFloat(),
                paper = o.optInt("paper", PAPER_WHITE), caption = o.optBoolean("caption"),
            )
        }.getOrDefault(FrameSpec())

        private fun luminance(c: Int): Float {
            val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        /**
         * The card around [image] with [caption] on the bottom band. A new
         * bitmap; [image] is left for the caller to recycle. Peak memory is
         * the two bitmaps, so callers check the budget first.
         */
        fun render(image: Bitmap, spec: FrameSpec, caption: Caption?): Bitmap {
            val l = spec.layout(image.width, image.height)
            val out = Bitmap.createBitmap(l.width, l.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(spec.paper)
            canvas.drawBitmap(image, l.x.toFloat(), l.y.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            if (spec.caption && caption != null) drawCaption(canvas, spec, l, image.width, image.height, caption)
            return out
        }

        fun drawCaption(canvas: Canvas, spec: FrameSpec, l: FrameLayout, w: Int, h: Int, c: Caption) {
            val band = spec.bottom * l.short
            if (band < 4f) return
            val bandTop = l.y + h.toFloat()
            val mid = bandTop + band / 2
            val margin = max(spec.side * l.short, l.short * 0.04f)
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.ink; textSize = l.short * 0.024f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.inkSoft; textSize = l.short * 0.017f }
            val gap = l.short * 0.008f
            val leftX = l.x + margin - spec.side * l.short
            canvas.drawText(c.camera, leftX, mid - gap, big)
            canvas.drawText(c.film, leftX, mid + gap + small.textSize, small)
            val right = l.x + w - margin + spec.side * l.short
            big.textAlign = Paint.Align.RIGHT
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(c.settings, right, mid - gap, big)
            canvas.drawText(c.date, right, mid + gap + small.textSize, small)
        }
    }
}

data class FrameLayout(val width: Int, val height: Int, val x: Int, val y: Int, val short: Float)

/** The four caption lines: camera, film (left); exposure, date (right). */
data class Caption(val camera: String, val film: String, val settings: String, val date: String) {
    companion object {
        fun of(exif: ExifCopy, filmLabel: String): Caption {
            val camera = listOf(exif.make, exif.model).filter { it.isNotBlank() }
                .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) listOf(parts[1]) else parts }
                .joinToString(" ").ifBlank { "SpektraLab" }
            val settings = listOfNotNull(
                exif.focal35()?.let { "${it}mm" },
                exif.aperture()?.let { "f/$it" },
                exif.shutter(),
                exif.iso()?.let { "ISO$it" },
            ).joinToString("  ")
            val date = exif.dateTimeOriginal?.take(10)?.replace(':', '.') ?: ""
            return Caption(camera, filmLabel, settings, date)
        }
    }
}

/**
 * A border preset: optional engine film edge plus an optional paper frame.
 * `edge` null means the film edge is off. `crop` asks for a centred crop to
 * that aspect when there is no film edge (the instant cards are 1:1 and 3:4).
 */
data class BorderPreset(
    val id: String,
    val label: String,
    val hint: String,
    val edge: EdgeSpec?,
    val frame: FrameSpec,
    val crop: Double = 0.0,
)

/** The engine's overscan knobs a preset sets (wire names in Model.kt). */
data class EdgeSpec(
    val format: String = "135",
    val mode: String = "strip",
    val holes: String = "white",
    val gate: String = "auto",
    val carrier: String = "none",
    val fog: Double = 1.0,
    val leaks: Double = 0.0,
)

object Borders {
    val PRESETS = listOf(
        BorderPreset("none", "无边框", "只有照片", null, FrameSpec()),
        BorderPreset("scan135", "135 扫描条", "齿孔、片基字、边缘雾化", EdgeSpec(), FrameSpec()),
        BorderPreset("filed", "暗房锉边", "锉开的底片夹，白色相纸", EdgeSpec(mode = "filed", holes = "black", fog = 0.5),
            FrameSpec("filed", 0.06f, 0.06f, 0.06f, paper = FrameSpec.PAPER_WHITE)),
        BorderPreset("contact", "印相小样", "整条底片印在相纸上，带参数", EdgeSpec(holes = "black"),
            FrameSpec("contact", 0.04f, 0.10f, 0.04f, paper = FrameSpec.PAPER_CREAM, caption = true)),
        BorderPreset("6x6", "6×6 方画幅", "120 宽片边与画幅编号", EdgeSpec(format = "120_6x6", gate = "kicked", holes = "black"), FrameSpec()),
        BorderPreset("645", "645 数据边", "片门耳朵与边缘数据", EdgeSpec(format = "120_645", gate = "eared"), FrameSpec()),
        BorderPreset("leak", "漏光胶卷", "片轴漏光与浓重边缘雾", EdgeSpec(fog = 2.5, leaks = 2.0), FrameSpec()),
        BorderPreset("xpan", "XPan 宽幅", "65×24 宽画幅", EdgeSpec(format = "135_xpan", carrier = "black"), FrameSpec()),
        BorderPreset("instant_sq", "宝丽来方卡", "1:1，底边宽", null,
            FrameSpec("instant_sq", 0.076f, 0.30f, 0.057f, paper = FrameSpec.PAPER_INSTANT), crop = 1.0),
        BorderPreset("instant_mini", "拍立得 Mini", "3:4 竖幅小卡", null,
            FrameSpec("instant_mini", 0.13f, 0.39f, 0.087f, paper = FrameSpec.PAPER_INSTANT), crop = 0.75),
        BorderPreset("exif_bar", "参数水印条", "底部白条：机型、镜头参数、日期", null,
            FrameSpec("exif_bar", 0f, 0.12f, 0f, paper = FrameSpec.PAPER_WHITE, caption = true)),
        BorderPreset("exif_bar_dark", "参数水印条 · 黑", "同上，黑底", null,
            FrameSpec("exif_bar_dark", 0f, 0.12f, 0f, paper = FrameSpec.PAPER_BLACK, caption = true)),
        BorderPreset("mount", "幻灯片框", "35mm 幻灯片片框", EdgeSpec(mode = "filed"),
            FrameSpec("mount", 0.15f, 0.15f, 0.15f, paper = FrameSpec.PAPER_MOUNT)),
        BorderPreset("gallery", "画廊白边", "均匀白边", null, FrameSpec("gallery", 0.05f, 0.05f, 0.05f)),
        BorderPreset("feed45", "4:5 留白", "补成 4:5，适合发图", null, FrameSpec("feed45", 0.03f, 0.03f, 0.03f, canvasAspect = 0.8f)),
    )

    fun byId(id: String) = PRESETS.firstOrNull { it.id == id }
}
