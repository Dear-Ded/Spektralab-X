package com.dearded.spektralab

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
    /** A hairline round the print and its sprocket strips, as a fraction of the short edge; 0 = none. */
    val keyline: Float = 0f,
    /** The gap between the print and the hairline, same unit. */
    val keylineGap: Float = 0f,
    /** Rounded print corners, fraction of the short edge. */
    val radius: Float = 0f,
    /** The card is the print itself, blurred and dimmed with [paper], instead of plain paper. */
    val blur: Boolean = false,
    /** [signText] in pencil under the print's right corner, where a printmaker signs. */
    val sign: Boolean = false,
    val signText: String = "",
) {
    val isNone get() = top == 0f && bottom == 0f && side == 0f && canvasAspect == 0f && !caption && perf == null &&
        keyline == 0f && radius == 0f && !blur

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
            // Padding stops at MAX_PAD times the print's area (a 6x17 strip padded to
            // 9:16 would be a 5x card): the card then misses the aspect, not the memory budget.
            val limit = MAX_PAD * w * h
            if (outW / outH < canvasAspect) {
                val nw = minOf(outH * canvasAspect, maxOf(outW, limit / outH)); x += (nw - outW) / 2; outW = nw
            } else {
                val nh = minOf(outW / canvasAspect, maxOf(outH, limit / outW)); y += (nh - outH) / 2; outH = nh
            }
        }
        return FrameLayout(outW.roundToInt(), outH.roundToInt(), x.roundToInt(), y.roundToInt(), s, p.left, p.right, p.belowPrint, p.top)
    }

    /** Text on paper this light is dark, and the other way round. */
    val ink: Int get() = if (lightPaper) 0xFF1A1A1A.toInt() else 0xFFEDEAE4.toInt()
    val inkSoft: Int get() = if (lightPaper) 0xFF8A8A8A.toInt() else 0xFF9C988F.toInt()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("top", top.toDouble()).put("bottom", bottom.toDouble())
        .put("side", side.toDouble()).put("aspect", canvasAspect.toDouble()).put("paper", paper).put("caption", caption)
        .apply { perf?.let { put("perf", it.toJson()) } }
        .put("layout", captionLayout).put("brand", brand).put("brandText", brandText)
        .put("filmLogo", filmLogo).put("logoColor", logoColor)
        .put("keyline", keyline.toDouble()).put("keylineGap", keylineGap.toDouble()).put("radius", radius.toDouble())
        .put("blur", blur).put("sign", sign).put("signText", signText)

    companion object {
        const val PAPER_WHITE = 0xFFFFFFFF.toInt()
        const val PAPER_CREAM = 0xFFF4F1EA.toInt()
        const val PAPER_INSTANT = 0xFFF7F6F2.toInt()
        const val PAPER_MOUNT = 0xFFE9E6DF.toInt()
        const val PAPER_BLACK = 0xFF141414.toInt()
        const val PAPER_KRAFT = 0xFFC9A77C.toInt()
        val PAPERS = listOf(PAPER_WHITE, PAPER_CREAM, PAPER_MOUNT, PAPER_KRAFT, PAPER_BLACK)
        const val PAPER_SMOKE = 0xFF1E1E1E.toInt()
        private const val MAX_PAD = 4f

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
                // Absent before the gallery mats: no hairline, square corners, plain paper.
                keyline = o.optDouble("keyline", 0.0).toFloat().coerceIn(0f, 0.05f),
                keylineGap = o.optDouble("keylineGap", 0.0).toFloat().coerceIn(0f, 0.2f),
                radius = o.optDouble("radius", 0.0).toFloat().coerceIn(0f, 0.2f),
                blur = o.optBoolean("blur", false),
                sign = o.optBoolean("sign", false), signText = o.optString("signText", ""),
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
            drawCard(Canvas(out), spec, l, image, caption)
            return out
        }

        /**
         * Everything on the card, in card pixels: paper (or the blurred print),
         * sprocket strips, the print, its hairline, the signature and the
         * caption. The export draws it on its bitmap and the preview on a
         * canvas scaled to the screen, so the two never disagree.
         */
        fun drawCard(canvas: Canvas, spec: FrameSpec, l: FrameLayout, image: Bitmap, caption: Caption?) {
            val w = image.width
            val h = image.height
            val all = RectF(0f, 0f, l.width.toFloat(), l.height.toFloat())
            if (!spec.blur || !drawBlurred(canvas, all, image, spec.paper)) {
                canvas.drawRect(all, Paint().apply { color = spec.paper })
            }
            spec.perf?.draw(canvas, l.x.toFloat(), l.y.toFloat(), w.toFloat(), h.toFloat(), l.short, caption?.film ?: "")
            val dst = RectF(l.x.toFloat(), l.y.toFloat(), (l.x + w).toFloat(), (l.y + h).toFloat())
            val r = spec.radius * l.short
            if (r > 0f) {
                // A shader rather than a clip: a software canvas clips without anti-aliasing.
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(Matrix().apply { setTranslate(dst.left, dst.top) })
                    }
                }
                canvas.drawRoundRect(dst, r, r, paint)
            } else {
                canvas.drawBitmap(image, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            if (spec.keyline > 0f) {
                val t = max(1f, spec.keyline * l.short)
                val g = spec.keylineGap * l.short + t / 2
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t; color = spec.ink }
                canvas.drawRect(dst.left - l.perfLeft - g, dst.top - l.perfTop - g, dst.right + l.perfRight + g,
                    dst.bottom + l.perfBelow + g, line)
            }
            if (spec.sign && !spec.caption) drawSign(canvas, spec, l, w, h)
            if (spec.caption && caption != null) drawCaption(canvas, spec, l, w, h, caption)
        }

        /** The signature in pencil grey, serif italic, right-aligned under the print. */
        private fun drawSign(canvas: Canvas, spec: FrameSpec, l: FrameLayout, w: Int, h: Int) {
            val text = spec.signText.trim()
            val band = spec.bottom * l.short
            if (text.isEmpty() || band < 4f) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.inkSoft; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textAlign = Paint.Align.RIGHT; textSize = max(MIN_TEXT_PX, band * 0.2f)
            }
            val room = w + l.perfLeft + l.perfRight
            val wide = p.measureText(text)
            if (wide > room) p.textSize = max(MIN_TEXT_PX, p.textSize * room / wide)
            canvas.drawText(text, l.x + w + l.perfRight, l.y + h + l.perfBelow + band * 0.42f, p)
        }

        /** Smallest text the data bar and signature are drawn at, in card pixels. */
        const val MIN_TEXT_PX = 10f

        // The blurred card is built from a 96 px copy of the print, a few tens of
        // kilobytes; the preview redraws it every frame, so the last one is kept.
        private var blurSource: java.lang.ref.WeakReference<Bitmap>? = null
        private var blurGeneration = 0
        private var blurResult: Bitmap? = null

        /** The print scaled to cover [all], blurred and dimmed with [paper]. False if it cannot be read. */
        private fun drawBlurred(canvas: Canvas, all: RectF, image: Bitmap, paper: Int): Boolean {
            val small = synchronized(this) {
                if (blurSource?.get() === image && blurGeneration == image.generationId) blurResult
                else runCatching { blurredCopy(image) }.getOrNull().also {
                    blurSource = java.lang.ref.WeakReference(image); blurGeneration = image.generationId; blurResult = it
                }
            } ?: return false
            val k = max(all.width() / small.width, all.height() / small.height)
            val sw = small.width * k / 2
            val sh = small.height * k / 2
            canvas.drawBitmap(small, null, RectF(all.centerX() - sw, all.centerY() - sh, all.centerX() + sw, all.centerY() + sh),
                Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawRect(all, Paint().apply { color = paper; alpha = 0x8C })
            return true
        }

        private fun blurredCopy(image: Bitmap): Bitmap {
            val k = 96f / max(image.width, image.height)
            val src = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
            val scaled = Bitmap.createScaledBitmap(src, max(1, (image.width * k).roundToInt()), max(1, (image.height * k).roundToInt()), true)
            val small = if (scaled.isMutable && scaled !== image && scaled !== src) scaled
                else scaled.copy(Bitmap.Config.ARGB_8888, true).also { if (scaled !== image && scaled !== src) scaled.recycle() }
            // The full-size copy of a HARDWARE print is only needed for the scale-down.
            if (src !== image && src !== small) src.recycle()
            val sw = small.width
            val sh = small.height
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            // Three box passes each way come close to a Gaussian of about 5 px at this size.
            repeat(3) { boxBlur(px, sw, sh, 3, true); boxBlur(px, sw, sh, 3, false) }
            small.setPixels(px, 0, sw, 0, 0, sw, sh)
            return small
        }

        private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val line = IntArray(len)
            for (i in 0 until lines) {
                for (j in 0 until len) line[j] = px[if (horizontal) i * w + j else j * w + i]
                for (j in 0 until len) {
                    var a = 0; var rr = 0; var gg = 0; var bb = 0
                    for (d in -r..r) {
                        val c = line[(j + d).coerceIn(0, len - 1)]
                        a += c ushr 24; rr += c shr 16 and 0xFF; gg += c shr 8 and 0xFF; bb += c and 0xFF
                    }
                    val n = 2 * r + 1
                    px[if (horizontal) i * w + j else j * w + i] = (a / n shl 24) or (rr / n shl 16) or (gg / n shl 8) or (bb / n)
                }
            }
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
            var gap = l.short * 0.008f
            val leftX = l.x - l.perfLeft + margin - spec.side * l.short
            val right = l.x + w + l.perfRight - margin + spec.side * l.short
            // Measured first (UI.md E.3.6): a wide or narrow print shrinks the text to fit,
            // never under MIN_TEXT_PX; past that the two columns stack, centred.
            fun col(a: String, b: String) = max(big.measureText(a), small.measureText(b))
            val need = col(c.camera, c.film) + col(c.settings, c.date) + l.short * 0.04f
            val k = minOf(1f, (right - leftX) / need)
            big.textSize *= k; small.textSize *= k; gap *= k
            if (small.textSize < MIN_TEXT_PX) {
                big.textSize = max(MIN_TEXT_PX, big.textSize); small.textSize = MIN_TEXT_PX
                big.textAlign = Paint.Align.CENTER
                val cx = (leftX + right) / 2
                val fit = (right - leftX) / max(1f, max(big.measureText(c.camera), big.measureText(c.settings)))
                if (fit < 1f) big.textSize = max(MIN_TEXT_PX, big.textSize * fit)
                canvas.drawText(c.camera, cx, mid - gap, big)
                canvas.drawText(c.settings, cx, mid + gap + big.textSize * 0.75f, big)
                return
            }
            canvas.drawText(c.camera, leftX, mid - gap, big)
            canvas.drawText(c.film, leftX, mid + gap + small.textSize, small)
            big.textAlign = Paint.Align.RIGHT
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(c.settings, right, mid - gap, big)
            canvas.drawText(c.date, right, mid + gap + small.textSize, small)
        }
    }
}

data class FrameLayout(
    val width: Int, val height: Int, val x: Int, val y: Int, val short: Float,
    val perfLeft: Float = 0f, val perfRight: Float = 0f, val perfBelow: Float = 0f, val perfTop: Float = 0f,
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
                exif.iso()?.takeIf { it > 0 }?.let { "ISO$it" },
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
        val film = Brands.filmMark(c)?.takeIf { spec.filmLogo }
        val marks = listOfNotNull(camera, film)
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
    /** A date back the preset turns on (null leaves the date switch as it was). */
    val date: DateBack? = null,
)

/** The date back a preset sets: the face, the corner and how bright the LEDs burn in. */
data class DateBack(
    /** lcd, dots or data; data needs the film edge (it prints between frames). */
    val style: String = "lcd",
    val corner: String = "br",
    val ev: Double = 3.5,
    /** The shooting data as well as the date (between frames, film edge only). */
    val withData: Boolean = false,
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
    /** The film's real name on the edge print (overscan_edge_text). */
    val realName: Boolean = true,
    /** 1..99 prints this frame number; null leaves the edit's own (0 = the seed's draw). */
    val frameNumber: Int? = null,
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
            // Polaroid 600 card: 5.8 mm sides on a 76.8 mm picture (support.polaroid.com); the sides were 0.057.
            FrameSpec("instant_sq", 0.076f, 0.296f, 0.076f, paper = FrameSpec.PAPER_INSTANT), crop = 1.0),
        BorderPreset("instant_mini", "拍立得 Mini", "3:4 竖幅小卡", null,
            FrameSpec("instant_mini", 0.13f, 0.39f, 0.087f, paper = FrameSpec.PAPER_INSTANT), crop = 0.75),
        BorderPreset("exif_bar", "参数水印条", "底部白条：机型、镜头参数、日期", null,
            FrameSpec("exif_bar", 0f, 0.12f, 0f, paper = FrameSpec.PAPER_WHITE, caption = true)),
        BorderPreset("exif_bar_dark", "参数水印条 · 黑", "同上，黑底", null,
            FrameSpec("exif_bar_dark", 0f, 0.12f, 0f, paper = FrameSpec.PAPER_BLACK, caption = true)),
        BorderPreset("mount", "幻灯片框", "35mm 幻灯片片框", EdgeSpec(mode = "filed"),
            // A 50 x 50 mm mount round a 34 x 23 mm window: 8 mm a side over the 23 mm height, padded square.
            FrameSpec("mount", 0.348f, 0.348f, 0.348f, canvasAspect = 1f, paper = FrameSpec.PAPER_MOUNT)),
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
        // UI.md appendix E.2. Minimal edges, and padding to a feed's shape.
        BorderPreset("thin_white", "细白边", "窄而均匀的白边", null, FrameSpec("thin_white", 0.02f, 0.02f, 0.02f)),
        BorderPreset("thin_black", "细黑边", "窄而均匀的黑边", null,
            FrameSpec("thin_black", 0.02f, 0.02f, 0.02f, paper = FrameSpec.PAPER_BLACK)),
        BorderPreset("pad_1x1", "1:1 留白", "补成正方形", null, FrameSpec("pad_1x1", 0.03f, 0.03f, 0.03f, canvasAspect = 1f)),
        BorderPreset("pad_3x4", "3:4 留白", "补成 3:4 竖幅", null, FrameSpec("pad_3x4", 0.03f, 0.03f, 0.03f, canvasAspect = 0.75f)),
        BorderPreset("pad_9x16", "9:16 留白", "补成 9:16，适合竖屏", null,
            FrameSpec("pad_9x16", 0.03f, 0.03f, 0.03f, canvasAspect = 9f / 16f)),
        // Gallery mats: wide margins; a museum mat is weighted at the bottom.
        BorderPreset("mat_museum", "美术馆卡纸", "米白卡纸，下边加宽", null,
            FrameSpec("mat_museum", 0.12f, 0.18f, 0.12f, paper = FrameSpec.PAPER_CREAM)),
        BorderPreset("mat_black", "黑卡纸", "宽黑卡纸", null, FrameSpec("mat_black", 0.10f, 0.10f, 0.10f, paper = FrameSpec.PAPER_BLACK)),
        BorderPreset("mat_keyline", "细线卡纸", "白卡纸，照片外一圈细线", null,
            FrameSpec("mat_keyline", 0.12f, 0.12f, 0.12f, keyline = 0.0025f, keylineGap = 0.025f)),
        BorderPreset("mat_signed", "签名边", "下边留出签名位置，签名在边框面板里填", null,
            FrameSpec("mat_signed", 0.08f, 0.20f, 0.08f, paper = FrameSpec.PAPER_CREAM, sign = true)),
        BorderPreset("blur_card", "模糊背景卡", "照片本身虚化成底，补成 4:5，圆角", null,
            FrameSpec("blur_card", 0.08f, 0.08f, 0.08f, canvasAspect = 0.8f, paper = FrameSpec.PAPER_SMOKE,
                radius = 0.025f, blur = true)),
        // Engine film edges (UI.md E.2). The crop follows the gate's shape; see Edit.withBorder.
        BorderPreset("edge135_name", "135 带片名", "整条 135，片边印胶片真实名称与编号", EdgeSpec(holes = "black", fog = 0.6), FrameSpec()),
        BorderPreset("edge135_black", "135 黑框扫描", "黑色片夹压住片边，扫描仪的样子", EdgeSpec(carrier = "black", fog = 0.8), FrameSpec()),
        BorderPreset("edge135_num", "135 带编号", "片边帧号固定为 12", EdgeSpec(frameNumber = 12), FrameSpec()),
        BorderPreset("edge_6x7", "6×7", "120 中画幅，圆角片门", EdgeSpec(format = "120_6x7", gate = "rounded", holes = "black"), FrameSpec()),
        BorderPreset("edge_6x8", "6×8 肩形片门", "120，片门带肩", EdgeSpec(format = "120_6x8", gate = "shouldered", holes = "black"), FrameSpec()),
        BorderPreset("edge_6x9", "6×9", "120 长画幅", EdgeSpec(format = "120_6x9", holes = "black"), FrameSpec()),
        BorderPreset("edge_6x17", "6×17 宽幅", "120 全景，黑色片夹", EdgeSpec(format = "120_6x17", carrier = "black", holes = "black"), FrameSpec()),
        BorderPreset("edge_half", "半格", "135 半格，竖拍一帧", EdgeSpec(format = "135_half"), FrameSpec()),
        BorderPreset("enlarger", "暗房放大相纸", "锉开的片夹放在放大机下，白色相纸留边", EdgeSpec(mode = "filed", holes = "black", carrier = "black", fog = 0.4),
            FrameSpec("enlarger", 0.07f, 0.10f, 0.07f, paper = FrameSpec.PAPER_WHITE)),
        // Date backs (UI.md E.2): the engine burns the date into the film, upstream's faces.
        BorderPreset("date_lcd", "橙色 LCD", "相机日期后背：右下角橙色日期", null, FrameSpec(), date = DateBack("lcd")),
        BorderPreset("date_data", "数据后背", "片间印快门、光圈、焦距，画面内印日期", EdgeSpec(),
            FrameSpec(), date = DateBack("lcd", withData = true)),
        // Instant cards (UI.md E.2), margins over the picture's short edge from the makers'
        // card and picture sizes (Polaroid support; Fujifilm via analog.cafe). Neither
        // publishes how the top and bottom split, so the top matches the sides.
        BorderPreset("instant_600", "600 / i-Type", "宝丽来 600 与 i-Type 相纸，88×107 mm", null,
            FrameSpec("instant_600", 0.076f, 0.296f, 0.076f, paper = FrameSpec.PAPER_INSTANT), crop = 76.80 / 78.94),
        BorderPreset("instax_square", "Instax Square", "72×86 mm 方形相纸", null,
            FrameSpec("instax_square", 0.089f, 0.298f, 0.081f, paper = FrameSpec.PAPER_INSTANT), crop = 1.0),
        BorderPreset("instax_wide", "Instax Wide", "108×86 mm 宽幅相纸", null,
            FrameSpec("instax_wide", 0.105f, 0.282f, 0.073f, paper = FrameSpec.PAPER_INSTANT), crop = 99.0 / 62.0),
        BorderPreset("polaroid_go", "Go 小卡", "宝丽来 Go，54×67 mm", null,
            FrameSpec("polaroid_go", 0.086f, 0.340f, 0.086f, paper = FrameSpec.PAPER_INSTANT), crop = 46.0 / 47.0),
        BorderPreset("instax_mini_black", "Mini 黑边", "Instax Mini 黑色边框", null,
            FrameSpec("instax_mini_black", 0.13f, 0.39f, 0.087f, paper = FrameSpec.PAPER_BLACK), crop = 0.75),
        // Text-only data bars (UI.md E.2): the logo layouts with the logo slot empty.
        BorderPreset("bar_split", "双栏白条", "左侧机型与胶片，右侧参数与日期，纯文字", null,
            FrameSpec("bar_split", 0f, 0.12f, 0f, caption = true, captionLayout = "split")),
        BorderPreset("bar_frame", "相框参数", "四周白边，底部居中一行参数", null,
            FrameSpec("bar_frame", 0.04f, 0.15f, 0.04f, caption = true, captionLayout = "center")),
        BorderPreset("bar_line", "单行极简", "细白条，一行机型与参数", null,
            FrameSpec("bar_line", 0f, 0.07f, 0f, caption = true, captionLayout = "line")),
        BorderPreset("bar_film", "胶片信息条", "左侧胶片与日期，右侧机型与参数", null,
            FrameSpec("bar_film", 0f, 0.12f, 0f, paper = FrameSpec.PAPER_CREAM, caption = true, captionLayout = "film")),
    )

    fun byId(id: String) = PRESETS.firstOrNull { it.id == id }
}
