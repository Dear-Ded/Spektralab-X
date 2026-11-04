package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
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
data class Curve(val points: List<CurvePoint> = IDENTITY_POINTS) {
    val isIdentity get() = points == IDENTITY_POINTS

    fun evaluate(x: Double): Double {
        val p = points
        val n = p.size
        if (n < 2) return x
        if (x <= p[0].x) return p[0].y
        if (x >= p[n - 1].x) return p[n - 1].y
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
                val lo = points[i - 1].x + 0.01; val hi = points[i + 1].x - 0.01
                x = x.coerceIn(min(lo, hi), max(lo, hi))
            }
        }
        return Curve(points.toMutableList().also { it[i] = CurvePoint(x, q.y.coerceIn(0.0, 1.0)) })
    }

    fun isDeletable(i: Int) = i > 0 && i < points.size - 1
    fun remove(i: Int) = if (isDeletable(i)) Curve(points.toMutableList().also { it.removeAt(i) }) else this

    fun toJson(): JSONArray = JSONArray().also { a -> points.forEach { a.put(JSONArray().put(it.x).put(it.y)) } }

    companion object {
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

    /** layer2.hpp's kCurves tail: per row, the point count then the (x, y) pairs. Empty when all are identity. */
    fun tail(): FloatArray {
        if (isIdentity) return FloatArray(0)
        val out = ArrayList<Float>()
        for (c in rows) {
            out += c.points.size.toFloat()
            for (p in c.points) { out += p.x.toFloat(); out += p.y.toFloat() }
        }
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
private val CHANNELS = listOf(
    Triple(0, "RGB", Palette.OnSurface),
    Triple(2, "红", Color(0xFFE5584A)),
    Triple(3, "绿", Color(0xFF5EB65A)),
    Triple(4, "蓝", Color(0xFF4A8AE8)),
)

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
    onDrag: (Boolean) -> Unit, onChange: (CurveSet) -> Unit,
) {
    val view = LocalView.current
    var channel by remember { mutableIntStateOf(0) }
    val row = CHANNELS[channel].first
    val curve = curves[row]
    val curvesLatest by rememberUpdatedState(curves)
    val onChangeLatest by rememberUpdatedState(onChange)
    val onDragLatest by rememberUpdatedState(onDrag)
    val recede = LocalSliderDrag.current
    var active by remember { mutableStateOf(-1) }
    val histogram by produceState<Histogram?>(null, image) {
        value = image?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Histogram.of(it) } }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CHANNELS.forEachIndexed { i, (r, label, _) ->
            SelectPill(selected = channel == i, onClick = { channel = i }, enabled = enabled,
                label = { Text(if (curves[r].isIdentity) label else "$label·") })
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        TextButton(onClick = { view.tick(strong = true); onChange(curves.with(row, Curve.IDENTITY)) },
            enabled = enabled && !curve.isIdentity) { Text("重置") }
    }

    val color = CHANNELS[channel].third
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 6.dp).systemGestureExclusion()
            .semantics {
                contentDescription = "${CHANNELS[channel].second} 曲线，${curve.points.size} 个控制点"
                if (enabled && !curve.isIdentity)
                    customActions = listOf(CustomAccessibilityAction("重置") { onChangeLatest(curvesLatest.with(row, Curve.IDENTITY)); true })
            }
            .pointerInput(enabled, row) {
                if (!enabled) return@pointerInput
                val hit = 24.dp.toPx()
                var lastTap = 0L
                var lastTapIndex = -1
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    fun toUnit(o: Offset) = CurvePoint((o.x / w).toDouble(), (1 - o.y / h).toDouble())
                    var c = curvesLatest[row]
                    var index = c.points.indices.minByOrNull {
                        hypot(c.points[it].x * w - down.position.x, (1 - c.points[it].y) * h - down.position.y)
                    }?.takeIf {
                        hypot(c.points[it].x * w - down.position.x, (1 - c.points[it].y) * h - down.position.y) <= hit
                    } ?: -1
                    val grabbed = index >= 0
                    var moved = false
                    // The point keeps its offset from the finger, so it does not jump under it.
                    var grab = Offset.Zero
                    if (grabbed) grab = Offset((c.points[index].x * w).toFloat(), ((1 - c.points[index].y) * h).toFloat()) - down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (!moved) {
                            // The page's scroll took the swipe first: it is not ours.
                            if (ch.isConsumed) return@awaitEachGesture
                            val d = ch.position - down.position
                            if (d.getDistance() <= viewConfiguration.touchSlop) continue
                            // Off a point, an up/down swipe scrolls the page instead of
                            // trapping the finger in the curve (only a point is dragged freely).
                            if (!grabbed && abs(d.y) > abs(d.x)) return@awaitEachGesture
                            moved = true
                            if (!grabbed) {
                                // Dragging from an empty spot: a new point there, at the curve's own height.
                                val q = toUnit(down.position)
                                val (next, i) = c.insert(CurvePoint(q.x, c.evaluate(q.x)))
                                if (i == 0 || i == next.points.size - 1) break
                                c = next; index = i
                                grab = Offset((q.x * w).toFloat(), ((1 - c.points[i].y) * h).toFloat()) - down.position
                            }
                            active = index
                            onDragLatest(true); recede(true)
                        }
                        c = c.move(index, toUnit(ch.position + grab))
                        onChangeLatest(curvesLatest.with(row, c))
                        ch.consume()
                    }
                    if (moved) {
                        active = -1
                        onDragLatest(false); recede(false)
                    } else if (grabbed) {
                        val now = down.uptimeMillis
                        if (index == lastTapIndex && now - lastTap < viewConfiguration.doubleTapTimeoutMillis && c.isDeletable(index)) {
                            view.tick(strong = true)
                            onChangeLatest(curvesLatest.with(row, c.remove(index)))
                            lastTapIndex = -1
                        } else { lastTap = now; lastTapIndex = index }
                    } else {
                        val q = toUnit(down.position)
                        val (next, i) = c.insert(CurvePoint(q.x, c.evaluate(q.x)))
                        if (i > 0 && i < next.points.size - 1 && next.points.size <= 16) {
                            view.tick()
                            onChangeLatest(curvesLatest.with(row, next))
                        }
                    }
                }
            },
    ) {
        val w = size.width; val h = size.height
        drawRect(Palette.Panel)
        histogram?.let { hist ->
            val bins = when (row) { 2 -> hist.r; 3 -> hist.g; 4 -> hist.b; else -> hist.y }
            val peak = sqrt(max(1, bins.max()).toFloat())
            val bw = w / Histogram.BINS
            val path = Path().apply {
                moveTo(0f, h)
                for (i in bins.indices) {
                    val top = h * (1f - 0.9f * sqrt(bins[i].toFloat()) / peak)
                    lineTo(i * bw, top); lineTo((i + 1) * bw, top)
                }
                lineTo(w, h); close()
            }
            drawPath(path, color.copy(alpha = 0.16f))
        }
        val grid = Palette.OutlineSoft
        for (k in 1..3) {
            drawLine(grid, Offset(w * k / 4, 0f), Offset(w * k / 4, h), 1.dp.toPx())
            drawLine(grid, Offset(0f, h * k / 4), Offset(w, h * k / 4), 1.dp.toPx())
        }
        drawLine(Palette.Outline, Offset(0f, h), Offset(w, 0f), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
        fun curvePath(c: Curve) = Path().apply {
            val steps = 128
            for (s in 0..steps) {
                val x = s.toDouble() / steps
                val px = (x * w).toFloat(); val py = ((1 - c.evaluate(x)) * h).toFloat()
                if (s == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        // The other channels, faint, so a colour cast stays in view.
        for ((r, _, col) in CHANNELS) if (r != row && !curves[r].isIdentity)
            drawPath(curvePath(curves[r]), col.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
        val stroke = if (enabled) color else Palette.Outline
        drawPath(curvePath(curve), stroke, style = Stroke(2.dp.toPx()))
        curve.points.forEachIndexed { i, p ->
            val o = Offset((p.x * w).toFloat(), ((1 - p.y) * h).toFloat())
            drawCircle(stroke, if (i == active) 8.dp.toPx() else 6.dp.toPx(), o)
            drawCircle(Palette.Panel, if (i == active) 5.dp.toPx() else 3.5.dp.toPx(), o)
        }
    }
    Text(
        "轻点添加控制点，拖动调整，双击控制点删除。直方图是当前画面。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
