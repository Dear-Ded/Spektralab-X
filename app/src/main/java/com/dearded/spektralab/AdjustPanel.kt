package com.dearded.spektralab

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip

/**
 * The 调整 page: Layer 2 (Look), the adjustments to the finished print. Every
 * slider re-packs the last render (a few ms), so it follows the finger without
 * developing again. Grouped the way Lightroom and Capture One group them.
 */
@Composable
internal fun AdjustPage(ui: UiState, vm: EditorViewModel, enabled: Boolean) {
    val e = ui.edit
    val l = e.look
    val n = Look.NEUTRAL
    fun set(next: Look) = vm.update(e.copy(look = next))

    // One segment on screen at a time (UI2.md 2.4); a dot marks the ones changed.
    val segments = ADJUST_SEGMENTS
    val edited = adjustEdited(l)
    fun resetSegment(i: Int) = set(resetAdjustSegment(l, i))
    // The segment lives in the editor's nav, so 修改记录 can open this page on one.
    val nav = LocalEditorNav.current
    var seg by nav::adjustSeg

    // UI2.md 7.2 point 2: title · how many segments changed, the reset on the right.
    val changedCount = edited.count { it }
    PanelHeader(
        title = tr("调整"),
        summary = if (changedCount == 0) "" else tr("已改 {0} 段", changedCount),
        // 无 is the panel's reset, not a segment: one row, one thing selected (ui_ai_flavor_audit).
        resetLabel = tr("重置调整"), changed = !l.neutral, enabled = enabled, onReset = { set(Look.NEUTRAL) },
    )
    PillRow {
        segments.forEachIndexed { i, name ->
            SelectPill(selected = seg == i, onClick = { seg = i },
                label = { Text(name) }, enabled = enabled, edited = edited[i])
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (SEGMENT_HINTS[seg].isEmpty()) Spacer(Modifier.weight(1f)) else FoldHint(SEGMENT_HINTS[seg], Modifier.weight(1f))
        if (edited[seg]) QuietButton(tr("重置{0}", segments[seg]), enabled = enabled) { resetSegment(seg) }
    }

    // UI2.md 2.4: at most three sliders in reach per segment; the rest behind 更多参数.
    val expandAll = rememberExpandAll()
    var more by remember(seg) { mutableStateOf(expandAll) }
    when (seg) {
        0 -> {
            // The 双击归零 hint sits on the first slider a newcomer meets, one tap from the photo.
            Box(Modifier.coachAnchor(CoachStep.RESET)) {
                LabeledSlider(tr("曝光"), l.exposure, -3.0, 3.0, 0.0, enabled, "%+.2f EV") { set(l.copy(exposure = it)) }
            }
            Adj(tr("对比度"), l.contrast, -50.0, 50.0, enabled) { set(l.copy(contrast = it)) }
            Adj(tr("亮度"), l.brightness, -50.0, 50.0, enabled) { set(l.copy(brightness = it)) }
            MoreRow(open = more, edited = l.highlights != n.highlights || l.shadows != n.shadows ||
                l.blackPoint != n.blackPoint || l.whitePoint != n.whitePoint) { more = !more }
            if (!more) Hint(moreSummary(tr("高光、阴影、黑场、白场"),
                tr("高光") to l.highlights.signedOrNull(n.highlights), tr("阴影") to l.shadows.signedOrNull(n.shadows),
                tr("黑场") to l.blackPoint.signedOrNull(n.blackPoint), tr("白场") to l.whitePoint.signedOrNull(n.whitePoint)))
            Expandable(more, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(RowGap)) {
                Adj(tr("高光"), l.highlights, -100.0, 100.0, enabled) { set(l.copy(highlights = it)) }
                Adj(tr("阴影"), l.shadows, -100.0, 100.0, enabled) { set(l.copy(shadows = it)) }
                Adj(tr("黑场"), l.blackPoint, 0.0, 50.0, enabled) { set(l.copy(blackPoint = it)) }
                Adj(tr("白场"), l.whitePoint, 0.0, 50.0, enabled) { set(l.copy(whitePoint = it)) }
            }
        }
        1 -> {
            Adj(tr("色温"), l.temperature, -100.0, 100.0, enabled) { set(l.copy(temperature = it)) }
            Adj(tr("色调"), l.tint, -100.0, 100.0, enabled) { set(l.copy(tint = it)) }
            Adj(tr("饱和度"), l.saturation, -100.0, 100.0, enabled) { set(l.copy(saturation = it)) }
            MoreRow(open = more, edited = l.vibrance != n.vibrance) { more = !more }
            if (!more) Hint(moreSummary(tr("自然饱和度"), tr("自然饱和度") to l.vibrance.signedOrNull(n.vibrance)))
            if (more) Adj(tr("自然饱和度"), l.vibrance, -100.0, 100.0, enabled) { set(l.copy(vibrance = it)) }
        }
        2 -> {
            // Two tools that never share the screen: one colour at a time, or one tonal zone.
            var tool by remember { mutableIntStateOf(0) }
            Segmented(listOf(tr("单色调整"), tr("色彩平衡")), tool, enabled) { tool = it }
            if (tool == 0) {
                var band by remember { mutableIntStateOf(0) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Look.BANDS.forEachIndexed { i, (bandName, argb) ->
                        val on = band == i
                        val bandEdited = !l.hsl.getOrElse(i) { Band() }.neutral
                        // A 48 dp target around a 28 dp swatch; the chosen one wears a white ring
                        // outside a gap, the same signal as a picked card.
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .selectable(selected = on, enabled = enabled, role = Role.Tab) { band = i }
                                .semantics { contentDescription = bandName },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(40.dp).border(2.dp, if (on) Palette.Accent else Color.Transparent, CircleShape))
                            Box(Modifier.size(28.dp).background(Color(argb), CircleShape))
                            if (bandEdited && !on) EditedDot(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                    }
                }
                val b = l.hsl.getOrElse(band) { Band() }
                val name = Look.BANDS[band].first
                Adj(tr("{0} · 色相", name), b.hue, -100.0, 100.0, enabled) { set(l.withBand(band, b.copy(hue = it))) }
                Adj(tr("{0} · 饱和度", name), b.sat, -100.0, 100.0, enabled) { set(l.withBand(band, b.copy(sat = it))) }
                Adj(tr("{0} · 明亮度", name), b.lum, -100.0, 100.0, enabled) { set(l.withBand(band, b.copy(lum = it))) }
            } else {
                var zone by remember { mutableIntStateOf(0) }
                Segmented(listOf(tr("全局"), tr("阴影"), tr("中间调"), tr("高光")), zone, enabled) { zone = it }
                val z = when (zone) { 1 -> l.shadowZone; 2 -> l.midZone; 3 -> l.highlightZone; else -> l.master }
                fun setZone(next: Zone) = set(when (zone) {
                    1 -> l.copy(shadowZone = next); 2 -> l.copy(midZone = next); 3 -> l.copy(highlightZone = next)
                    else -> l.copy(master = next)
                })
                LabeledSlider(tr("色相"), z.hue, 0.0, 360.0, 0.0, enabled, "%.0f°") { setZone(z.copy(hue = it)) }
                Adj(tr("强度"), z.sat, 0.0, 100.0, enabled) { setZone(z.copy(sat = it)) }
                Adj(tr("明亮度"), z.lum, -100.0, 100.0, enabled) { setZone(z.copy(lum = it)) }
            }
        }
        3 -> CurveEditor(l.curves, ui.image, enabled, onDrag = vm::setInteracting) { set(l.copy(curves = it)) }
        4 -> PalettePad(l, enabled, onDrag = vm::setInteracting) { set(it) }
        else -> {
            Adj(tr("褪色"), l.fade, 0.0, 100.0, enabled) { set(l.copy(fade = it)) }
            Adj(tr("暗角"), l.vignette, -100.0, 100.0, enabled) { set(l.copy(vignette = it)) }
            LabeledSlider(tr("暗角范围"), l.vignetteMid, 0.0, 100.0, 50.0, enabled && l.vignette != 0.0, "%.0f",
                disabledReason = if (enabled && l.vignette == 0.0) tr("先调暗角，范围才有作用。") else "") {
                set(l.copy(vignetteMid = it))
            }
        }
    }
    MaskSection(ui, vm, enabled)   // edit-effects: 局部蒙版 (MaskPanel.kt)
}

/** The 调整 segments, in page order (UI2.md 2.4). */
internal val ADJUST_SEGMENTS get() = listOf(tr("光线"), tr("色彩"), tr("分色"), tr("曲线"), tr("调色盘"), tr("暗角"))

/** Which [ADJUST_SEGMENTS] differ from neutral; shared by the page's dots and 修改记录. */
internal fun adjustEdited(l: Look): List<Boolean> {
    val n = Look.NEUTRAL
    return listOf(
        l.exposure != n.exposure || l.contrast != n.contrast || l.brightness != n.brightness ||
            l.highlights != n.highlights || l.shadows != n.shadows || l.blackPoint != n.blackPoint ||
            l.whitePoint != n.whitePoint,
        l.temperature != n.temperature || l.tint != n.tint || l.saturation != n.saturation || l.vibrance != n.vibrance,
        l.hsl != n.hsl || l.master != n.master || l.shadowZone != n.shadowZone ||
            l.midZone != n.midZone || l.highlightZone != n.highlightZone,
        l.curves != n.curves,
        l.tone != l.toneHome || l.colour != l.colourHome,
        l.fade != n.fade || l.vignette != n.vignette || l.vignetteMid != n.vignetteMid,
    )
}

/** [l] with segment [i] back at neutral (调色盘 back to its style's own point). */
internal fun resetAdjustSegment(l: Look, i: Int): Look {
    val n = Look.NEUTRAL
    return when (i) {
        0 -> l.copy(exposure = n.exposure, contrast = n.contrast, brightness = n.brightness, highlights = n.highlights,
            shadows = n.shadows, blackPoint = n.blackPoint, whitePoint = n.whitePoint)
        1 -> l.copy(temperature = n.temperature, tint = n.tint, saturation = n.saturation, vibrance = n.vibrance)
        2 -> l.copy(hsl = n.hsl, master = n.master, shadowZone = n.shadowZone, midZone = n.midZone,
            highlightZone = n.highlightZone)
        3 -> l.copy(curves = n.curves)
        4 -> l.copy(tone = l.toneHome, colour = l.colourHome)
        else -> l.copy(fade = n.fade, vignette = n.vignette, vignetteMid = n.vignetteMid)
    }
}

/** A line only where the gesture cannot be seen (ui_ai_flavor_audit): the curve and the pad. */
private val SEGMENT_HINTS get() = listOf(
    "",
    "",
    "",
    tr("按住点拖动，双击删除。"),
    tr("左右浓淡，上下明暗；双击回到默认点。"),
    "",
)

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Accent,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun Adj(label: String, value: Double, min: Double, max: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    LabeledSlider(label, value, min, max, 0.0, enabled, "%+.0f", onChange = onChange)
}

/** A column of controls for a page; keeps AnimatedContent's pages the same shape. */
@Composable
internal fun PageColumn(content: @Composable () -> Unit) = Column { content() }

/** No photo yet: one obvious action in the middle, not directions to a corner. */
@Composable
internal fun EmptyState(onPick: () -> Unit, onLightTable: () -> Unit = {}) {
    // UI2.md 1.1: one near-white primary action in the middle, the light table as the quiet second.
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f, Motion.fastSpatial(), label = "open")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        androidx.compose.material3.Button(
            onClick = onPick, interactionSource = press,
            modifier = Modifier.height(52.dp).widthIn(min = 200.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        ) {
            Text(tr("打开照片"), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onLightTable, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(tr("观片台"), color = Palette.OnSurface.copy(alpha = 0.85f))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            tr("支持 JPEG、HEIC、PNG 和相机 RAW"),
            style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
        )
    }
}
