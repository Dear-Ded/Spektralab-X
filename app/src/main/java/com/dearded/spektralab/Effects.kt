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

    // ui2_colorfulcam.md 1: four stops per effect in reach; 关 is the old switch. The
    // continuous strengths are behind 更多参数, and a strength between stops chooses none.
    // Stop values are first guesses, to be tuned against real prints.
    EffectStops(tr("颗粒"), e.grainActive, e.grainAmount, GRAIN_STOPS, enabled) { on, k -> onChange(e.copy(grainActive = on, grainAmount = k)) }
    EffectStops(tr("光晕"), e.halationActive, e.halationAmount, HALATION_STOPS, enabled) { on, k -> onChange(e.copy(halationActive = on, halationAmount = k)) }
    EffectStops(tr("相纸眩光"), e.glareActive, e.glareAmount, GLARE_STOPS, enabled) { on, k -> onChange(e.copy(glareActive = on, glareAmount = k)) }

    val tuned = e.grainAmount != fxd.grainAmount || e.halationAmount != fxd.halationAmount ||
        e.glareAmount != fxd.glareAmount || e.grainArea != 0.2 || !e.grainLayered || e.scatterAmount != 1.0 ||
        e.unsharpAmount != fxd.unsharpAmount || e.unsharpSigma != fxd.unsharpSigma || e.couplersAmount != 1.0 ||
        e.negativeGamma != fxd.negativeGamma || e.highlightBoostEv != fxd.highlightBoostEv ||
        e.lensBlurUm != fxd.lensBlurUm || e.noAntihalation != fxd.noAntihalation || e.cameraFilter != fxd.cameraFilter ||
        e.preflash != fxd.preflash || e.labLevels != fxd.labLevels
    MoreRow(open = more, edited = tuned) { more = !more }
    if (!more) Hint(moreSummary(tr("15 项"),
        tr("颗粒强度") to e.grainAmount.takeIf { e.grainActive && it != fxd.grainAmount }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        tr("光晕强度") to e.halationAmount.takeIf { e.halationActive && it != fxd.halationAmount }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        tr("眩光强度") to e.glareAmount.takeIf { e.glareActive && it != fxd.glareAmount }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
        tr("负片反差") to e.negativeGamma.takeIf { it != fxd.negativeGamma }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        tr("高光能量") to e.highlightBoostEv.takeIf { it != fxd.highlightBoostEv }?.let { String.format(java.util.Locale.ROOT, "%.1f EV", it) },
        tr("柔化") to e.lensBlurUm.takeIf { it != fxd.lensBlurUm }?.let { String.format(java.util.Locale.ROOT, "%.0f µm", it) },
        tr("去防光晕层") to if (e.noAntihalation) tr("开") else null,
        tr("滤镜") to e.cameraFilter.takeIf { it.isNotEmpty() && e.monoPrint }?.let { filterLabel(it) },
        tr("预闪") to e.preflash.takeIf { it != fxd.preflash }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        tr("黑白场") to if (e.labLevels && !e.scanFilm) tr("开") else null,
        tr("颗粒尺寸") to e.grainArea.takeIf { it != 0.2 }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        tr("分层颗粒") to if (!e.grainLayered) tr("关") else null,
        tr("散射") to e.scatterAmount.takeIf { it != 1.0 }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        tr("锐化") to e.unsharpAmount.takeIf { it != fxd.unsharpAmount }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
        tr("半径") to e.unsharpSigma.takeIf { it != fxd.unsharpSigma }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
        tr("色彩分离") to e.couplersAmount.takeIf { it != 1.0 }?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
    ))
    Expandable(more, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(RowGap)) {
        LabeledSlider(tr("颗粒强度"), e.grainAmount, 0.0, 2.0, 1.0, enabled && e.grainActive, "%.2f", onDrag = onDrag,
            disabledReason = "") {
            onChange(e.copy(grainAmount = it))
        }
        LabeledSlider(tr("光晕强度"), e.halationAmount, 0.0, 4.0, 1.0, enabled && e.halationActive, "%.2f", onDrag = onDrag,
            disabledReason = "") {
            onChange(e.copy(halationAmount = it))
        }
        // Upstream's 0..30 (RFC-034): the paper's own veil is 1, and nothing under ~4 is visible.
        LabeledSlider(tr("眩光强度"), e.glareAmount, 0.0, 30.0, 1.0, enabled && e.glareActive, "%.1f", onDrag = onDrag,
            disabledReason = "") {
            onChange(e.copy(glareAmount = it))
        }
        LabeledSlider(tr("颗粒尺寸"), e.grainArea, 0.05, 1.0, 0.2, enabled && e.grainActive, "%.2f µm²", onDrag = onDrag,
            disabledReason = "") {
            onChange(e.copy(grainArea = it))
        }
        Toggle(tr("分层颗粒"), e.grainLayered, enabled && e.grainActive && !e.monoPrint, reason = when {
            !enabled -> ""
            e.monoPrint -> tr("黑白片只有一层银盐颗粒。")
            !e.grainActive -> tr("颗粒已关闭。")
            else -> ""
        }) { onChange(e.copy(grainLayered = it)) }
        Note(if (e.monoPrint) tr("黑白片只有一层银盐颗粒。颗粒细节在 1:1 下看得最清楚。")
             else tr("关闭后每个色层只画一层颗粒，颗粒更匀、更细。颗粒细节在 1:1 下看得最清楚。"))
        LabeledSlider(tr("乳剂散射"), e.scatterAmount, 0.0, 1.0, 1.0, enabled && e.halationActive, "%.2f", onDrag = onDrag,
            disabledReason = if (enabled && !e.halationActive) tr("光晕已关闭，散射跟着光晕一起关。") else "") {
            onChange(e.copy(scatterAmount = it))
        }
        Note(tr("光晕是片基反射回来的红晕；散射是光在乳剂里的扩散，调低画面更锐。"))
        LabeledSlider(tr("扫描锐化"), e.unsharpAmount, 0.0, 2.0, fxd.unsharpAmount, enabled, "%.2f", onDrag = onDrag) {
            onChange(e.copy(unsharpAmount = it))
        }
        LabeledSlider(tr("锐化半径"), e.unsharpSigma, 0.2, 3.0, fxd.unsharpSigma, enabled && e.unsharpAmount > 0, "%.1f px", onDrag = onDrag,
            disabledReason = if (enabled && e.unsharpAmount <= 0) tr("扫描锐化为 0，半径不起作用。") else "") {
            onChange(e.copy(unsharpSigma = it))
        }
        Note(tr("扫描仪的 USM 锐化；半径按原图像素算，各档预览按比例缩放。0 为不锐化。"))
        LabeledSlider(tr("色彩分离"), e.couplersAmount, 0.0, 1.5, 1.0, enabled, "%.2f", onDrag = onDrag) {
            onChange(e.copy(couplersAmount = it))
        }
        Note(tr("胶片层间的 DIR 抑制：调高颜色更分明，调低更柔和；1 是这款胶片自己的。"))

        // engine_deepdive 1.1-1.2: upstream controls the app did not have.
        GroupLabel(tr("负片"))
        LabeledSlider(tr("负片反差"), e.negativeGamma, 0.7, 1.4, fxd.negativeGamma, enabled, "%.2f", onDrag = onDrag,
            ends = tr("更平") to tr("更硬")) {
            onChange(e.copy(negativeGamma = it))
        }
        Note(tr("负片冲洗的反差：调高曲线更陡，暗部更沉、高光更亮；1 是这款胶片自己的。"))
        LabeledSlider(tr("高光能量"), e.highlightBoostEv, 0.0, 3.0, fxd.highlightBoostEv, enabled, "%.1f EV", onDrag = onDrag) {
            onChange(e.copy(highlightBoostEv = it))
        }
        Note(tr("把中灰以上 4 档的高光再推亮，光晕和胶片肩部跟着更明显。0 为不加。"))
        LabeledSlider(tr("镜头柔化"), e.lensBlurUm, 0.0, 30.0, fxd.lensBlurUm, enabled, "%.0f µm", onDrag = onDrag) {
            onChange(e.copy(lensBlurUm = it))
        }
        Note(tr("相机镜头在胶片上的模糊，按胶片上的微米算，画幅越小越明显。0 为不柔化。"))
        Toggle(tr("去掉防光晕层"), e.noAntihalation, enabled && e.halationActive,
            reason = if (enabled && !e.halationActive) tr("光晕已关闭。") else "") {
            onChange(e.copy(noAntihalation = it))
        }
        Note(tr("像拆掉防光晕层的电影胶片（CineStill 那样）：亮处周围出现明显的红晕。"))
        if (e.monoPrint) {
            GroupLabel(tr("镜前滤镜"))
            PillRow {
                Edit.CAMERA_FILTERS.forEach { f ->
                    SelectPill(selected = e.cameraFilter == f, enabled = enabled,
                        onClick = { onChange(e.copy(cameraFilter = f)) }, label = { Text(filterLabel(f)) })
                }
            }
            Note(tr("黄、橙、红逐级压暗蓝天；绿提亮植物。"))
        }

        GroupLabel(tr("相纸与扫描"))
        LabeledSlider(tr("预闪"), e.preflash, 0.0, 0.3, fxd.preflash, enabled && !e.scanFilm, "%.2f", onDrag = onDrag,
            disabledReason = if (enabled && e.scanFilm) tr("直接扫描不经过相纸，没有预闪。") else "") {
            onChange(e.copy(preflash = it))
        }
        Note(tr("印相前先给相纸一点均匀曝光：高光变灰、细节回来，整体反差降低。"))
        Toggle(tr("冲扫黑白场"), e.labLevels, enabled && !e.scanFilm,
            reason = if (enabled && e.scanFilm) tr("直接扫描不经过相纸，没有黑白场校正。") else "") {
            onChange(e.copy(labLevels = it))
        }
        Note(tr("像冲扫店那样把相纸的白和黑拉到满幅：黑更沉、白更干净。"))
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
    "" -> tr("无")
    "w8" -> tr("8 黄")
    "w11" -> tr("11 黄绿")
    "w12" -> tr("12 深黄")
    "w15" -> tr("15 深黄")
    "w21" -> tr("21 橙")
    "w25" -> tr("25 红")
    "w47" -> tr("47 蓝")
    "w58" -> tr("58 绿")
    else -> f
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, reason: String = "", onChange: (Boolean) -> Unit) =
    SettingRow(label, checked, enabled, disabledReason = reason, onChange = onChange)

@Composable
private fun Note(text: String) = Hint(text, Modifier.padding(bottom = 4.dp))

/** 轻 / 中 / 强 per effect; 中 is the film's own strength (1). */
private val GRAIN_STOPS = listOf(0.5, 1.0, 1.6)
private val HALATION_STOPS = listOf(0.5, 1.0, 2.0)
/** Glare: the paper's own veil is 1, and under ~4 it hardly shows (RFC-034). */
private val GLARE_STOPS = listOf(1.0, 4.0, 10.0)

@Composable
private fun EffectStops(label: String, on: Boolean, amount: Double, stops: List<Double>, enabled: Boolean,
                        onPick: (Boolean, Double) -> Unit) {
    val chosen = if (!on) 0 else stops.indexOfFirst { kotlin.math.abs(amount - it) < 0.005 }.let { if (it < 0) -1 else it + 1 }
    StepRow(if (chosen < 0) tr("{0} · 自定义", label) else label, listOf(tr("关"), tr("轻"), tr("中"), tr("强")), chosen, enabled) { i ->
        if (i == 0) onPick(false, amount) else onPick(true, stops[i - 1])
    }
}
