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
    val segments = listOf("光线", "色彩", "分色", "曲线", "调色盘", "质感")
    val edited = listOf(
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
    fun resetSegment(i: Int) = set(when (i) {
        0 -> l.copy(exposure = n.exposure, contrast = n.contrast, brightness = n.brightness, highlights = n.highlights,
            shadows = n.shadows, blackPoint = n.blackPoint, whitePoint = n.whitePoint)
        1 -> l.copy(temperature = n.temperature, tint = n.tint, saturation = n.saturation, vibrance = n.vibrance)
        2 -> l.copy(hsl = n.hsl, master = n.master, shadowZone = n.shadowZone, midZone = n.midZone,
            highlightZone = n.highlightZone)
        3 -> l.copy(curves = n.curves)
        4 -> l.copy(tone = l.toneHome, colour = l.colourHome)
        else -> l.copy(fade = n.fade, vignette = n.vignette, vignetteMid = n.vignetteMid)
    })
    var seg by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }

    Text(
        "作用在冲好的照片上，像扫描后再微调；拖动即时生效，不重新冲洗。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.forEachIndexed { i, name ->
            SelectPill(selected = seg == i, onClick = { seg = i },
                label = { Text(if (edited[i]) "$name •" else name) }, enabled = enabled)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(SEGMENT_HINTS[seg], style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = { resetSegment(seg) }, enabled = enabled && edited[seg]) { Text("重置这一项") }
    }

    when (seg) {
        0 -> {
            LabeledSlider("曝光", l.exposure, -3.0, 3.0, 0.0, enabled, "%+.2f EV") { set(l.copy(exposure = it)) }
            Adj("对比度", l.contrast, -50.0, 50.0, enabled) { set(l.copy(contrast = it)) }
            Adj("亮度", l.brightness, -50.0, 50.0, enabled) { set(l.copy(brightness = it)) }
            Adj("高光", l.highlights, -100.0, 100.0, enabled) { set(l.copy(highlights = it)) }
            Adj("阴影", l.shadows, -100.0, 100.0, enabled) { set(l.copy(shadows = it)) }
            Adj("黑场", l.blackPoint, 0.0, 50.0, enabled) { set(l.copy(blackPoint = it)) }
            Adj("白场", l.whitePoint, 0.0, 50.0, enabled) { set(l.copy(whitePoint = it)) }
        }
        1 -> {
            Adj("色温", l.temperature, -100.0, 100.0, enabled) { set(l.copy(temperature = it)) }
            Adj("色调", l.tint, -100.0, 100.0, enabled) { set(l.copy(tint = it)) }
            Adj("饱和度", l.saturation, -100.0, 100.0, enabled) { set(l.copy(saturation = it)) }
            Adj("自然饱和度", l.vibrance, -100.0, 100.0, enabled) { set(l.copy(vibrance = it)) }
        }
        2 -> {
            Group("HSL 分色")
            var band by remember { mutableIntStateOf(0) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Look.BANDS.forEachIndexed { i, (_, argb) ->
                    val on = band == i
                    val bandEdited = !l.hsl.getOrElse(i) { Band() }.neutral
                    Box(
                        Modifier.size(if (on) 34.dp else 28.dp)
                            .background(Color(argb), CircleShape)
                            .border(if (on) 2.5.dp else if (bandEdited) 1.5.dp else 0.dp,
                                if (on) Palette.OnSurface else Palette.OnSurfaceSoft, CircleShape)
                            .clickable { band = i },
                    )
                }
            }
            val b = l.hsl.getOrElse(band) { Band() }
            val name = Look.BANDS[band].first
            Adj("${name} · 色相", b.hue, -100.0, 100.0, enabled) { set(l.withBand(band, b.copy(hue = it))) }
            Adj("${name} · 饱和度", b.sat, -100.0, 100.0, enabled) { set(l.withBand(band, b.copy(sat = it))) }
            Adj("${name} · 明亮度", b.lum, -100.0, 100.0, enabled) { set(l.withBand(band, b.copy(lum = it))) }

            Group("色彩平衡")
            var zone by remember { mutableIntStateOf(0) }
            val zones = listOf("全局", "阴影", "中间调", "高光")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                zones.forEachIndexed { i, label ->
                    SelectPill(selected = zone == i, onClick = { zone = i }, label = { Text(label) }, enabled = enabled)
                }
            }
            val z = when (zone) { 1 -> l.shadowZone; 2 -> l.midZone; 3 -> l.highlightZone; else -> l.master }
            fun setZone(next: Zone) = set(when (zone) {
                1 -> l.copy(shadowZone = next); 2 -> l.copy(midZone = next); 3 -> l.copy(highlightZone = next)
                else -> l.copy(master = next)
            })
            LabeledSlider("色相", z.hue, 0.0, 360.0, 0.0, enabled, "%.0f°") { setZone(z.copy(hue = it)) }
            Adj("强度", z.sat, 0.0, 100.0, enabled) { setZone(z.copy(sat = it)) }
            Adj("明亮度", z.lum, -100.0, 100.0, enabled) { setZone(z.copy(lum = it)) }
        }
        3 -> CurveEditor(l.curves, ui.image, enabled, onDrag = vm::setInteracting) { set(l.copy(curves = it)) }
        4 -> PalettePad(l, enabled, onDrag = vm::setInteracting) { set(it) }
        else -> {
            Adj("褪色", l.fade, 0.0, 100.0, enabled) { set(l.copy(fade = it)) }
            Adj("暗角", l.vignette, -100.0, 100.0, enabled) { set(l.copy(vignette = it)) }
            LabeledSlider("暗角范围", l.vignetteMid, 0.0, 100.0, 50.0, enabled && l.vignette != 0.0, "%.0f") {
                set(l.copy(vignetteMid = it))
            }
        }
    }
}

/** One line per 调整 segment: what it changes, in plain words (UI2.md 2.6). */
private val SEGMENT_HINTS = listOf(
    "明暗：整体曝光、反差，以及高光和阴影各自的亮度。",
    "冷暖和浓淡：色温、色调、饱和度。",
    "单独调某一种颜色，或给阴影、中间调、高光分别染色。",
    "按住点拖动；空白处上下滑会滚动页面。双击点删除。",
    "左右是色彩浓淡，上下是色调明暗；双击回到这款风格的默认点。",
    "褪色和暗角，像老照片的质感。",
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
    // UI.md 2.1: one amber action in the middle, the light table as the quiet second.
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f, Motion.fastSpatial(), label = "open")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Text("Spektralab-X", style = MaterialTheme.typography.headlineSmall, color = Palette.OnSurface)
        Spacer(Modifier.height(6.dp))
        Text("光谱胶片冲印", style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurfaceSoft)
        Spacer(Modifier.height(32.dp))
        androidx.compose.material3.Button(
            onClick = onPick, interactionSource = press,
            modifier = Modifier.height(52.dp).widthIn(min = 200.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        ) {
            Text("打开照片", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onLightTable, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("去观片台挑一张", color = Palette.OnSurface.copy(alpha = 0.85f))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "JPEG · HEIC · PNG · 相机 RAW（DNG、CR3、NEF、ARW、RAF…）",
            style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
        )
    }
}
