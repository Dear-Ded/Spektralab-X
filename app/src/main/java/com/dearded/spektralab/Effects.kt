package com.dearded.spektralab

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    // UI2.md 2.3: three strengths in reach; the film-nerd knobs behind 更多参数.
    // Defaults come from Edit() (no-film-default moves scan sharpening to 0), never literals.
    val fxd = Edit()
    val expandAll = rememberExpandAll()
    var more by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(expandAll) }

    Toggle("颗粒", e.grainActive, enabled) { onChange(e.copy(grainActive = it)) }
    LabeledSlider("颗粒强度", e.grainAmount, 0.0, 2.0, 1.0, enabled && e.grainActive, "%.2f", onDrag = onDrag,
        disabledReason = if (enabled && !e.grainActive) "颗粒已关闭，打开上面的开关再调。" else "") {
        onChange(e.copy(grainAmount = it))
    }
    Toggle("光晕（Halation）", e.halationActive, enabled) { onChange(e.copy(halationActive = it)) }
    LabeledSlider("光晕强度", e.halationAmount, 0.0, 4.0, 1.0, enabled && e.halationActive, "%.2f", onDrag = onDrag,
        disabledReason = if (enabled && !e.halationActive) "光晕已关闭，打开上面的开关再调。" else "") {
        onChange(e.copy(halationAmount = it))
    }
    Toggle("相纸眩光", e.glareActive, enabled) { onChange(e.copy(glareActive = it)) }
    // Upstream's 0..30 (RFC-034): the paper's own veil is 1, and nothing under ~4 is visible.
    LabeledSlider("眩光强度", e.glareAmount, 0.0, 30.0, 1.0, enabled && e.glareActive, "%.1f", onDrag = onDrag,
        disabledReason = if (enabled && !e.glareActive) "眩光已关闭，打开上面的开关再调。" else "") {
        onChange(e.copy(glareAmount = it))
    }

    val tuned = e.grainArea != 0.2 || !e.grainLayered || e.scatterAmount != 1.0 ||
        e.unsharpAmount != fxd.unsharpAmount || e.unsharpSigma != fxd.unsharpSigma || e.couplersAmount != 1.0 ||
        e.negativeGamma != fxd.negativeGamma || e.highlightBoostEv != fxd.highlightBoostEv ||
        e.lensBlurUm != fxd.lensBlurUm || e.noAntihalation != fxd.noAntihalation || e.cameraFilter != fxd.cameraFilter ||
        e.preflash != fxd.preflash || e.labLevels != fxd.labLevels
    MoreRow(open = more, edited = tuned) { more = !more }
    if (!more) Hint(moreSummary("颗粒尺寸、分层颗粒、乳剂散射、扫描锐化、色彩分离、负片反差、高光能量、镜头柔化、去防光晕层、镜前滤镜、预闪、冲扫黑白场",
        "负片反差" to e.negativeGamma.takeIf { it != fxd.negativeGamma }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        "高光能量" to e.highlightBoostEv.takeIf { it != fxd.highlightBoostEv }?.let { String.format(java.util.Locale.ROOT, "%.1f EV", it) },
        "柔化" to e.lensBlurUm.takeIf { it != fxd.lensBlurUm }?.let { String.format(java.util.Locale.ROOT, "%.0f µm", it) },
        "去防光晕层" to if (e.noAntihalation) "开" else null,
        "滤镜" to e.cameraFilter.takeIf { it.isNotEmpty() && e.monoPrint }?.let { filterLabel(it) },
        "预闪" to e.preflash.takeIf { it != fxd.preflash }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        "黑白场" to if (e.labLevels && !e.scanFilm) "开" else null,
        "颗粒尺寸" to e.grainArea.takeIf { it != 0.2 }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        "分层颗粒" to if (!e.grainLayered) "关" else null,
        "散射" to e.scatterAmount.takeIf { it != 1.0 }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        "锐化" to e.unsharpAmount.takeIf { it != fxd.unsharpAmount }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        "半径" to e.unsharpSigma.takeIf { it != fxd.unsharpSigma }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
        "色彩分离" to e.couplersAmount.takeIf { it != 1.0 }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
    ))
    Expandable(more, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(RowGap)) {
        LabeledSlider("颗粒尺寸", e.grainArea, 0.05, 1.0, 0.2, enabled && e.grainActive, "%.2f µm²", onDrag = onDrag,
            disabledReason = if (enabled && !e.grainActive) "颗粒已关闭，打开上面的开关再调。" else "") {
            onChange(e.copy(grainArea = it))
        }
        Toggle("分层颗粒", e.grainLayered, enabled && e.grainActive && !e.monoPrint, reason = when {
            !enabled -> ""
            e.monoPrint -> "黑白片只有一层银盐颗粒。"
            !e.grainActive -> "颗粒已关闭。"
            else -> ""
        }) { onChange(e.copy(grainLayered = it)) }
        Note(if (e.monoPrint) "黑白片只有一层银盐颗粒。颗粒细节在 1:1 下看得最清楚。"
             else "关闭后每个色层只画一层颗粒，颗粒更匀、更细。颗粒细节在 1:1 下看得最清楚。")
        LabeledSlider("乳剂散射", e.scatterAmount, 0.0, 1.0, 1.0, enabled && e.halationActive, "%.2f", onDrag = onDrag,
            disabledReason = if (enabled && !e.halationActive) "光晕已关闭，散射跟着光晕一起关。" else "") {
            onChange(e.copy(scatterAmount = it))
        }
        Note("光晕是片基反射回来的红晕；散射是光在乳剂里的扩散，调低画面更锐。")
        LabeledSlider("扫描锐化", e.unsharpAmount, 0.0, 2.0, fxd.unsharpAmount, enabled, "%.2f", onDrag = onDrag) {
            onChange(e.copy(unsharpAmount = it))
        }
        LabeledSlider("锐化半径", e.unsharpSigma, 0.2, 3.0, fxd.unsharpSigma, enabled && e.unsharpAmount > 0, "%.1f px", onDrag = onDrag,
            disabledReason = if (enabled && e.unsharpAmount <= 0) "扫描锐化为 0，半径不起作用。" else "") {
            onChange(e.copy(unsharpSigma = it))
        }
        Note("扫描仪的 USM 锐化；半径按原图像素算，各档预览按比例缩放。0 为不锐化。")
        LabeledSlider("色彩分离", e.couplersAmount, 0.0, 1.5, 1.0, enabled, "%.2f", onDrag = onDrag) {
            onChange(e.copy(couplersAmount = it))
        }
        Note("胶片层间的 DIR 抑制：调高颜色更分明，调低更柔和；1 是这款胶片自己的。")

        // engine_deepdive 1.1-1.2: upstream controls the app did not have.
        GroupLabel("负片")
        LabeledSlider("负片反差", e.negativeGamma, 0.7, 1.4, fxd.negativeGamma, enabled, "%.2f", onDrag = onDrag,
            ends = "更平" to "更硬") {
            onChange(e.copy(negativeGamma = it))
        }
        Note("负片冲洗的反差：调高曲线更陡，暗部更沉、高光更亮；1 是这款胶片自己的。")
        LabeledSlider("高光能量", e.highlightBoostEv, 0.0, 3.0, fxd.highlightBoostEv, enabled, "%.1f EV", onDrag = onDrag) {
            onChange(e.copy(highlightBoostEv = it))
        }
        Note("把中灰以上 4 档的高光再推亮，光晕和胶片肩部跟着更明显。0 为不加。")
        LabeledSlider("镜头柔化", e.lensBlurUm, 0.0, 30.0, fxd.lensBlurUm, enabled, "%.0f µm", onDrag = onDrag) {
            onChange(e.copy(lensBlurUm = it))
        }
        Note("相机镜头在胶片上的模糊，按胶片上的微米算，画幅越小越明显。0 为不柔化。")
        Toggle("去掉防光晕层", e.noAntihalation, enabled && e.halationActive,
            reason = if (enabled && !e.halationActive) "光晕已关闭。" else "") {
            onChange(e.copy(noAntihalation = it))
        }
        Note("像拆掉防光晕层的电影胶片（CineStill 那样）：亮处周围出现明显的红晕。")
        if (e.monoPrint) {
            GroupLabel("镜前滤镜")
            PillRow {
                Edit.CAMERA_FILTERS.forEach { f ->
                    SelectPill(selected = e.cameraFilter == f, enabled = enabled,
                        onClick = { onChange(e.copy(cameraFilter = f)) }, label = { Text(filterLabel(f)) })
                }
            }
            Note("黑白片的彩色滤镜：黄、橙压暗蓝天，红更戏剧，绿让植物变亮，蓝反过来。")
        }

        GroupLabel("相纸与扫描")
        LabeledSlider("预闪", e.preflash, 0.0, 0.3, fxd.preflash, enabled && !e.scanFilm, "%.2f", onDrag = onDrag,
            disabledReason = if (enabled && e.scanFilm) "直接扫描不经过相纸，没有预闪。" else "") {
            onChange(e.copy(preflash = it))
        }
        Note("印相前先给相纸一点均匀曝光：高光变灰、细节回来，整体反差降低。")
        Toggle("冲扫黑白场", e.labLevels, enabled && !e.scanFilm,
            reason = if (enabled && e.scanFilm) "直接扫描不经过相纸，没有黑白场校正。" else "") {
            onChange(e.copy(labLevels = it))
        }
        Note("像冲扫店那样把相纸的白和黑拉到满幅：黑更沉、白更干净。")
    }
}

/** The engine_deepdive fields (Model.kt EXTRA_FIELDS) off their defaults: the 效果 step's dot. */
internal fun Edit.extraEffectsTuned(d: Edit = Edit()) =
    negativeGamma != d.negativeGamma || highlightBoostEv != d.highlightBoostEv || lensBlurUm != d.lensBlurUm ||
        noAntihalation != d.noAntihalation || cameraFilter != d.cameraFilter || preflash != d.preflash || labLevels != d.labLevels

/** The same fields back at their defaults: the 效果 step's reset. */
internal fun Edit.withExtraEffectsReset(d: Edit = Edit()) = copy(negativeGamma = d.negativeGamma,
    highlightBoostEv = d.highlightBoostEv, lensBlurUm = d.lensBlurUm, noAntihalation = d.noAntihalation,
    cameraFilter = d.cameraFilter, preflash = d.preflash, labLevels = d.labLevels)

/** A lens filter's name in the pill row ("" is none). */
private fun filterLabel(f: String) = when (f) {
    "" -> "无"
    "w8" -> "8 黄"
    "w11" -> "11 黄绿"
    "w12" -> "12 深黄"
    "w15" -> "15 深黄"
    "w21" -> "21 橙"
    "w25" -> "25 红"
    "w47" -> "47 蓝"
    "w58" -> "58 绿"
    else -> f
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, reason: String = "", onChange: (Boolean) -> Unit) =
    SettingRow(label, checked, enabled, disabledReason = reason, onChange = onChange)

@Composable
private fun Note(text: String) = Hint(text, Modifier.padding(bottom = 4.dp))
