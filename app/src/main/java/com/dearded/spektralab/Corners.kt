package com.dearded.spektralab

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Corners (tools/work/ui_corners.md). Every rounded thing in the app takes its
 * shape from [Corners], and every one is a continuous-curvature corner: Figma's
 * squircle (figma.com/blog/desperately-seeking-squircles), a cubic Bézier, an
 * arc and the mirrored Bézier, so the curvature rises from zero at the straight
 * edge instead of jumping to 1/R as a plain arc does. smoothing 0.6 is the iOS
 * look. Nested shapes keep inner radius = outer radius - padding.
 */

/**
 * A rounded rectangle with smoothed corners. [smoothing] 0 is a plain rounded
 * rectangle, 1 spends twice the radius of each edge on the corner. Too little
 * room (a pill) lowers the smoothing first and then the radius, so a fully
 * round shape ends as an ordinary semicircle.
 */
class SmoothCornerShape(
    topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize,
    val smoothing: Float = SMOOTHING,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) =
        SmoothCornerShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun createOutline(
        size: Size, topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float, layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) return Outline.Rectangle(size.toRect())
        val ltr = layoutDirection == LayoutDirection.Ltr
        val tl = if (ltr) topStart else topEnd
        val tr = if (ltr) topEnd else topStart
        val br = if (ltr) bottomEnd else bottomStart
        val bl = if (ltr) bottomStart else bottomEnd
        val w = size.width
        val h = size.height
        val budget = min(w, h) / 2f
        val path = Path()
        // Clockwise from the top edge; each corner in its own frame: apex, the
        // direction back along the edge coming in, the direction along the edge going out.
        val corners = listOf(
            Corner(tr, Offset(w, 0f), Offset(-1f, 0f), Offset(0f, 1f)),
            Corner(br, Offset(w, h), Offset(0f, -1f), Offset(-1f, 0f)),
            Corner(bl, Offset(0f, h), Offset(1f, 0f), Offset(0f, -1f)),
            Corner(tl, Offset(0f, 0f), Offset(0f, 1f), Offset(1f, 0f)),
        )
        val start = corners.last().params(budget, smoothing).p
        path.moveTo(start, 0f)
        for (c in corners) c.draw(path, c.params(budget, smoothing))
        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is SmoothCornerShape && other.smoothing == smoothing &&
        other.topStart == topStart && other.topEnd == topEnd && other.bottomEnd == bottomEnd && other.bottomStart == bottomStart

    override fun hashCode() = ((((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 +
        bottomStart.hashCode()) * 31) + smoothing.hashCode()

    override fun toString() = "SmoothCornerShape($topStart, $topEnd, $bottomEnd, $bottomStart, smoothing=$smoothing)"

    private class Params(val r: Float, val p: Float, val a: Float, val b: Float, val c: Float, val d: Float,
                         val beta: Float, val k: Float)

    private class Corner(val radius: Float, val apex: Offset, val back: Offset, val out: Offset) {
        fun params(budget: Float, smoothing: Float): Params {
            var r = radius.coerceAtLeast(0f)
            var xi = smoothing.coerceIn(0f, 1f)
            var p = (1 + xi) * r
            if (p > budget) {   // Figma's order: less smoothing first, then a smaller radius
                xi = if (r > 0f) (budget / r - 1f).coerceIn(0f, xi) else 0f
                r = min(r, budget)
                p = min((1 + xi) * r, budget)
            }
            if (r <= 0f) return Params(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val arcDeg = 90f * (1 - xi)
            val arcLen = (sin(rad(arcDeg / 2)) * r * sqrt(2f))
            val alpha = (90f - arcDeg) / 2
            val p3p4 = r * tan(rad(alpha / 2))
            val beta = 45f * xi
            val c = p3p4 * cos(rad(beta))
            val d = c * tan(rad(beta))
            val b = ((p - arcLen - c - d) / 3f).coerceAtLeast(0f)
            // The arc as one cubic: handles 4/3·tan(θ/4)·R along its end tangents.
            val k = 4f / 3f * tan(rad(arcDeg) / 4f) * r
            return Params(r, p, 2 * b, b, c, d, rad(beta), k)
        }

        private fun pt(x: Float, y: Float) = Offset(apex.x + back.x * x + out.x * y, apex.y + back.y * x + out.y * y)

        fun draw(path: Path, q: Params) {
            if (q.r <= 0f) { path.lineTo(apex.x, apex.y); return }
            val s = pt(q.p, 0f)
            path.lineTo(s.x, s.y)
            val c1 = pt(q.p - q.a, 0f)
            val c2 = pt(q.p - q.a - q.b, 0f)
            val p1x = q.p - q.a - q.b - q.c
            val p1 = pt(p1x, q.d)
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, p1.x, p1.y)
            val p2y = q.p - q.a - q.b - q.c
            val a1 = pt(p1x - q.k * cos(q.beta), q.d + q.k * sin(q.beta))
            val a2 = pt(q.d + q.k * sin(q.beta), p2y - q.k * cos(q.beta))
            val p2 = pt(q.d, p2y)
            path.cubicTo(a1.x, a1.y, a2.x, a2.y, p2.x, p2.y)
            val e1 = pt(0f, q.p - q.a - q.b)
            val e2 = pt(0f, q.p - q.a)
            val e = pt(0f, q.p)
            path.cubicTo(e1.x, e1.y, e2.x, e2.y, e.x, e.y)
        }

        private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()
    }

    companion object {
        /** The iOS look (Figma 60 %). */
        const val SMOOTHING = 0.6f
    }
}

/** All four corners [r]. */
fun SmoothCornerShape(r: Dp, smoothing: Float = SmoothCornerShape.SMOOTHING) =
    CornerSize(r).let { SmoothCornerShape(it, it, it, it, smoothing) }

/** Top corners [r], bottom square (a panel or sheet top). */
fun SmoothTopShape(r: Dp) = SmoothCornerShape(CornerSize(r), CornerSize(r), CornerSize(0.dp), CornerSize(0.dp))

/**
 * The shape tokens (ui_corners.md 3). xs 4 badges; s 8 overlay buttons on the
 * photo, a segment inside its track; m 12 buttons, pills, tracks, text cards;
 * l 16 thumbnail cards; xl 24 dialogs and sheets. [full] only for the toolbar,
 * its chosen entry and the primary action. The photo itself is never rounded.
 */
object Corners {
    val xs = SmoothCornerShape(4.dp)
    val s = SmoothCornerShape(8.dp)
    val m = SmoothCornerShape(12.dp)
    val l = SmoothCornerShape(16.dp)
    val xl = SmoothCornerShape(24.dp)
    val full = CornerSize(50).let { SmoothCornerShape(it, it, it, it) }
    val panelTop = SmoothTopShape(24.dp)

    /** MaterialTheme's shapes: dialogs, menus, sheets and text fields take the same corners. */
    val material = Shapes(extraSmall = xs, small = s, medium = m, large = l, extraLarge = xl)
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(Offset.Zero, this)
