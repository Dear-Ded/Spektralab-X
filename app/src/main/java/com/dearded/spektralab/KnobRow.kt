package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * A page of related values as one row of knobs and one dial under it (ui-polish; preview KnobRow).
 * Each knob shows its value and how far it has moved from its default; tapping one puts it on the
 * dial. All of a group is in reach at once, so nothing hides behind 更多参数, and only one dial
 * takes the height. A [Knob] with a [Knob.zone] also gets a tone strip over the dial: the photo's
 * tones, with the part that control works on lit.
 */
internal class Knob(
    val key: String, val label: String, val value: Double, val min: Double, val max: Double, val default: Double,
    val format: String,
    /** The short value on the knob, e.g. +12 or −0.3. */
    val short: (Double) -> String = { v -> String.format(Locale.ROOT, "%+.0f", v).replace("-", "−") },
    val tint: ((Float) -> Color)? = null,
    /** Which tones it works on, 0 (black) to 1 (white), as a weight 0..1; null for no tone strip. */
    val zone: ((Float) -> Float)? = null,
    /** A wider gap before this one in the row: a new group starts. */
    val divider: Boolean = false,
    val onChange: (Double) -> Unit,
)

@Composable
internal fun KnobRow(id: String, knobs: List<Knob>, enabled: Boolean, image: Bitmap?, onDrag: (Boolean) -> Unit = {}) {
    val view = LocalView.current
    var picked by rememberSaveable(id) { mutableStateOf(knobs.first().key) }
    val current = knobs.firstOrNull { it.key == picked } ?: knobs.first()
    Column(Modifier.fillMaxWidth()) {
        ParamCarousel(knobs, current.key, enabled) { key -> if (picked != key) { picked = key; Feedback.play(view, Cue.Segment) } }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(current.key, transitionSpec = { fadeIn(Motion.defaultEffects()) togetherWith fadeOut(Motion.fastEffects()) }, label = "knob") { key ->
            val k = knobs.firstOrNull { it.key == key } ?: return@AnimatedContent
            Column {
                k.zone?.let { ToneStrip(image, it) }
                LabeledSlider(k.label, k.value, k.min, k.max, k.default, enabled, k.format, onDrag = onDrag, tint = k.tint, onChange = k.onChange)
            }
        }
    }
}

/**
 * The parameters as a camera's mode dial: names in a row that scrolls under the thumb and snaps,
 * the one in the middle being the one on the dial below. Each name is a big target (52 dp tall,
 * 76 dp at least), its value under it once moved. Swipe to the next, or tap one to bring it to the
 * middle. The selection is chosen by where the row stops, so a fling lands on a name, never between.
 */
@Composable
private fun ParamCarousel(knobs: List<Knob>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, knobs.indexOfFirst { it.key == selected }))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state, androidx.compose.foundation.gestures.snapping.SnapPosition.Center)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = (maxWidth - 76.dp) / 2
        // The item nearest the middle, as the row moves.
        val centred by androidx.compose.runtime.remember {
            androidx.compose.runtime.derivedStateOf {
                val info = state.layoutInfo
                val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index
            }
        }
        androidx.compose.runtime.LaunchedEffect(centred) { centred?.let { i -> knobs.getOrNull(i)?.let { onSelect(it.key) } } }
        androidx.compose.runtime.LaunchedEffect(selected) {
            val i = knobs.indexOfFirst { it.key == selected }
            if (i >= 0 && i != centred && !state.isScrollInProgress) state.animateScrollToItem(i)
        }
        androidx.compose.foundation.lazy.LazyRow(
            state = state, flingBehavior = fling, userScrollEnabled = enabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = side),
            modifier = Modifier.fillMaxWidth().height(56.dp)
                // The row's ends fade: there is more either side.
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val f = 48.dp.toPx()
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, f), size = Size(f, size.height), blendMode = BlendMode.DstIn)
                    drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - f, size.width),
                        topLeft = Offset(size.width - f, 0f), size = Size(f, size.height), blendMode = BlendMode.DstIn)
                },
        ) {
            itemsIndexed(knobs, key = { _, k -> k.key }) { i, k ->
                val on = k.key == selected
                val changed = abs(k.value - k.default) > 1e-6
                Column(
                    Modifier.padding(start = if (k.divider) 14.dp else 0.dp).widthIn(min = 76.dp).height(56.dp).clip(Corners.l)
                        .selectable(on, enabled = enabled, role = Role.Tab) { scope.launch { state.animateScrollToItem(i) } }
                        .semantics { contentDescription = k.label; stateDescription = String.format(Locale.ROOT, k.format, k.value) }
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(k.label, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (on) Palette.OnSurface else Palette.OnSurfaceSoft, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    // Moved: its value; at its default: a short mark; the one on the dial: underlined.
                    Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                        if (changed) Text(k.short(k.value), style = Readout.copy(fontSize = 12.sp),
                            color = if (on) Palette.Accent else Palette.OnSurfaceSoft)
                        else Box(Modifier.size(if (on) 18.dp else 4.dp, if (on) 2.dp else 4.dp)
                            .background(if (on) Palette.Accent else Palette.Track, if (on) Corners.xs else androidx.compose.foundation.shape.CircleShape))
                    }
                }
            }
        }
    }
}

/**
 * The photo's tones (a luma histogram, square-root scaled so the quiet ends still show), the tones
 * [zone] works on lit, the rest left grey. Clipping at either end gets a small coloured mark.
 */
@Composable
private fun ToneStrip(image: Bitmap?, zone: (Float) -> Float) {
    val hist by produceState<Histogram?>(null, image) {
        value = image?.let { b -> withContext(Dispatchers.Default) { Histogram.of(b) } }
    }
    val h = hist ?: run { Spacer(Modifier.height(30.dp)); return }
    Canvas(Modifier.fillMaxWidth().height(30.dp).padding(bottom = 2.dp).semantics { }) {
        val bins = h.y
        val n = bins.size
        val mx = bins.maxOf { sqrt(it.toFloat()) }.coerceAtLeast(1f)
        val bw = size.width / n
        val dim = Color(0xFF6E6B65)
        val lit = Color(0xFFECE2D0)
        for (i in 0 until n) {
            val x = (i + 0.5f) / n
            val w = zone(x).coerceIn(0f, 1f)
            val hh = (sqrt(bins[i].toFloat()) / mx * (size.height - 6.dp.toPx())).coerceAtLeast(1f)
            drawRect(lerp(dim, lit, w).copy(alpha = 0.32f + 0.58f * w), Offset(i * bw + 0.3f, size.height - 2f - hh), Size(bw - 0.6f, hh))
        }
        drawRect(Color(0x14FFF6E6), Offset(0f, size.height - 1f), Size(size.width, 1f))
        if (h.clippedLow > 0.02f) drawRect(Color(0xFF8FB3D9), Offset(0f, size.height - 3.dp.toPx()), Size(3.dp.toPx(), 3.dp.toPx()))
        if (h.clippedHigh > 0.02f) drawRect(Color(0xFFE0956F), Offset(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()), Size(3.dp.toPx(), 3.dp.toPx()))
    }
}

/** Tone zones for the 光线 knobs (preview ToneStrip weights). */
internal object Zones {
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return v * v * (3 - 2 * v) }
    val all: (Float) -> Float = { 1f }
    val contrast: (Float) -> Float = { x -> abs(x - 0.5f) * 2f }
    val mids: (Float) -> Float = { x -> 1f - abs(x - 0.5f) * 2f }
    val highlights: (Float) -> Float = { x -> smooth(0.5f, 0.85f, x) }
    val shadows: (Float) -> Float = { x -> 1f - smooth(0.15f, 0.5f, x) }
    val blacks: (Float) -> Float = { x -> 1f - smooth(0f, 0.18f, x) }
    val whites: (Float) -> Float = { x -> smooth(0.82f, 1f, x) }
}
