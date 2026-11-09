package com.dearded.spektralab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Where the editor's panel is: the entry, the 冲洗 step and the 调整 segment.
 * One object so 修改记录 can send the user to the step a change lives in.
 */
@Stable
internal class EditorNav {
    var tab by mutableIntStateOf(0)
    var step by mutableIntStateOf(0)
    var adjustSeg by mutableIntStateOf(0)
}

internal val LocalEditorNav = staticCompositionLocalOf { EditorNav() }

/** One changed group: where it lives and how to put it back. */
internal class Change(val name: String, val detail: String, val tab: Int, val step: Int = -1, val seg: Int = -1,
                      val reset: (Edit) -> Edit)

/**
 * The changed groups of [e] in darkroom order (ui2_snapseed.md 4): 冲洗 胶卷 / 相纸 / 曝光 / 效果,
 * the 调整 segments, 蒙版, 灰尘, 边框, 构图. The order is fixed, never sorted by
 * the user: grain cannot go before the paper. Each reset returns only its own
 * group to a fresh edit's value.
 */
internal fun changesOf(e: Edit, catalog: Catalog): List<Change> {
    val d = Edit()
    val out = mutableListOf<Change>()
    // A new photo opens on 无 (Edit.NONE): a film, a paper or the film's effects are each a change.
    val positive = catalog.film(e.film)?.positive == true
    if (e.rawDevelopmentChanged)
        out += Change("RAW 显影", if (e.rawDevelopment == RawDevelopmentMode.STANDARD_V1) "标准显色" else "线性显示", 1, step = 0) {
            it.copy(rawDevelopment = RawDevelopmentMode.NEUTRAL, rawAutoExposure = true, rawExposureEv = 0.0)
        }
    if (!e.noFilm || e.filmFormatMm != d.filmFormatMm)
        out += Change("胶卷", listOfNotNull(if (e.noFilm) "无" else catalog.film(e.film)?.label,
            FORMAT_NAMES[e.filmFormatMm]?.takeIf { e.filmFormatMm != d.filmFormatMm }).joinToString(" · "), 1, step = 0) {
            it.copy(noFilm = true, filmFormatMm = d.filmFormatMm)
        }
    if (!e.noFilm && !e.paperNone(positive))
        out += Change("相纸", catalog.paper(e.paper)?.label.orEmpty(), 1, step = 1) {
            catalog.normalize(if (positive) it.copy(scanFilm = true) else it.copy(scanFilm = false, digitalIntermediate = true))
        }
    if (!e.autoExposure || e.exposureEv != d.exposureEv || !e.sceneCurve.isIdentity || e.printExposure != d.printExposure || e.yShift != d.yShift || e.mShift != d.mShift)
        out += Change("曝光", listOfNotNull(
            e.exposureEv.takeIf { it != d.exposureEv }?.let { String.format(java.util.Locale.ROOT, "%+.1f EV", it) },
            e.printExposure.takeIf { it != d.printExposure }?.let { String.format(java.util.Locale.ROOT, "×%.2f", it) },
            "滤镜".takeIf { e.yShift != d.yShift || e.mShift != d.mShift },
            "手动曝光".takeIf { !e.autoExposure },
            "胶片前曲线".takeIf { !e.sceneCurve.isIdentity },
        ).joinToString(" · "), 1, step = 2) {
            it.copy(autoExposure = d.autoExposure, exposureEv = d.exposureEv, printExposure = d.printExposure,
                sceneCurve = SceneCurve.IDENTITY,
                yShift = d.yShift, mShift = d.mShift)
        }
    val fxTuned = e.scatterAmount != d.scatterAmount || e.couplersAmount != d.couplersAmount ||
        e.grainLayered != d.grainLayered || e.grainArea != d.grainArea || e.unsharpSigma != d.unsharpSigma
    if (!e.effectsNone || fxTuned)
        out += Change("效果", listOfNotNull("颗粒".takeIf { e.grainActive }, "光晕".takeIf { e.halationActive },
            "眩光".takeIf { e.glareActive }, "锐化".takeIf { e.unsharpAmount > 0 }).joinToString(" · ")
            .ifEmpty { "更多参数" }, 1, step = 3) {
            it.withoutEffects().copy(grainArea = d.grainArea, grainLayered = d.grainLayered, scatterAmount = d.scatterAmount,
                unsharpSigma = d.unsharpSigma, couplersAmount = d.couplersAmount)
        }
    adjustEdited(e.look).forEachIndexed { i, on ->
        if (on) out += Change("调整 · ${ADJUST_SEGMENTS[i]}", "", 2, seg = i) { it.copy(look = resetAdjustSegment(it.look, i)) }
    }
    if (e.look.masks.isNotEmpty())
        out += Change("蒙版", "${e.look.masks.size} 个", 2) { it.copy(look = it.look.copy(masks = emptyList())) }
    if (e.look.dust.active)
        out += Change("灰尘", "", 1, step = 3) { it.copy(look = it.look.copy(dust = Dust())) }
    if (e.borderId != d.borderId || e.filmEdge || e.frame.perf != null || e.dateImprint)
        out += Change("边框", listOfNotNull(Borders.byId(e.borderId)?.takeIf { it.id != "none" }?.label,
            "片边".takeIf { e.filmEdge }, "齿孔".takeIf { e.frame.perf != null }, "日期".takeIf { e.dateImprint })
            .joinToString(" · "), 3) {
            it.copy(borderId = d.borderId, frame = d.frame, filmEdge = false, dateImprint = false,
                cropX = if (it.filmEdge) d.cropX else it.cropX, cropY = if (it.filmEdge) d.cropY else it.cropY,
                cropW = if (it.filmEdge) d.cropW else it.cropW, cropH = if (it.filmEdge) d.cropH else it.cropH)
        }
    if (e.cropAspect != d.cropAspect || e.quarterTurns != d.quarterTurns || e.flipH != d.flipH)
        out += Change("构图", listOfNotNull(
            "裁剪".takeIf { e.cropAspect != d.cropAspect }, "转 ${e.quarterTurns * 90}°".takeIf { e.quarterTurns != 0 },
            "翻转".takeIf { e.flipH }).joinToString(" · "), 4) {
            it.copy(cropAspect = d.cropAspect, cropX = d.cropX, cropY = d.cropY, cropW = d.cropW, cropH = d.cropH,
                quarterTurns = d.quarterTurns, flipH = d.flipH)
        }
    return out
}

private val FORMAT_NAMES = mapOf(24.0 to "半格", 36.0 to "135", 56.0 to "645", 70.0 to "6×7", 127.0 to "4×5")

/**
 * 修改记录 (ui2_snapseed.md 4, after Snapseed's 查看修改): what is changed on this
 * photo, one row per group in the darkroom's fixed order. Tap a row to go to
 * its step; 重置 puts that group back (one undo). No reordering: the chain is
 * physics, not a stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangesSheet(ui: UiState, vm: EditorViewModel, onGo: (Change) -> Unit, onDismiss: () -> Unit) {
    val changes = changesOf(ui.edit, ui.catalog)
    // 隐藏: groups taken off the preview only (EditorViewModel.previewOverride); a real
    // edit clears the override there, so the set follows the edit and the sheet's life.
    var hidden by androidx.compose.runtime.remember(ui.edit) { androidx.compose.runtime.mutableStateOf(emptySet<String>()) }
    androidx.compose.runtime.LaunchedEffect(hidden, ui.edit) {
        vm.previewOverride(if (hidden.isEmpty()) null
            else changes.filter { it.name in hidden }.fold(ui.edit) { acc, c -> c.reset(acc) })
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.previewOverride(null) } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.PanelHigh) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState())) {
            PanelHeader(
                title = "修改记录",
                summary = if (changes.isEmpty()) "" else "${changes.size} 组",
            )
            Spacer(Modifier.padding(top = RowGap))
            if (changes.isEmpty()) Hint("没有改动", Modifier.padding(bottom = 24.dp))
            for (c in changes) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Corners.m)
                        .clickable(role = Role.Button) { onGo(c) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
                        if (c.detail.isNotBlank()) Hint(c.detail)
                    }
                    Spacer(Modifier.width(8.dp))
                    val off = c.name in hidden
                    // Words, not an eye icon in a colour: 显示 while hidden, 隐藏 otherwise.
                    QuietButton(if (off) "显示" else "隐藏", enabled = ui.hasImage && !ui.exporting) {
                        hidden = if (off) hidden - c.name else hidden + c.name
                    }
                    QuietButton("重置", enabled = ui.hasImage && !ui.exporting) { vm.update(c.reset(ui.edit)) }
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
        }
    }
}
