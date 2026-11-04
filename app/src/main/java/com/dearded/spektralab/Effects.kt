package com.dearded.spektralab

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The 效果 page's simulated effects, each with its own strength (users asked
 * for grain, halation and the rest to be separate): grain and whether it is
 * drawn per sub-layer, the halo off the film base and the scatter inside the
 * emulsion, the paper's glare, and the DIR couplers that give a stock its
 * colour separation. Ranges are upstream's EffectStrengths. Every one is an
 * engine field, so a change develops again (the live tier while dragging).
 */
@Composable
internal fun EffectsSection(e: Edit, enabled: Boolean, onDrag: (Boolean) -> Unit, onChange: (Edit) -> Unit) {
    Toggle("颗粒", e.grainActive, enabled) { onChange(e.copy(grainActive = it)) }
    LabeledSlider("颗粒强度", e.grainAmount, 0.0, 2.0, 1.0, enabled && e.grainActive, "%.2f", onDrag = onDrag) {
        onChange(e.copy(grainAmount = it))
    }
    Toggle("分层颗粒", e.grainLayered, enabled && e.grainActive && !e.monoPrint) { onChange(e.copy(grainLayered = it)) }
    Note(if (e.monoPrint) "黑白片只有一层银盐颗粒。" else "关闭后每个色层只画一层颗粒，颗粒更匀、更细。")

    Toggle("光晕（Halation）", e.halationActive, enabled) { onChange(e.copy(halationActive = it)) }
    LabeledSlider("光晕强度", e.halationAmount, 0.0, 4.0, 1.0, enabled && e.halationActive, "%.2f", onDrag = onDrag) {
        onChange(e.copy(halationAmount = it))
    }
    LabeledSlider("乳剂散射", e.scatterAmount, 0.0, 1.0, 1.0, enabled && e.halationActive, "%.2f", onDrag = onDrag) {
        onChange(e.copy(scatterAmount = it))
    }
    Note("光晕是片基反射回来的红晕；散射是光在乳剂里的扩散，调低画面更锐。")

    Toggle("相纸眩光", e.glareActive, enabled) { onChange(e.copy(glareActive = it)) }
    LabeledSlider("眩光强度", e.glareAmount, 0.0, 5.0, 1.0, enabled && e.glareActive, "%.2f", onDrag = onDrag) {
        onChange(e.copy(glareAmount = it))
    }

    LabeledSlider("色彩分离", e.couplersAmount, 0.0, 1.5, 1.0, enabled, "%.2f", onDrag = onDrag) {
        onChange(e.copy(couplersAmount = it))
    }
    Note("胶片层间的 DIR 抑制：调高颜色更分明，调低更柔和；1 是这款胶片自己的。")
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))
}
