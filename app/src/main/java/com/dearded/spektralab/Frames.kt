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
    /** 135 sprocket strips beside the print, drawn here, not by the engine (Perforations.kt). */
    val perf: Perforation? = null,
    /** How the band is laid out: one of [CAPTION_LAYOUTS]; "classic" is the text-only bar. */
    val captionLayout: String = "classic",
    /** The logo slot: "none", "auto" (from EXIF), "custom" ([brandText]) or a [Brands] id picked by hand. */
    val brand: String = "none",
    val brandText: String = "",
    /** The film maker's logo beside the camera's, from the film on the print. */
    val filmLogo: Boolean = false,
    /** Logos in their own colours; only on light paper, dark paper always gets mono. */
    val logoColor: Boolean = false,
) {
    val isNone get() = top == 0f && bottom == 0f && side == 0f && canvasAspect == 0f && !caption && perf == null

    val lightPaper: Boolean get() = luminance(paper) > 0.5f

    /** Where the image sits on the card, in pixels, for a [w] x [h] image. */
    fun layout(w: Int, h: Int): FrameLayout {
        val s = minOf(w, h).toFloat()
        // The sprocket strips sit against the print; the paper margins go round both.
        val p = perf?.margins(w, h, s) ?: Perforation.Margins(0f, 0f, 0f, 0f, 0f)
        var outW = w + p.left + p.right + 2 * side * s
        var outH = h + p.top + p.bottom + (top + bottom) * s
        var x = side * s + p.left
        var y = top * s + p.top
        if (canvasAspect > 0f) {
            if (outW / outH < canvasAspect) {
                val nw = outH * canvasAspect; x += (nw - outW) / 2; outW = nw
            } else {
                val nh = outW / canvasAspect; y += (nh - outH) / 2; outH = nh
            }
        }
        return FrameLayout(outW.roundToInt(), outH.roundToInt(), x.roundToInt(), y.roundToInt(), s, p.left, p.right, p.belowPrint)
    }

    /** Text on paper this light is dark, and the other way round. */
    val ink: Int get() = if (lightPaper) 0xFF1A1A1A.toInt() else 0xFFEDEAE4.toInt()
    val inkSoft: Int get() = if (lightPaper) 0xFF8A8A8A.toInt() else 0xFF9C988F.toInt()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("top", top.toDouble()).put("bottom", bottom.toDouble())
        .put("side", side.toDouble()).put("aspect", canvasAspect.toDouble()).put("paper", paper).put("caption", caption)
        .apply { perf?.let { put("perf", it.toJson()) } }
        .put("layout", captionLayout).put("brand", brand).put("brandText", brandText)
        .put("filmLogo", filmLogo).put("logoColor", logoColor)

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
                perf = Perforation.fromJson(o.optJSONObject("perf")),
                // Absent in frames saved before logos: the text-only bar, no logo.
                captionLayout = o.optString("layout", "classic").takeIf { l -> CAPTION_LAYOUTS.any { it.first == l } } ?: "classic",
                brand = o.optString("brand", "none"), brandText = o.optString("brandText", ""),
                filmLogo = o.optBoolean("filmLogo", false), logoColor = o.optBoolean("logoColor", false),
            )
        }.getOrDefault(FrameSpec())

        /** Band layouts. All but "classic" have a logo slot (UI.md appendix E.2, data bars). */
        val CAPTION_LAYOUTS = listOf(
            "classic" to "纯文字", "split" to "双栏", "center" to "居中", "line" to "单行", "film" to "胶片信息",
        )

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
            // The card keeps the print's colour space, so a P3 print stays P3.
            val out = Bitmap.createBitmap(l.width, l.height, Bitmap.Config.ARGB_8888, true,
                image.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            val canvas = Canvas(out)
            canvas.drawColor(spec.paper)
            spec.perf?.draw(canvas, l.x.toFloat(), l.y.toFloat(), image.width.toFloat(), image.height.toFloat(), l.short,
                caption?.film ?: "")
            canvas.drawBitmap(image, l.x.toFloat(), l.y.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            if (spec.caption && caption != null) drawCaption(canvas, spec, l, image.width, image.height, caption)
            return out
        }

        fun drawCaption(canvas: Canvas, spec: FrameSpec, l: FrameLayout, w: Int, h: Int, c: Caption) {
            val band = spec.bottom * l.short
            if (band < 4f) return
            if (spec.captionLayout != "classic") { BrandCaption.draw(canvas, spec, l, w, h, c); return }
            val bandTop = l.y + h.toFloat() + l.perfBelow
            val mid = bandTop + band / 2
            val margin = max(spec.side * l.short, l.short * 0.04f)
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.ink; textSize = l.short * 0.024f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.inkSoft; textSize = l.short * 0.017f }
            val gap = l.short * 0.008f
            val leftX = l.x - l.perfLeft + margin - spec.side * l.short
            canvas.drawText(c.camera, leftX, mid - gap, big)
            canvas.drawText(c.film, leftX, mid + gap + small.textSize, small)
            val right = l.x + w + l.perfRight - margin + spec.side * l.short
            big.textAlign = Paint.Align.RIGHT
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(c.settings, right, mid - gap, big)
            canvas.drawText(c.date, right, mid + gap + small.textSize, small)
        }
    }
}

data class FrameLayout(
    val width: Int, val height: Int, val x: Int, val y: Int, val short: Float,
    val perfLeft: Float = 0f, val perfRight: Float = 0f, val perfBelow: Float = 0f,
)

/**
 * The four caption lines: camera, film (left); exposure, date (right). [make]
 * and [exifModel] are EXIF as written, for matching a brand; [model] is the
 * name the camera is sold under ("23127PN0CC" -> "Xiaomi 14").
 */
data class Caption(
    val camera: String, val film: String, val settings: String, val date: String,
    val make: String = "", val exifModel: String = "", val model: String = "",
) {
    companion object {
        fun of(exif: ExifCopy, filmLabel: String): Caption {
            val model = Brands.marketName(exif.model)
            // A phone's code ("23127PN0CC") gives way to the name it is sold under, maker included.
            val camera = (if (model != exif.model) listOf(model) else listOf(exif.make, exif.model).filter { it.isNotBlank() })
                .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) listOf(parts[1]) else parts }
                .joinToString(" ").ifBlank { "Spektralab-X" }
            val settings = listOfNotNull(
                exif.focal35()?.let { "${it}mm" },
                exif.aperture()?.let { "f/$it" },
                exif.shutter(),
                exif.iso()?.let { "ISO$it" },
            ).joinToString("  ")
            val date = exif.dateTimeOriginal?.take(10)?.replace(':', '.') ?: ""
            return Caption(camera, filmLabel, settings, date, exif.make, exif.model, model)
        }
    }
}

/**
 * The data-bar layouts with a logo slot (UI.md appendix E.2): 双栏 (logo and
 * model left, exposure right), 居中 (logo over one line of data), 单行
 * (everything on one line) and 胶片信息 (film maker and stock left, camera
 * right). A missing EXIF item is left out, never printed as "-". Each layout
 * is measured at full size first and scaled down as a whole when the band is
 * too narrow; a two-column bar that would shrink past 72 % is drawn centred
 * and stacked instead.
 */
object BrandCaption {
    private val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private const val MIN_SIDE_BY_SIDE = 0.72f

    /** System sans at cap height [cap]. */
    private fun text(cap: Float, colour: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = cap / 0.72f; color = colour; typeface = if (bold) BOLD else Typeface.DEFAULT
    }

    /** The band's geometry and inks. */
    private class Band(spec: FrameSpec, l: FrameLayout, w: Int, h: Int) {
        val height = spec.bottom * l.short
        val mid = l.y + h + l.perfBelow + height / 2
        private val margin = max(spec.side * l.short, l.short * 0.04f)
        val left = l.x - l.perfLeft - spec.side * l.short + margin
        val right = l.x + w + l.perfRight + spec.side * l.short - margin
        val width get() = right - left
        val colour = spec.logoColor && spec.lightPaper
        val logoInk: Int? = if (colour) null else spec.ink
        val ink = spec.ink
        val soft = spec.inkSoft
        fun row(marks: List<Mark>, cap: Float) = if (marks.isEmpty()) null else MarkRow(marks, cap, colour, BOLD)
    }

    /** A bold line over a soft one, either of which may be blank, centred on a line. */
    private class Lines(main: String, sub: String, private val cap: Float, b: Band) {
        private val a = main.trim()
        private val z = sub.trim()
        private val subCap = cap * 0.75f
        private val pa = text(cap, b.ink, true)
        private val pz = text(subCap, b.soft, false)
        val isEmpty get() = a.isEmpty() && z.isEmpty()
        val width = max(if (a.isEmpty()) 0f else pa.measureText(a), if (z.isEmpty()) 0f else pz.measureText(z))

        fun draw(canvas: Canvas, x: Float, mid: Float, alignRight: Boolean) {
            val align = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
            pa.textAlign = align
            pz.textAlign = align
            when {
                a.isNotEmpty() && z.isNotEmpty() -> {
                    val gap = cap * 0.55f
                    val base = mid - (cap + gap + subCap) / 2 + cap
                    canvas.drawText(a, x, base, pa)
                    canvas.drawText(z, x, base + gap + subCap, pz)
                }
                a.isNotEmpty() -> canvas.drawText(a, x, mid + cap / 2, pa)
                z.isNotEmpty() -> canvas.drawText(z, x, mid + subCap / 2, pz)
            }
        }
    }

    fun draw(canvas: Canvas, spec: FrameSpec, l: FrameLayout, w: Int, h: Int, c: Caption) {
        val b = Band(spec, l, w, h)
        // Two stacked lines fill a band at 16 %; the one-line bar can stand taller.
        val cap = b.height * if (spec.captionLayout == "line") 0.26f else 0.16f
        val camera = Brands.cameraMark(spec, c)
        val film = Brands.filmMark(c)
        val marks = listOfNotNull(camera, film?.takeIf { spec.filmLogo })
        when (spec.captionLayout) {
            "split" -> if (!split(canvas, b, cap, marks, c)) center(canvas, b, cap, marks, c)
            "line" -> line(canvas, b, cap, marks, c)
            "film" -> if (!filmBar(canvas, b, cap, camera, film, c)) center(canvas, b, cap, listOfNotNull(film, camera), c)
            else -> center(canvas, b, cap, marks, c)
        }
    }

    /** 双栏: logo, model and film on the left; exposure and date on the right. False when it will not fit. */
    private fun split(canvas: Canvas, b: Band, cap0: Float, marks: List<Mark>, c: Caption): Boolean {
        fun gap(cap: Float, row: MarkRow?, lines: Lines) = if (row != null && !lines.isEmpty) cap * 0.9f else 0f
        val row0 = b.row(marks, cap0)
        val left0 = Lines(c.model, c.film, cap0, b)
        val need = (row0?.width ?: 0f) + gap(cap0, row0, left0) + left0.width + cap0 * 2f + Lines(c.settings, c.date, cap0, b).width
        val k = minOf(1f, b.width / need)
        if (k < MIN_SIDE_BY_SIDE) return false
        val cap = cap0 * k
        val row = b.row(marks, cap)
        val left = Lines(c.model, c.film, cap, b)
        var x = b.left
        row?.let { it.draw(canvas, x, b.mid, b.ink, b.logoInk); x += it.width + gap(cap, it, left) }
        left.draw(canvas, x, b.mid, alignRight = false)
        Lines(c.settings, c.date, cap, b).draw(canvas, b.right, b.mid, alignRight = true)
        return true
    }

    /** 居中: the logo centred over one line of model, exposure and date. */
    private fun center(canvas: Canvas, b: Band, cap0: Float, marks: List<Mark>, c: Caption) {
        val line = listOf(c.model, c.settings, c.date).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("   ")
        val row0 = b.row(marks, cap0)
        if (row0 == null && line.isEmpty()) return
        val textCap = 0.8f
        val gap = if (row0 != null && line.isNotEmpty()) 0.7f else 0f
        val lineW = if (line.isEmpty()) 0f else text(cap0 * textCap, b.ink, false).measureText(line)
        val tall = (row0?.height ?: 0f) + (gap + if (line.isEmpty()) 0f else textCap) * cap0
        val k = minOf(1f, b.width / max(row0?.width ?: 0f, lineW), b.height * 0.8f / tall)
        val cap = cap0 * k
        val cx = (b.left + b.right) / 2
        var y = b.mid - tall * k / 2
        b.row(marks, cap)?.let { it.draw(canvas, cx - it.width / 2, y + it.height / 2, b.ink, b.logoInk); y += it.height + gap * cap }
        if (line.isNotEmpty()) {
            val p = text(cap * textCap, b.ink, false).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(line, cx, y + cap * textCap, p)
        }
    }

    /** 单行: logo, model, exposure and date on one centred line. */
    private fun line(canvas: Canvas, b: Band, cap0: Float, marks: List<Mark>, c: Caption) {
        val model = c.model.trim()
        val rest = listOf(c.settings, c.date).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("   ")
        fun widths(cap: Float, row: MarkRow?) = listOfNotNull(
            row?.width,
            model.takeIf { it.isNotEmpty() }?.let { text(cap, b.ink, true).measureText(it) },
            rest.takeIf { it.isNotEmpty() }?.let { text(cap, b.soft, false).measureText(it) },
        )
        val row0 = b.row(marks, cap0)
        val w0 = widths(cap0, row0)
        if (w0.isEmpty()) return
        val need = w0.sum() + cap0 * 1.2f * (w0.size - 1)
        val k = minOf(1f, b.width / need, if (row0 != null) b.height * 0.8f / row0.height else 1f)
        val cap = cap0 * k
        val row = b.row(marks, cap)
        val gap = cap * 1.2f
        var x = (b.left + b.right) / 2 - need * k / 2
        row?.let { it.draw(canvas, x, b.mid, b.ink, b.logoInk); x += it.width + gap }
        if (model.isNotEmpty()) {
            val p = text(cap, b.ink, true)
            canvas.drawText(model, x, b.mid + cap / 2, p)
            x += p.measureText(model) + gap
        }
        if (rest.isNotEmpty()) canvas.drawText(rest, x, b.mid + cap / 2, text(cap, b.soft, false))
    }

    /** 胶片信息: film maker and stock on the left; the camera's mark, model and exposure on the right. */
    private fun filmBar(canvas: Canvas, b: Band, cap0: Float, camera: Mark?, film: Mark?, c: Caption): Boolean {
        fun parts(cap: Float) = listOf(
            b.row(listOfNotNull(film), cap), b.row(listOfNotNull(camera), cap * 0.8f),
        ) to (Lines(c.film, c.date, cap, b) to Lines(c.model, c.settings, cap, b))
        fun need(cap: Float): Float {
            val (rows, lines) = parts(cap)
            val (fr, cr) = rows
            return (fr?.width?.plus(cap * 0.9f) ?: 0f) + lines.first.width + cap * 2f +
                (cr?.width?.plus(cap * 0.9f) ?: 0f) + lines.second.width
        }
        val k = minOf(1f, b.width / need(cap0))
        if (k < MIN_SIDE_BY_SIDE) return false
        val cap = cap0 * k
        val (rows, lines) = parts(cap)
        val (fr, cr) = rows
        var x = b.left
        fr?.let { it.draw(canvas, x, b.mid, b.ink, b.logoInk); x += it.width + cap * 0.9f }
        lines.first.draw(canvas, x, b.mid, alignRight = false)
        lines.second.draw(canvas, b.right, b.mid, alignRight = true)
        cr?.draw(canvas, b.right - lines.second.width - cap * 0.9f - cr.width, b.mid, b.ink, b.logoInk)
        return true
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
        // Data bars with a logo slot (UI.md appendix E.2). The brand is picked from
        // EXIF; names never say whose watermark they copy.
        BorderPreset("logo_split", "白条参数（品牌 logo）", "左侧 logo 与机型，右侧参数与日期", null,
            FrameSpec("logo_split", 0f, 0.13f, 0f, paper = FrameSpec.PAPER_WHITE, caption = true,
                captionLayout = "split", brand = "auto")),
        BorderPreset("logo_split_dark", "黑条参数（品牌 logo）", "同上，黑底，logo 用单色", null,
            FrameSpec("logo_split_dark", 0f, 0.13f, 0f, paper = FrameSpec.PAPER_BLACK, caption = true,
                captionLayout = "split", brand = "auto")),
        BorderPreset("logo_center", "相框参数（居中 logo）", "四周白边，底部居中 logo 与参数", null,
            FrameSpec("logo_center", 0.04f, 0.17f, 0.04f, paper = FrameSpec.PAPER_WHITE, caption = true,
                captionLayout = "center", brand = "auto")),
        BorderPreset("logo_line", "单行极简（品牌 logo）", "一行：logo、机型、参数", null,
            FrameSpec("logo_line", 0f, 0.08f, 0f, paper = FrameSpec.PAPER_WHITE, caption = true,
                captionLayout = "line", brand = "auto")),
        BorderPreset("logo_film", "胶片信息条（厂牌 logo）", "相机 logo 与胶片厂牌 logo 并排，胶片名与参数", null,
            FrameSpec("logo_film", 0f, 0.13f, 0f, paper = FrameSpec.PAPER_CREAM, caption = true,
                captionLayout = "film", brand = "auto", filmLogo = true)),
    )

    fun byId(id: String) = PRESETS.firstOrNull { it.id == id }
}
