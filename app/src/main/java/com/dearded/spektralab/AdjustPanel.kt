package com.dearded.spektralab

import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChip
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
    fun set(next: Look) = vm.update(e.copy(look = next))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("冲印后调整", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { set(Look.NEUTRAL) }, enabled = enabled && !l.neutral) { Text("全部重置") }
    }
    Text(
        "作用在冲好的照片上，像扫描后再微调；拖动即时生效，不重新冲洗。导出和预设都带上这些调整。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Group("白平衡")
    Adj("色温", l.temperature, -100.0, 100.0, enabled) { set(l.copy(temperature = it)) }
    Adj("色调", l.tint, -100.0, 100.0, enabled) { set(l.copy(tint = it)) }

    Group("光影")
    LabeledSlider("曝光", l.exposure, -3.0, 3.0, 0.0, enabled, "%+.2f EV") { set(l.copy(exposure = it)) }
    Adj("对比度", l.contrast, -50.0, 50.0, enabled) { set(l.copy(contrast = it)) }
    Adj("亮度", l.brightness, -50.0, 50.0, enabled) { set(l.copy(brightness = it)) }
    Adj("高光", l.highlights, -100.0, 100.0, enabled) { set(l.copy(highlights = it)) }
    Adj("阴影", l.shadows, -100.0, 100.0, enabled) { set(l.copy(shadows = it)) }
    Adj("黑场", l.blackPoint, 0.0, 50.0, enabled) { set(l.copy(blackPoint = it)) }
    Adj("白场", l.whitePoint, 0.0, 50.0, enabled) { set(l.copy(whitePoint = it)) }

    Group("色彩")
    Adj("饱和度", l.saturation, -100.0, 100.0, enabled) { set(l.copy(saturation = it)) }
    Adj("自然饱和度", l.vibrance, -100.0, 100.0, enabled) { set(l.copy(vibrance = it)) }

    Group("HSL 分色")
    var band by remember { mutableIntStateOf(0) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Look.BANDS.forEachIndexed { i, (_, argb) ->
            val on = band == i
            val edited = !l.hsl.getOrElse(i) { Band() }.neutral
            Box(
                Modifier.size(if (on) 30.dp else 26.dp)
                    .background(Color(argb), CircleShape)
                    .border(if (on) 2.dp else if (edited) 1.5.dp else 0.dp,
                        if (on) Palette.OnSurface else Palette.Amber, CircleShape)
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
            FilterChip(selected = zone == i, onClick = { zone = i }, label = { Text(label) }, enabled = enabled)
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

    Group("质感")
    Adj("褪色", l.fade, 0.0, 100.0, enabled) { set(l.copy(fade = it)) }
    Adj("暗角", l.vignette, -100.0, 100.0, enabled) { set(l.copy(vignette = it)) }
    LabeledSlider("暗角范围", l.vignetteMid, 0.0, 100.0, 50.0, enabled && l.vignette != 0.0, "%.0f") {
        set(l.copy(vignetteMid = it))
    }
}

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Amber,
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
internal fun EmptyState(onPick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Text("Spektralab-X", style = MaterialTheme.typography.headlineSmall, color = Palette.OnSurface)
        Spacer(Modifier.height(6.dp))
        Text("光谱胶片冲印", style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurfaceSoft)
        Spacer(Modifier.height(28.dp))
        androidx.compose.material3.Button(onClick = onPick, modifier = Modifier.height(52.dp)) {
            Text("打开照片", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "JPEG · HEIC · PNG · 相机 RAW（DNG、CR3、NEF、ARW、RAF…）",
            style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
        )
    }
}
