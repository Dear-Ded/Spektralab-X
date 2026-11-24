package com.dearded.spektralab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The camera dial (ui-polish; preview/ui-polish.html Dial): a knurled metal drum seen face on,
 * turned by the finger. Every editing value in the app is one of these.
 *
 *  Look   the drum curves away at both ends (ticks crowd and fade, as on a cylinder); ticks are
 *         cut into the metal and filled with paint (a dark cut, a light fill); knurling runs round
 *         both rims and turns with it; a fixed warm index marks the value; a stop at each end of
 *         the range. A value with a direction (warm / cool) gets that direction as a colour band.
 *  Feel   a detent at every step (Cue.Detent), firmer at labelled steps and stops, its own click at
 *         the default (Cue.Zero), a stop at the ends (Cue.Limit); flung, it coasts and settles on a
 *         step; held further than 36 dp above or below, it turns at a fifth for fine work; a tap
 *         steps one detent towards that side; two taps, or the ↺ above it, return to the default.
 *  Hands  only a sideways drag is the dial's. Vertical travel is left to the page, which scrolls.
 *
 * The position lives here (in steps) and is read in the draw phase, so a turning dial redraws
 * without recomposing; the model hears each step through onChange, not each pixel.
 */
@Composable
internal fun CameraDial(
    label: String, value: Double, min: Double, max: Double, default: Double,
    enabled: Boolean, format: String,
    onDrag: (Boolean) -> Unit = {},
    disabledReason: String = "",
    /** What each end does, for TalkBack: e.g. 偏黄 / 偏蓝. On screen the [tint] band says it. */
    ends: Pair<String, String>? = null,
    /** A colour along the range (0 = min, 1 = max), drawn as a band on the drum. */
    tint: ((Float) -> Color)? = null,
    /** Values that get a dot and a firmer detent (a style's 40 / 70 %). */
    stops: List<Double> = emptyList(),
    onTypeValue: (() -> Unit)? = null,
    /** False when the name is already on screen right above (a KnobRow's carousel). TalkBack still reads it. */
    showName: Boolean = true,
    onChange: (Double) -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lo = min.toFloat()
    val hi = max.toFloat()
    val span = (hi - lo).coerceAtLeast(1e-6f)
    val scale = remember(span, format) { dialScale(span, format) }
    val step = scale.step
    val major = scale.major
    val steps = (span / step).roundToInt().coerceAtLeast(1)
    val labelEvery = if (span / major > 6.5f) major * 2 else major
    val def = default.toFloat().coerceIn(lo, hi)
    fun posOf(v: Float) = ((v - lo) / step).coerceIn(0f, steps.toFloat())
    val defPos = posOf(def)
    // The default is a detent of its own, even off the grid (an as-shot white balance).
    fun valueAt(p: Float) = if (abs(p - defPos) < 0.5f) def else (lo + p.roundToInt() * step).coerceIn(lo, hi)

    var pos by remember { mutableFloatStateOf(posOf(value.toFloat())) }
    // Until the drum moves, the readout shows the model's value exactly, not its nearest step.
    var exact by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var fine by remember { mutableStateOf(false) }
    var lastIndex by remember { mutableStateOf(pos.roundToInt()) }
    var motion by remember { mutableStateOf<Job?>(null) }
    val onChangeLatest by rememberUpdatedState(onChange)
    val onDragLatest by rememberUpdatedState(onDrag)
    val recede = LocalSliderDrag.current
    // A value from elsewhere (undo, a preset, another control) moves the drum, except under the finger.
    LaunchedEffect(value, lo, hi, step) {
        if (!dragging && motion?.isActive != true) { pos = posOf(value.toFloat()); lastIndex = pos.roundToInt(); exact = true }
    }
    val current = if (exact) value.toFloat().coerceIn(lo, hi) else valueAt(pos)
    val changed = abs(current - def) > step / 2
    fun text(v: Float) = String.format(Locale.ROOT, format, v.toDouble())
    val isOn = { v: Float, every: Float -> abs(v / every - (v / every).roundToInt()) < 1e-3f }

    /** Called whenever the drum moves: a detent for each step crossed, the model told. */
    fun crossed(velocity: Float = 0f) {
        val i = if (abs(pos - defPos) < 0.5f) Int.MIN_VALUE else pos.roundToInt()
        if (i == lastIndex) return
        lastIndex = i
        exact = false
        val v = valueAt(pos)
        val cue = when {
            abs(v - def) < step / 2 -> Cue.Zero
            pos <= 0.5f || pos >= steps - 0.5f -> Cue.Limit
            isOn(v, major) || stops.any { abs(it.toFloat() - v) < step / 2 } -> Cue.DetentMajor
            else -> Cue.Detent
        }
        Feedback.play(view, cue, fast = abs(velocity) > 40f)
        onChangeLatest(v.toDouble())
    }
    /**
     * Runs a motion of the drum (coasting, settling, a tap's step, a reset). [onDrag] brackets it as it
     * brackets a drag: the dial counts as in use until it has stopped, so a caller that does slow work
     * when the user lets go (a RAW re-decode) does it once, at the end, not at every step on the way.
     */
    fun runMotion(block: suspend () -> Unit) {
        motion?.cancel()
        motion = scope.launch {
            onDragLatest(true); recede(true)
            try { block() } finally { onDragLatest(false); recede(false) }
        }
    }
    fun settleTo(target: Float, velocity: Float = 0f, fast: Boolean = false) = runMotion {
        val a = Animatable(pos)
        a.animateTo(target.coerceIn(0f, steps.toFloat()),
            if (fast) spring(dampingRatio = 0.6f, stiffness = 800f) else spring(dampingRatio = 0.8f, stiffness = 380f),
            initialVelocity = velocity) { pos = this.value; crossed() }
    }
    /** The step nearest [p], or the default when that is nearer. */
    fun snapTarget(p: Float): Float = if (abs(p - defPos) < 0.5f) defPos else p.roundToInt().toFloat().coerceIn(0f, steps.toFloat())
    fun reset() { if (changed) settleTo(defPos) }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // The name on the left, the value over the index, ↺ on the right once moved.
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            if (showName) Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = if (enabled) Palette.OnSurfaceSoft else Palette.OnSurfaceSoft.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp))
            Text(text(current), style = Readout.copy(fontSize = 19.sp, fontFamily = UiSans, fontWeight = FontWeight.Medium),
                color = Palette.OnSurface,
                modifier = Modifier.align(Alignment.Center).clip(Corners.s)
                    .then(if (onTypeValue != null && enabled) Modifier.clickable(role = Role.Button) { onTypeValue() }
                        .semantics { contentDescription = tr("输入{0}", label) } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 2.dp))
            if (changed && enabled) Box(
                Modifier.align(Alignment.CenterEnd).size(48.dp).clip(CircleShape)
                    .clickable(role = Role.Button) { reset() }
                    .semantics { contentDescription = tr("重置{0}", label) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val r = size.width * 0.34f
                    val c = Offset(size.width / 2, size.height / 2)
                    drawArc(Palette.OnSurfaceSoft, -20f, 280f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r),
                        style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
                    val head = Path().apply {
                        moveTo(c.x + r * 1.05f, c.y - r * 1.05f); lineTo(c.x + r * 1.05f, c.y - r * 0.05f); lineTo(c.x + r * 0.15f, c.y - r * 0.5f); close()
                    }
                    drawPath(head, Palette.OnSurfaceSoft)
                }
            }
        }

        val measurer = rememberTextMeasurer()
        val tickStyle = TextStyle(fontFamily = UiSans, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE8E4DC))
        val layouts = remember(measurer) { HashMap<String, TextLayoutResult>() }
        val decimals = decimalsOf(labelEvery)
        val bipolar = lo < 0f && hi > 0f
        fun tickText(v: Float): String {
            val n = String.format(Locale.ROOT, "%.${decimals}f", abs(v))
            return if (abs(v) < step / 2) "0" else if (bipolar) (if (v > 0) "+" else "−") + n else n
        }
        Canvas(
            Modifier.fillMaxWidth().height(70.dp)
                .semantics {
                    contentDescription = label + (ends?.let { "，${it.first} / ${it.second}" } ?: "")
                    stateDescription = text(current)
                    progressBarRangeInfo = ProgressBarRangeInfo(current, lo..hi)
                    if (!enabled) disabled()
                    else {
                        setProgress { target -> settleTo(posOf(target), fast = true); true }
                        customActions = listOf(CustomAccessibilityAction(tr("重置")) { reset(); true })
                    }
                }
                .pointerInput(enabled, lo, hi, step) {
                    if (!enabled) return@pointerInput
                    val px = dialStepPx(span, step, major)
                    val slop = viewConfiguration.touchSlop
                    val fineBand = 36.dp.toPx()
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        motion?.cancel()   // its finally reports onDrag(false); a drag reports true again
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var locked = false
                        var moved = false
                        var origin = pos
                        var originX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val dx = c.position.x - down.position.x
                            val dy = c.position.y - down.position.y
                            if (!locked) {
                                // The page's scroll took it first, or it went up or down: not ours.
                                if (c.isConsumed) return@awaitEachGesture
                                if (abs(dx) <= slop && abs(dy) <= slop) continue
                                moved = true
                                if (abs(dy) > abs(dx)) return@awaitEachGesture
                                locked = true
                                dragging = true
                                origin = pos; originX = c.position.x
                                onDragLatest(true); recede(true)
                                Feedback.play(view, Cue.Hold)
                            }
                            tracker.addPosition(c.uptimeMillis, c.position)
                            val away = c.position.y < -fineBand || c.position.y > size.height + fineBand
                            if (away != fine) {
                                fine = away; origin = pos; originX = c.position.x
                                Feedback.play(view, Cue.DetentMajor)
                            }
                            val next = (origin - (c.position.x - originX) / px * (if (fine) 0.2f else 1f)).coerceIn(0f, steps.toFloat())
                            pos = next
                            crossed()
                            c.consume()
                        }
                        if (locked) {
                            dragging = false
                            val v = if (fine) 0f else (-tracker.calculateVelocity().x / px).coerceIn(-120f, 120f)
                            fine = false
                            recede(false)
                            Feedback.play(view, Cue.Release)
                            // The drag's onDrag(false) comes when the drum has stopped (runMotion).
                            if (abs(v) > 6f) runMotion {
                                val a = Animatable(pos)
                                a.updateBounds(0f, steps.toFloat())
                                val r = a.animateDecay(v, exponentialDecay(frictionMultiplier = 2.6f)) { pos = this.value; crossed(velocity) }
                                if (r.endReason == AnimationEndReason.BoundReached) Feedback.play(view, Cue.Limit)
                                a.animateTo(snapTarget(pos), spring(dampingRatio = 0.8f, stiffness = 380f)) { pos = this.value; crossed() }
                            } else settleTo(snapTarget(pos))
                        } else if (!moved) {
                            // A tap steps one detent towards the side tapped; a second tap soon after resets.
                            val now = down.uptimeMillis
                            if (now - lastTap < viewConfiguration.doubleTapTimeoutMillis) { lastTap = 0L; reset() }
                            else {
                                lastTap = now
                                val dir = if (down.position.x > size.width / 2f) 1 else -1
                                settleTo(snapTarget(pos + dir), fast = true)
                            }
                        }
                    }
                },
        ) {
            val w = size.width
            val top = 8.dp.toPx()
            // A drum the thumb lands on without aiming: 58 dp, the whole width.
            val dh = 58.dp.toPx()
            val cx = w / 2f
            val radius = w * 0.58f
            val px = dialStepPx(span, step, major)
            val p = pos
            val corner = CornerRadius(10.dp.toPx())
            // The drum: warm graphite, lit from above.
            clipRect(0f, top, w, top + dh) {
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3B3A36), Color(0xFF2B2A27), Color(0xFF1F1E1C), Color(0xFF121110)),
                    startY = top, endY = top + dh), Offset(0f, top), Size(w, dh), corner)
                // Knurling round both rims, turning with the drum.
                val pitch = 3.6.dp.toPx()
                val shift = (p * px) % pitch
                for (k in -120..120) {
                    val a = (k * pitch - shift) / radius
                    if (abs(a) >= PI / 2) continue
                    val x = cx + radius * sin(a).toFloat()
                    val cs = cos(a).toFloat()
                    for (y0 in floatArrayOf(top, top + dh - 4.dp.toPx())) {
                        drawRect(Color(0xFFFFF8EB).copy(alpha = 0.11f * cs), Offset(x, y0), Size(0.7f * cs + 0.3f, 4.dp.toPx()))
                        drawRect(Color.Black.copy(alpha = 0.5f * cs), Offset(x + 0.9f * cs, y0), Size(0.7f * cs + 0.3f, 4.dp.toPx()))
                    }
                }
                val first = (p - radius / px).toInt() - 1
                val last = (p + radius / px).toInt() + 1
                // The direction as colour, when there is one.
                if (tint != null) for (q in maxOf(0, first)..minOf(steps - 1, last)) {
                    val a = (q - p) * px / radius
                    if (abs(a) >= PI / 2) continue
                    val a2 = minOf(((q + 1 - p) * px / radius).toDouble(), PI / 2)
                    val x = cx + radius * sin(a).toFloat()
                    val x2 = cx + radius * sin(a2).toFloat()
                    val cs = cos(a).toFloat()
                    val col = tint(q / steps.toFloat())
                    drawRect(col.copy(alpha = 0.09f * cs), Offset(x, top + 4.dp.toPx()), Size(maxOf(0.5f, x2 - x + 0.4f), dh - 8.dp.toPx()))
                    drawRect(col.copy(alpha = 0.72f * cs), Offset(x, top + dh - 9.dp.toPx()), Size(maxOf(0.5f, x2 - x + 0.4f), 3.dp.toPx()))
                }
                // The ticks, cut and filled; labels on every [labelEvery].
                for (q in maxOf(0, first)..minOf(steps, last)) {
                    val a = (q - p) * px / radius
                    if (abs(a) >= PI / 2 * 0.98) continue
                    val x = cx + radius * sin(a).toFloat()
                    val cs = cos(a).toFloat()
                    val v = lo + q * step
                    val isMajor = isOn(v, major)
                    val isHalf = !isMajor && isOn(v, major / 2)
                    val len = (if (isMajor) 19 else if (isHalf) 13 else 8).dp.toPx()
                    val wd = (if (isMajor) 1.6f else 1.05f) * (0.4f + 0.6f * cs) * density
                    val y0 = top + 6.dp.toPx()
                    drawRect(Color.Black.copy(alpha = 0.75f * cs), Offset(x + 0.7f * density, y0 + 0.7f * density), Size(wd, len))
                    drawRect(Color(0xFFEEEAE2).copy(alpha = (if (isMajor) 0.95f else 0.55f) * cs.pow(1.6f)), Offset(x, y0), Size(wd, len))
                    if (isMajor && isOn(v, labelEvery)) {
                        val t = tickText(v)
                        val layout = layouts.getOrPut(t) { measurer.measure(t, tickStyle) }
                        val labelY = top + 40.dp.toPx()
                        withTransform({ scale(cs, 1f, Offset(x, labelY)) }) {
                            drawText(layout, color = Color(0xFFE8E4DC).copy(alpha = 0.88f * cs * cs),
                                topLeft = Offset(x - layout.size.width / 2f, labelY - layout.size.height / 2f))
                        }
                    }
                    val stopDot = 1.7.dp.toPx() * cs + 0.4f
                    if (stops.any { abs(it.toFloat() - v) < step / 2 }) drawCircle(Color(0xFFEEEAE2).copy(alpha = 0.9f * cs), stopDot, Offset(x + 0.5f, top + dh - 13.dp.toPx()))
                    if (abs(v - def) < step / 2) {
                        val tri = Path().apply {
                            moveTo(x - 3.dp.toPx() * cs, top + dh - 4.dp.toPx()); lineTo(x + 3.dp.toPx() * cs, top + dh - 4.dp.toPx()); lineTo(x, top + dh - 8.dp.toPx()); close()
                        }
                        drawPath(tri, Color(0xFFEEEAE2).copy(alpha = 0.9f * cs))
                    }
                }
                // A stop at each end of the range: a shadow and a line, so the end is seen, not only felt.
                for (q in intArrayOf(0, steps)) {
                    val a = (q - p) * px / radius
                    if (abs(a) >= PI / 2 * 0.98) continue
                    val x = cx + radius * sin(a).toFloat()
                    val cs = cos(a).toFloat()
                    val out = if (q == 0) -1f else 1f
                    val ww = 14.dp.toPx() * cs
                    drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startX = x, endX = x + out * ww),
                        Offset(minOf(x, x + out * ww), top + 3.dp.toPx()), Size(ww, dh - 6.dp.toPx()))
                    drawRect(Color(0xFFEEEAE2).copy(alpha = 0.5f * cs), Offset(x - 0.6f * density, top + 4.dp.toPx()), Size(1.2f * density, dh - 8.dp.toPx()))
                }
                // The cylinder turning away at both ends, and a highlight along its top.
                drawRect(Brush.horizontalGradient(0f to Color(0xF0090807), 0.17f to Color(0x57090807), 0.42f to Color.Transparent,
                    0.58f to Color.Transparent, 0.83f to Color(0x66090807), 1f to Color(0xF5090807)), Offset(0f, top), Size(w, dh))
                drawRect(Brush.verticalGradient(listOf(Color(0x2BFFFAF0), Color.Transparent), startY = top, endY = top + 7.dp.toPx()),
                    Offset(0f, top), Size(w, 7.dp.toPx()))
                if (dragging) drawRect(Color(0x0DFFF6E6), Offset(0f, top), Size(w, dh))
            }
            drawRoundRect(Color(0x0FFFF6E6), Offset(0.5f, top + 0.5f), Size(w - 1f, dh - 1f), corner, style = Stroke(1f))
            // The index: fixed and warm, a mark above and a short line into the window.
            val ink = if (enabled) Palette.Index else Palette.Outline
            val mark = Path().apply {
                moveTo(cx - 4.5.dp.toPx(), top - 5.dp.toPx()); lineTo(cx + 4.5.dp.toPx(), top - 5.dp.toPx()); lineTo(cx, top + 1.5.dp.toPx()); close()
            }
            drawPath(mark, ink)
            drawRect(ink.copy(alpha = 0.8f), Offset(cx - 0.6f * density, top + 2.dp.toPx()), Size(1.2f * density, 22.dp.toPx()))
            if (fine) {
                val t = tr("微调")
                val layout = layouts.getOrPut("\u0000$t") { measurer.measure(t, tickStyle) }
                drawText(layout, color = Palette.OnSurface, topLeft = Offset(cx - layout.size.width / 2f, top + dh + 1.dp.toPx()))
            }
            if (!enabled) drawRect(Palette.Panel.copy(alpha = 0.55f), Offset(0f, 0f), size)
        }
        if (!enabled && disabledReason.isNotBlank()) Hint(disabledReason)
    }
}

/** The colour bands of the dials that have a direction (left end first). */
internal object DialTints {
    private val Amber = Color(0xFFDEAA68)
    private val Blue = Color(0xFF769ED6)
    private val Magenta = Color(0xFFC27CB2)
    private val Green = Color(0xFF7CB284)
    /** Cool to warm: colour temperature. */
    val coolWarm: (Float) -> Color = { t -> androidx.compose.ui.graphics.lerp(Blue, Amber, t) }
    /** Warm to cool: the enlarger's yellow filter (偏黄 to 偏蓝). */
    val warmCool: (Float) -> Color = { t -> androidx.compose.ui.graphics.lerp(Amber, Blue, t) }
    /** Green to magenta: tint. */
    val greenMagenta: (Float) -> Color = { t -> androidx.compose.ui.graphics.lerp(Green, Magenta, t) }
    /** Magenta to green: the enlarger's magenta filter (偏品红 to 偏绿). */
    val magentaGreen: (Float) -> Color = { t -> androidx.compose.ui.graphics.lerp(Magenta, Green, t) }
}

/** A dial's step and its labelled division. */
internal class DialScale(val step: Float, val major: Float)

/**
 * A round labelled division, about eight across the range, then the finest step that divides it,
 * shows in the readout's last digit and leaves at most 320 detents. So a step never lands on odd
 * numbers (0.03 EV), and the labels always fall on ticks.
 */
internal fun dialScale(span: Float, format: String): DialScale {
    val decimals = Regex("""\.(\d+)f""").find(format)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val unit = 10f.pow(-decimals)
    fun multiple(x: Float, of: Float) = abs(x / of - (x / of).roundToInt()) < 1e-3f
    val raw = span / 8f
    val mag = 10f.pow(floor(log10(raw)))
    val nice = listOf(0.1f, 0.2f, 0.25f, 0.5f, 1f, 2f, 2.5f, 5f, 10f, 20f).map { it * mag }.filter { multiple(it, unit) }
    val major = nice.minByOrNull { abs(it - raw) } ?: unit * maxOf(1, (raw / unit).roundToInt())
    for (n in intArrayOf(25, 20, 10, 5, 4, 2, 1)) {
        val st = major / n
        if (multiple(st, unit) && span / st <= 320f) return DialScale(st, major)
    }
    return DialScale(major, major)
}

/** Width of one step at the drum's middle: a labelled division is about 104 dp, a step 3 to 9 dp. */
private fun androidx.compose.ui.unit.Density.dialStepPx(span: Float, step: Float, major: Float): Float =
    (104.dp.toPx() / (major / step)).coerceIn(3.dp.toPx(), 9.dp.toPx())

private fun decimalsOf(v: Float): Int {
    var d = 0
    var x = v
    while (d < 3 && abs(x - x.roundToInt()) > 1e-3f) { x *= 10; d++ }
    return d
}
