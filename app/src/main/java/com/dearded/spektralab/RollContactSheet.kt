package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 导出印样: a roll laid out as a 6-column contact sheet on one JPEG, with the
 * edge print (model on odd frames, frame numbers 00 / 1 / 1A ...), sprocket
 * holes, and the roll's name and date at the top. Every cell is the light
 * table's own film thumbnail (FilmThumbs, developed only when the engine is
 * idle), never a full-size render, so the sheet costs one small session per
 * frame not yet cached. Frames never edited print in black and white, as the
 * roll view shows them. Saved to Pictures/Spektralab-X through MediaSaver.
 *
 * It runs as a job in the 正在处理 queue ([job]): the roll goes in as a
 * [snapshot] (Roll is computed from the frames, never stored), so a sheet a
 * kill interrupted is laid out again from the same frames at the next start.
 */
object RollContactSheet {
    private const val COLUMNS = 6
    /** FilmThumbs' largest size: the cell's width never asks for more. */
    private const val CELL = 512
    private val lock = Mutex()

    /** Busy: one sheet at a time (each holds up to 36 thumbnails' worth of drawing). */
    val busy get() = lock.isLocked

    /**
     * Lays out and saves [roll]'s sheet; returns its file name. The long edge
     * follows [maxPixels] (DeviceProfile's cap; 0 = its canvas ceiling).
     * [progress] hears each frame drawn; [onCreated] the pending gallery entry
     * before a byte is written.
     */
    suspend fun export(
        context: Context, store: FrameStore, roll: Roll, maxPixels: Long,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> }, onCreated: (Uri) -> Unit = {},
    ): String = lock.withLock {
        val app = context.applicationContext
        val g = Geometry.fit(roll.frames.size, maxPixels)
        val sheet = withContext(Dispatchers.Default) { Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888) }
        try {
            val canvas = Canvas(sheet)
            withContext(Dispatchers.Default) { drawPaper(canvas, g, roll) }
            roll.frames.forEachIndexed { i, f ->
                val (thumb, developed) = thumbFor(app, store, f)
                withContext(Dispatchers.Default) { drawFrame(canvas, g, roll, i, thumb, developed, f.landscape) }
                progress(i + 1, roll.frames.size)
            }
            save(app, sheet, onCreated)
        } finally {
            sheet.recycle()
        }
    }

    // --- the queue job ------------------------------------------------------------

    /** [roll] as a 正在处理 job; [ProcessingQueue] runs it through [export]. */
    fun job(roll: Roll, id: Long, maxPixels: Long) = ProcessingJob(
        id = id, uri = "", name = roll.title, raw = false, editJson = "{}", wbK = 0.0, wbTint = 0.0,
        format = ExportFormat.JPEG, size = ExportSize.FULL, workingEdge = 0, maxPixels = maxPixels, outPixels = 0,
        sheetJson = snapshot(roll).toString(), sheetFrames = roll.frames.size,
    )

    /** Everything the sheet prints about [roll], and its frames by id in order. */
    fun snapshot(roll: Roll): JSONObject = JSONObject()
        .put("id", roll.id).put("film", roll.film).put("name", roll.name).put("process", roll.process.name)
        .put("iso", roll.iso).put("day", roll.day).put("part", roll.part).put("model", roll.model)
        .put("manual", roll.manualId ?: "")
        .put("frames", JSONArray().apply { roll.frames.forEach { put(it.id) } })

    /** IO, FrameStore loaded. The roll [snapshot] wrote, with the frames still on the table; null when none are. */
    fun restore(store: FrameStore, json: String): Roll? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val ids = o.optJSONArray("frames") ?: return null
        val frames = (0 until ids.length()).mapNotNull { store.byId(ids.optString(it)) }
        if (frames.isEmpty()) return null
        return Roll(
            id = o.optString("id"), film = o.optString("film"), name = o.optString("name"),
            process = runCatching { FilmProcess.valueOf(o.optString("process")) }.getOrDefault(FilmProcess.NONE),
            iso = o.optString("iso"), day = o.optLong("day"), part = o.optInt("part", 1), frames = frames,
            model = o.optString("model"), manualId = o.optString("manual").ifEmpty { null },
        )
    }

    /** The frame through its own film when it has an edit, else the photo as shot (printed in black and white). */
    private suspend fun thumbFor(app: Context, store: FrameStore, f: Frame): Pair<Bitmap?, Boolean> {
        val edit = if (f.edited) withContext(Dispatchers.IO) { store.sidecar(f.id)?.edit } else null
        if (edit != null) {
            FilmThumbs.get(app, f, edit, CELL) { 0 }?.let { return it to true }
        }
        return Thumbnails.load(app, f, CELL) to (edit != null)
    }

    // --- layout --------------------------------------------------------------------

    /** Everything in pixels, scaled from the cell width [w] (a 36 x 24 gate is [w] x [h]). */
    private class Geometry(val w: Int, rows: Int) {
        val h = w * 2 / 3
        val margin = (w * 0.12f).toInt()
        val gap = max(2, (w * 0.04f).toInt())
        val edge = max(8, (w * 0.10f).toInt())
        val sprocket = max(6, (w * 0.09f).toInt())
        val rowGap = (w * 0.10f).toInt()
        val header = (w * 0.46f).toInt()
        val rowHeight = edge + sprocket + h + sprocket + edge
        val width = 2 * margin + COLUMNS * w + (COLUMNS - 1) * gap
        val height = margin + header + rows * rowHeight + (rows - 1).coerceAtLeast(0) * rowGap + margin

        fun cellLeft(col: Int) = margin + col * (w + gap)
        fun rowTop(row: Int) = margin + header + row * (rowHeight + rowGap)

        companion object {
            fun fit(frames: Int, maxPixels: Long): Geometry {
                val rows = max(1, ceil(frames / COLUMNS.toDouble()).toInt())
                val cap = (if (maxPixels > 0) maxPixels else DeviceProfile.MAX_CANVAS_PIXELS)
                    .coerceAtMost(DeviceProfile.MAX_CANVAS_PIXELS)
                var g = Geometry(CELL, rows)
                val px = g.width.toLong() * g.height
                if (px > cap) g = Geometry(max(64, (CELL * sqrt(cap.toDouble() / px)).toInt()), rows)
                return g
            }
        }
    }

    // --- drawing --------------------------------------------------------------------

    private val Paper = FrameSpec.PAPER_CREAM
    private val Strip = 0xFF141414.toInt()
    private val Ink = 0xFF1A1A1C.toInt()
    private val InkSoft = 0xFF6A6A72.toInt()
    private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    /** The process colour as the edge print's ink; a stock too dark to read on the strip prints grey. */
    private fun edgeInk(roll: Roll): Int {
        val c = roll.process.color
        return if (c.luminance() < 0.08f) FilmProcess.BW.color.toArgb() else c.toArgb()
    }

    private fun text(size: Float, color: Int, face: Typeface = Typeface.DEFAULT, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(face, Typeface.BOLD) else face
        fontFeatureSettings = "tnum"
    }

    private fun drawPaper(canvas: Canvas, g: Geometry, roll: Roll) {
        canvas.drawColor(Paper)
        val title = text(g.header * 0.36f, Ink, bold = true)
        val line = text(g.header * 0.22f, InkSoft, condensed)
        val top = g.margin + g.header * 0.40f
        canvas.drawText(roll.title, g.margin.toFloat(), top, title)
        canvas.drawText(roll.edgeLine, g.margin.toFloat(), top + g.header * 0.34f, line)
        val mark = tr("Spektralab-X · 印样")
        canvas.drawText(mark, g.width - g.margin - line.measureText(mark), top + g.header * 0.34f, line)
        // One continuous strip per row, as negatives laid on the paper.
        val strip = Paint().apply { color = Strip }
        val rows = (roll.frames.size + COLUMNS - 1) / COLUMNS
        for (r in 0 until rows) {
            val inRow = min(COLUMNS, roll.frames.size - r * COLUMNS)
            val left = g.cellLeft(0) - g.gap / 2f
            val right = g.cellLeft(inRow - 1) + g.w + g.gap / 2f
            val y = g.rowTop(r).toFloat()
            canvas.drawRect(left, y, right, y + g.rowHeight, strip)
        }
    }

    private fun drawFrame(canvas: Canvas, g: Geometry, roll: Roll, index: Int, thumb: Bitmap?, developed: Boolean, landscape: Boolean) {
        val col = index % COLUMNS
        val row = index / COLUMNS
        val x = g.cellLeft(col).toFloat()
        val y = g.rowTop(row).toFloat()
        val ink = edgeInk(roll)
        val edgeText = text(g.edge * 0.78f, ink, condensed)
        val halfText = text(g.edge * 0.78f, (ink and 0x00FFFFFF) or (0xB3 shl 24), condensed)
        val centre = x + g.w / 2f
        fun centred(s: String, baseline: Float, p: Paint) = canvas.drawText(s, centre - p.measureText(s) / 2, baseline, p)
        val textUp = y + g.edge * 0.80f
        val textDown = y + g.rowHeight - g.edge * 0.22f
        // Edge print as on the strip: the model on odd frames and 00, the number below, the half mark trailing.
        if (index == 0 || index % 2 == 1) {
            if (roll.edgeModel.isNotEmpty()) centred(roll.edgeModel, textUp, edgeText)
            centred(frameNumber(index), textDown, edgeText)
        }
        val half = halfMark(index)
        canvas.drawText(half, x + g.w - halfText.measureText(half), textDown, halfText)

        // Eight perforations per full frame, above and below, punched through to the paper.
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paper }
        val pitch = g.w / 8f
        val hw = pitch * 0.5f
        val hh = g.sprocket * 0.62f
        for (band in listOf(y + g.edge, y + g.edge + g.sprocket + g.h)) {
            val hy = band + (g.sprocket - hh) / 2
            for (k in 0 until 8) {
                val hx = x + k * pitch + (pitch - hw) / 2
                canvas.drawRoundRect(RectF(hx, hy, hx + hw, hy + hh), hh * 0.25f, hh * 0.25f, hole)
            }
        }

        val gate = RectF(x, y + g.edge + g.sprocket, x + g.w, y + g.edge + g.sprocket + g.h)
        canvas.drawRect(gate, Paint().apply { color = 0xFF000000.toInt() })
        val src = thumb?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it } ?: return
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                if (!developed) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.save()
            canvas.clipRect(gate)
            // A landscape frame fills the gate; an upright one stands in it whole, as on the strip.
            val (from, to) = if (landscape) cropTo(src, gate) to gate else Rect(0, 0, src.width, src.height) to fitIn(src, gate)
            canvas.drawBitmap(src, from, to, paint)
            canvas.restore()
        } finally {
            if (src !== thumb) src.recycle()
        }
    }

    private fun cropTo(b: Bitmap, dst: RectF): Rect {
        val want = dst.width() / dst.height()
        val have = b.width.toFloat() / b.height
        return if (have > want) {
            val w = (b.height * want).toInt()
            Rect((b.width - w) / 2, 0, (b.width + w) / 2, b.height)
        } else {
            val h = (b.width / want).toInt()
            Rect(0, (b.height - h) / 2, b.width, (b.height + h) / 2)
        }
    }

    private fun fitIn(b: Bitmap, dst: RectF): RectF {
        val s = min(dst.width() / b.width, dst.height() / b.height)
        val w = b.width * s
        val h = b.height * s
        return RectF(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2)
    }

    // --- saving ----------------------------------------------------------------------

    private suspend fun save(app: Context, sheet: Bitmap, onCreated: (Uri) -> Unit): String = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val name = "Spektralab-X_contact_$stamp.jpg"
        val dir = File(app.cacheDir, "contact").apply { mkdirs() }
        val part = File(dir, "$name.part")
        try {
            part.outputStream().use { if (!sheet.compress(Bitmap.CompressFormat.JPEG, 92, it)) throw EngineException(tr("印样编码失败")) }
            MediaSaver.checkJpeg(part, sheet.width, sheet.height)
            MediaSaver.saveFile(app, part, name, "image/jpeg", onCreated)
            name
        } finally {
            part.delete()
        }
    }
}
