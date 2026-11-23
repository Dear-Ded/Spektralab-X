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
    fun set(next: Look) = vm.update(e.copy(look = next))

    // One segment on screen at a time (UI2.md 2.4); a dot marks the ones changed.
    val segments = ADJUST_SEGMENTS
    val edited = adjustEdited(l)
    fun resetSegment(i: Int) = set(resetAdjustSegment(l, i))
    // The segment lives in the editor's nav, so 修改记录 can open this page on one.
    val nav = LocalEditorNav.current
    var seg by nav::adjustSeg

    // One reset, for the segment on screen; the dots on the pills say which ones are changed,
    // and 重置全部 in the top bar takes everything back.
    PanelHeader(
        title = tr("调整"),
        resetLabel = tr("重置"), changed = edited[seg], enabled = enabled, onReset = { resetSegment(seg) },
    )
    PillRow {
        segments.forEachIndexed { i, name ->
            SelectPill(selected = seg == i, onClick = { seg = i },
                label = { Text(name) }, enabled = enabled, edited = edited[i])
        }
    }

    // ui-polish: 光线 and 色彩 are a row of knobs and one dial (KnobRow.kt), all in reach.
    when (seg) {
        0 -> KnobRow("light", listOf(
            Knob("exposure", tr("曝光"), l.exposure, -3.0, 3.0, 0.0, "%+.2f EV", short = ::signed1, zone = Zones.all) { set(l.copy(exposure = it)) },
            Knob("contrast", tr("对比度"), l.contrast, -50.0, 50.0, 0.0, "%+.0f", zone = Zones.contrast) { set(l.copy(contrast = it)) },
            Knob("brightness", tr("亮度"), l.brightness, -50.0, 50.0, 0.0, "%+.0f", zone = Zones.mids) { set(l.copy(brightness = it)) },
            Knob("highlights", tr("高光"), l.highlights, -100.0, 100.0, 0.0, "%+.0f", zone = Zones.highlights, divider = true) { set(l.copy(highlights = it)) },
            Knob("shadows", tr("阴影"), l.shadows, -100.0, 100.0, 0.0, "%+.0f", zone = Zones.shadows) { set(l.copy(shadows = it)) },
            Knob("black", tr("黑场"), l.blackPoint, 0.0, 50.0, 0.0, "%.0f", short = ::plain0, zone = Zones.blacks) { set(l.copy(blackPoint = it)) },
            Knob("white", tr("白场"), l.whitePoint, 0.0, 50.0, 0.0, "%.0f", short = ::plain0, zone = Zones.whites) { set(l.copy(whitePoint = it)) },
        ), enabled, ui.image)
        1 -> KnobRow("colour", listOf(
            Knob("temperature", tr("色温"), l.temperature, -100.0, 100.0, 0.0, "%+.0f", tint = DialTints.coolWarm) { set(l.copy(temperature = it)) },
            Knob("tint", tr("色调"), l.tint, -100.0, 100.0, 0.0, "%+.0f", tint = DialTints.greenMagenta) { set(l.copy(tint = it)) },
            Knob("saturation", tr("饱和度"), l.saturation, -100.0, 100.0, 0.0, "%+.0f", divider = true) { set(l.copy(saturation = it)) },
            Knob("vibrance", tr("自然饱和度"), l.vibrance, -100.0, 100.0, 0.0, "%+.0f") { set(l.copy(vibrance = it)) },
        ), enabled, ui.image)
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
                // Each dial shows where it goes: the hue either side of this colour, grey to the colour,
                // dark through it to light.
                val c = Color(Look.BANDS[band].second)
                val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(Look.BANDS[band].second.toInt(), it) }
                val hueTint: (Float) -> Color = { t -> Color.hsv(((hsv[0] + (t - 0.5f) * 60f) % 360f + 360f) % 360f, 0.5f, 0.84f) }
                KnobRow("band$band", listOf(
                    Knob("hue", tr("色相"), b.hue, -100.0, 100.0, 0.0, "%+.0f", tint = hueTint) { set(l.withBand(band, b.copy(hue = it))) },
                    Knob("sat", tr("饱和度"), b.sat, -100.0, 100.0, 0.0, "%+.0f", tint = { t -> androidx.compose.ui.graphics.lerp(Color(0xFF807E79), c, t) }) { set(l.withBand(band, b.copy(sat = it))) },
                    Knob("lum", tr("明亮度"), b.lum, -100.0, 100.0, 0.0, "%+.0f", tint = { t ->
                        if (t < 0.5f) androidx.compose.ui.graphics.lerp(Color(0xFF22201D), c, t * 2) else androidx.compose.ui.graphics.lerp(c, Color(0xFFE8E4DA), (t - 0.5f) * 2) }) { set(l.withBand(band, b.copy(lum = it))) },
                ), enabled, ui.image)
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
            // The range only does something once there is a vignette, so it is only there then.
            Expandable(l.vignette != 0.0) {
                LabeledSlider(tr("暗角范围"), l.vignetteMid, 0.0, 100.0, 50.0, enabled, "%.0f") { set(l.copy(vignetteMid = it)) }
            }
        }
    }
    MaskSection(ui, vm, enabled)   // edit-effects: 局部蒙版 (MaskPanel.kt)
}

/** The 调整 segments, in page order (UI2.md 2.4). */
internal val ADJUST_SEGMENTS get() = listOf(tr("光线"), tr("色彩"), tr("混色"), tr("曲线"), tr("调色盘"), tr("暗角"))

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

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Accent,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun Adj(label: String, value: Double, min: Double, max: Double, enabled: Boolean,
                tint: ((Float) -> androidx.compose.ui.graphics.Color)? = null, onChange: (Double) -> Unit) {
    LabeledSlider(label, value, min, max, 0.0, enabled, "%+.0f", tint = tint, onChange = onChange)
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
    }
}

private fun signed1(v: Double) = String.format(java.util.Locale.ROOT, "%+.1f", v).replace("-", "−")
private fun plain0(v: Double) = String.format(java.util.Locale.ROOT, "%.0f", v)
