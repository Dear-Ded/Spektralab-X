package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class CurvePoint(val x: Double, val y: Double)

/**
 * A tone curve: control points in the unit square, sorted by x, the first at
 * x = 0 and the last at x = 1. Evaluated by the same Fritsch-Carlson monotone
 * cubic as upstream (view_process.cpp `curve()`, CurveMath.swift) and
 * layer2.cpp, so what the editor draws is what the print gets.
 */
data class Curve(val points: List<CurvePoint> = IDENTITY_POINTS, val linear: Boolean = false) {
    val isIdentity get() = points == IDENTITY_POINTS

    fun evaluate(x: Double): Double {
        val p = points
        val n = p.size
        if (n < 2) return x
        if (x <= p[0].x) return p[0].y
        if (x >= p[n - 1].x) return p[n - 1].y
        if (linear) {
            val found = p.binarySearch { it.x.compareTo(x) }
            if (found >= 0) return p[found].y
            val i = (-found - 2).coerceIn(0, n - 2)
            val t = (x - p[i].x) / (p[i + 1].x - p[i].x)
            return p[i].y + (p[i + 1].y - p[i].y) * t
        }
        val d = DoubleArray(n - 1) { (p[it + 1].y - p[it].y) / max(p[it + 1].x - p[it].x, 1e-6) }
        val m = DoubleArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) if (d[i] != 0.0) {
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]; val s = a * a + b * b
            if (s > 9) { val t = 3 / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        var i = 0
        while (i < n - 2 && x > p[i + 1].x) i++
        val h = max(p[i + 1].x - p[i].x, 1e-6); val t = (x - p[i].x) / h; val t2 = t * t; val t3 = t2 * t
        return ((2 * t3 - 3 * t2 + 1) * p[i].y + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * p[i + 1].y + (t3 - t2) * h * m[i + 1]).coerceIn(0.0, 1.0)
    }

    /** [q] added in x order; returns the curve and the new point's index. */
    fun insert(q: CurvePoint): Pair<Curve, Int> {
        val c = CurvePoint(q.x.coerceIn(0.0, 1.0), q.y.coerceIn(0.0, 1.0))
        points.indexOfFirst { it.x == c.x }.takeIf { it >= 0 }?.let { return this to it }
        if (points.size >= 256) return this to points.indices.minBy { abs(points[it].x - c.x) }
        val i = points.indexOfFirst { it.x > c.x }.let { if (it < 0) points.size else it }
        return Curve(points.toMutableList().also { it.add(i, c) }) to i
    }

    /** Point [i] at [q]: the ends keep their x; inner points stay 0.01 inside their neighbours (upstream's guard). */
    fun move(i: Int, q: CurvePoint): Curve {
        if (i !in points.indices) return this
        var x = q.x.coerceIn(0.0, 1.0)
        when (i) {
            0 -> x = 0.0
            points.size - 1 -> x = 1.0
            else -> {
                val gap = min(0.01, (points[i + 1].x - points[i - 1].x) / 4)
                val lo = max(Math.nextUp(points[i - 1].x), points[i - 1].x + gap)
                val hi = min(Math.nextDown(points[i + 1].x), points[i + 1].x - gap)
                x = if (lo <= hi) x.coerceIn(lo, hi) else points[i].x
            }
        }
        return Curve(points.toMutableList().also { it[i] = CurvePoint(x, q.y.coerceIn(0.0, 1.0)) })
    }

    fun isDeletable(i: Int) = i > 0 && i < points.size - 1
    fun remove(i: Int) = if (isDeletable(i)) Curve(points.toMutableList().also { it.removeAt(i) }) else this

    fun toJson(): JSONArray = JSONArray().also { a -> points.forEach { a.put(JSONArray().put(it.x).put(it.y)) } }

    /** Keep ordinary rows unchanged; sample in Double when Float would merge two x coordinates. */
    internal fun appendWire(out: MutableList<Float>) {
        if ((1 until points.size).any { points[it - 1].x.toFloat() >= points[it].x.toFloat() }) {
            out += SAMPLED_ROW
            repeat(WIRE_SAMPLES) { out += evaluate(it.toDouble() / (WIRE_SAMPLES - 1)).toFloat() }
        } else {
            out += points.size.toFloat()
            for (p in points) { out += p.x.toFloat(); out += p.y.toFloat() }
        }
    }

    companion object {
        internal const val WIRE_SAMPLES = 256
        internal const val SAMPLED_ROW = -256f
        val IDENTITY_POINTS = listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0))
        val IDENTITY = Curve()

        /** Upstream's rules (view_process.cpp `points`): 2..256 points, x strictly increasing, all in 0..1. Anything else reads as identity. */
        fun fromJson(a: JSONArray?): Curve {
            if (a == null || a.length() !in 2..256) return IDENTITY
            val p = ArrayList<CurvePoint>(a.length())
            for (k in 0 until a.length()) {
                val q = a.optJSONArray(k) ?: return IDENTITY
                val x = q.optDouble(0, Double.NaN); val y = q.optDouble(1, Double.NaN)
                if (!(x in 0.0..1.0) || !(y in 0.0..1.0) || (p.isNotEmpty() && x <= p.last().x)) return IDENTITY
                p += CurvePoint(x, y)
            }
            return Curve(p)
        }
    }
}

/** Upstream's five curves (CurveSet in CurveMath.swift), in its row order: rgb, luma, red, green, blue. */
data class CurveSet(
    val rgb: Curve = Curve.IDENTITY,
    val luma: Curve = Curve.IDENTITY,
    val red: Curve = Curve.IDENTITY,
    val green: Curve = Curve.IDENTITY,
    val blue: Curve = Curve.IDENTITY,
) {
    val isIdentity get() = rows.all { it.isIdentity }
    val rows get() = listOf(rgb, luma, red, green, blue)

    operator fun get(row: Int) = rows[row]
    fun with(row: Int, c: Curve) = when (row) {
        0 -> copy(rgb = c); 1 -> copy(luma = c); 2 -> copy(red = c); 3 -> copy(green = c); else -> copy(blue = c)
    }

    /** layer2.hpp's kCurves tail: control points or a sampled row. Empty when all are identity. */
    fun tail(): FloatArray {
        if (isIdentity) return FloatArray(0)
        val out = ArrayList<Float>()
        for (c in rows) c.appendWire(out)
        return out.toFloatArray()
    }

    /** By channel name, upstream's keys; identity channels left out. */
    fun toJson(): JSONObject = JSONObject().apply {
        rows.forEachIndexed { i, c -> if (!c.isIdentity) put(KEYS[i], c.toJson()) }
    }

    companion object {
        val IDENTITY = CurveSet()
        val KEYS = listOf("rgb", "luma", "red", "green", "blue")

        fun fromJson(o: JSONObject?): CurveSet {
            if (o == null) return IDENTITY
            val c = KEYS.map { Curve.fromJson(o.optJSONArray(it)) }
            return CurveSet(c[0], c[1], c[2], c[3], c[4])
        }
    }
}

/** The editor's channels: CurveSet row, label, colour. Luma stays in the model (imports) but not on screen. */
private val CHANNELS get() = listOf(
    Triple(0, "RGB", Palette.OnSurface),
    Triple(2, tr("红"), Color(0xFFE5584A)),
    Triple(3, tr("绿"), Color(0xFF5EB65A)),
    Triple(4, tr("蓝"), Color(0xFF4A8AE8)),
)

private data class CurvePlotGeometry(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun position(point: CurvePoint) = Offset(
        left + (point.x * width).toFloat(), top + ((1.0 - point.y) * height).toFloat(),
    )

    fun point(position: Offset) = CurvePoint(
        ((position.x - left) / width).toDouble().coerceIn(0.0, 1.0),
        (1.0 - (position.y - top) / height).coerceIn(0.0, 1.0),
    )

    fun contains(position: Offset) = position.x in left..(left + width) && position.y in top..(top + height)
}

private fun curvePlotGeometry(width: Float, height: Float, padding: Float): CurvePlotGeometry {
    val inset = min(padding, min(width, height) / 2f)
    return CurvePlotGeometry(inset, inset, (width - 2f * inset).coerceAtLeast(1f),
        (height - 2f * inset).coerceAtLeast(1f))
}

/**
 * The tone-curve editor (UI.md appendix A; behaviour after upstream's
 * CurveEditor.swift): RGB and the three channels over a histogram of the
 * picture on screen. Tap the curve or beside it to add a point where the curve
 * already is, drag a point to move it (ends keep their x), double-tap a point
 * to remove it. Every move re-packs the print, like a slider.
 */
@Composable
internal fun CurveEditor(
    curves: CurveSet, image: Bitmap?, enabled: Boolean,
    includeLuma: Boolean = false, editable: Boolean = true,
    onDrag: (Boolean) -> Unit, onChange: (CurveSet) -> Unit,
) {
    val channels = if (includeLuma) listOf(CHANNELS[0], Triple(1, tr("明度"), Palette.OnSurface)) + CHANNELS.drop(1) else CHANNELS
    val view = LocalView.current
    var selectedRow by remember { mutableIntStateOf(0) }
    val row = selectedRow.takeIf { selected -> channels.any { it.first == selected } } ?: 0
    val curve = curves[row]
    val curvesLatest by rememberUpdatedState(curves)
    val onChangeLatest by rememberUpdatedState(onChange)
    val onDragLatest by rememberUpdatedState(onDrag)
    val recedeLatest by rememberUpdatedState(LocalSliderDrag.current)
    var active by remember(row) { mutableIntStateOf(-1) }
    val histogram by produceState<Histogram?>(null, image) {
        value = image?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Histogram.of(it) } }
    }

    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag("curve-editor-channels"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            channels.forEach { (r, label, _) ->
                SelectPill(selected = row == r, onClick = { selectedRow = r }, enabled = enabled,
                    label = { Text(if (curves[r].isIdentity) label else "$label·",
                        modifier = Modifier.widthIn(min = 24.dp), textAlign = TextAlign.Center) })
            }
        }
        TextButton(onClick = { view.tick(strong = true); onChangeLatest(curvesLatest.with(row, Curve.IDENTITY)) },
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp),
            enabled = enabled && editable && !curve.isIdentity) { Text(tr("重置"), maxLines = 1) }
    }

    val color = channels.first { it.first == row }.third
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val plotHeight = if (maxWidth <= 320.dp) 176.dp else 192.dp
        Canvas(
            Modifier.fillMaxWidth().height(plotHeight).systemGestureExclusion().testTag("curve-editor-plot")
            .semantics {
                contentDescription = tr("{0} 曲线，{1} 个{2}", channels.first { it.first == row }.second,
                    curve.points.size, if (editable) tr("控制点") else tr("采样点"))
                if (!enabled) disabled()
                if (enabled && editable && !curve.isIdentity)
                    customActions = listOf(CustomAccessibilityAction(tr("重置")) { onChangeLatest(curvesLatest.with(row, Curve.IDENTITY)); true })
            }
            .pointerInput(enabled, editable, row, maxWidth, plotHeight) {
                if (!enabled || !editable) return@pointerInput
                val hit = 24.dp.toPx()
                var lastTap = 0L
                var lastTapIndex = -1
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    val geometry = curvePlotGeometry(size.width.toFloat(), size.height.toFloat(), 24.dp.toPx())
                    var c = curvesLatest[row]
                    var index = c.points.indices.minByOrNull {
                        (geometry.position(c.points[it]) - down.position).getDistance()
                    }?.takeIf {
                        (geometry.position(c.points[it]) - down.position).getDistance() <= hit
                    } ?: -1
                    val grabbed = index >= 0
                    if (!grabbed && !geometry.contains(down.position)) {
                        lastTapIndex = -1
                        return@awaitEachGesture
                    }
                    var started = false
                    var released = false
                    var upAt = 0L
                    var upPosition = down.position
                    // The point keeps its offset from the finger, so it does not jump under it.
                    var grab = Offset.Zero
                    if (grabbed) grab = geometry.position(c.points[index]) - down.position
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.isConsumed || event.changes.count { it.pressed } > 1) {
                                lastTapIndex = -1
                                break
                            }
                            if (!ch.pressed) {
                                released = true
                                upAt = ch.uptimeMillis
                                upPosition = ch.position
                                break
                            }
                            if (!started) {
                                val d = ch.position - down.position
                                if (d.getDistance() <= viewConfiguration.touchSlop) continue
                                // Away from a point, vertical swipes belong to the scrolling page.
                                if (!grabbed && abs(d.y) > abs(d.x)) break
                                lastTapIndex = -1
                                if (!grabbed) {
                                    if (c.points.size >= 16) break
                                    val q = geometry.point(down.position)
                                    val (next, i) = c.insert(CurvePoint(q.x, c.evaluate(q.x)))
                                    if (i == 0 || i == next.points.size - 1) break
                                    c = next; index = i
                                    grab = geometry.position(c.points[i]) - down.position
                                }
                                started = true
                                active = index
                                onDragLatest(true); recedeLatest(true)
                            }
                            c = c.move(index, geometry.point(ch.position + grab))
                            onChangeLatest(curvesLatest.with(row, c))
                            ch.consume()
                        }
                        if (!started && released && upAt - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis &&
                            (upPosition - down.position).getDistance() <= viewConfiguration.touchSlop) {
                            if (grabbed) {
                                val now = down.uptimeMillis
                                if (index == lastTapIndex && now - lastTap < viewConfiguration.doubleTapTimeoutMillis && c.isDeletable(index)) {
                                    view.tick(strong = true)
                                    onChangeLatest(curvesLatest.with(row, c.remove(index)))
                                    lastTapIndex = -1
                                } else { lastTap = now; lastTapIndex = index }
                            } else {
                                val q = geometry.point(down.position)
                                val (next, i) = c.insert(CurvePoint(q.x, c.evaluate(q.x)))
                                if (i > 0 && i < next.points.size - 1 && next.points.size <= 16) {
                                    view.tick()
                                    onChangeLatest(curvesLatest.with(row, next))
                                }
                            }
                        } else lastTapIndex = -1
                    } finally {
                        if (started) {
                            active = -1
                            try { onDragLatest(false) } finally { recedeLatest(false) }
                        }
                    }
                }
            },
        ) {
            val geometry = curvePlotGeometry(size.width, size.height, 24.dp.toPx())
            val w = geometry.width; val h = geometry.height
            val left = geometry.left; val top = geometry.top
            val bottom = top + h; val right = left + w
            drawRect(Palette.Panel)
            histogram?.let { hist ->
                val bins = when (row) { 2 -> hist.r; 3 -> hist.g; 4 -> hist.b; else -> hist.y }
                val peak = sqrt(max(1, bins.max()).toFloat())
                val bw = w / Histogram.BINS
                val path = Path().apply {
                    moveTo(left, bottom)
                    for (i in bins.indices) {
                        val y = top + h * (1f - 0.9f * sqrt(bins[i].toFloat()) / peak)
                        lineTo(left + i * bw, y); lineTo(left + (i + 1) * bw, y)
                    }
                    lineTo(right, bottom); close()
                }
                drawPath(path, color.copy(alpha = 0.16f))
            }
            val grid = Palette.OutlineSoft
            for (k in 1..3) {
                drawLine(grid, Offset(left + w * k / 4, top), Offset(left + w * k / 4, bottom), 1.dp.toPx())
                drawLine(grid, Offset(left, top + h * k / 4), Offset(right, top + h * k / 4), 1.dp.toPx())
            }
            drawLine(Palette.Outline, Offset(left, bottom), Offset(right, top), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
            fun curvePath(c: Curve) = Path().apply {
                val steps = if (c.linear) c.points.size - 1 else 128
                for (s in 0..steps) {
                    val x = s.toDouble() / steps
                    val position = geometry.position(CurvePoint(x, c.evaluate(x)))
                    if (s == 0) moveTo(position.x, position.y) else lineTo(position.x, position.y)
                }
            }
            // The other channels, faint, so a colour cast stays in view.
            for ((r, _, col) in channels) if (r != row && !curves[r].isIdentity)
                drawPath(curvePath(curves[r]), col.copy(alpha = 0.35f), style = Stroke(1.dp.toPx()))
            val stroke = if (enabled) color else Palette.Outline
            drawPath(curvePath(curve), stroke, style = Stroke(2.dp.toPx()))
            if (editable) curve.points.forEachIndexed { i, p ->
                val o = geometry.position(p)
                drawCircle(stroke, if (i == active) 7.dp.toPx() else 5.dp.toPx(), o)
                drawCircle(Palette.Panel, if (i == active) 4.dp.toPx() else 2.5.dp.toPx(), o)
            }
        }
    }
    Text(
        if (!editable) tr("导入的采样曲线按原样保留；重置后可编辑控制点。")
        else tr("轻点添加控制点，拖动调整，双击控制点删除。") + if (image != null) tr("直方图是当前画面。") else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
