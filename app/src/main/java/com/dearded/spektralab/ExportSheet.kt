package com.dearded.spektralab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What 保存 was asked for. */
internal sealed interface SaveChoice {
    data class File(val format: ExportFormat, val size: ExportSize, val space: TiffColorSpace = TiffColorSpace.SRGB) : SaveChoice
    /** The largest the phone can render (ProcessingQueue's extreme job). */
    data object Highest : SaveChoice
}

/**
 * 保存 (ui-polish): the format first, as three cards, then its one option, then one button.
 * Replaces a menu of eight lines with brackets in them. The JPEG colour proofs stay, as two quiet
 * buttons at the foot: they show, they do not save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportSheet(
    heif: Boolean,
    proofEnabled: Boolean,
    onProof: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (SaveChoice) -> Unit,
) {
    val view = LocalView.current
    val formats = buildList {
        add(Triple(ExportFormat.JPEG, "JPEG", tr("通用")))
        if (heif) add(Triple(ExportFormat.HEIF, "HEIF", tr("体积更小")))
        add(Triple(ExportFormat.TIFF, "TIFF", tr("16 位，后期用")))
    }
    var fmt by rememberSaveable { mutableIntStateOf(0) }
    var jpegSize by rememberSaveable { mutableIntStateOf(0) }
    var space by rememberSaveable { mutableIntStateOf(0) }
    // Short names on the segments; the file gets the full profile (TiffColorSpace).
    val spaces = listOf(TiffColorSpace.SRGB, TiffColorSpace.DISPLAY_P3, TiffColorSpace.PROPHOTO, TiffColorSpace.ADOBE_RGB)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(tr("保存到相册"), style = MaterialTheme.typography.titleMedium, color = Palette.OnSurface,
                modifier = Modifier.padding(bottom = 14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEachIndexed { i, (_, name, note) ->
                    val on = fmt.coerceIn(formats.indices) == i
                    Column(
                        Modifier.weight(1f).heightIn(min = 72.dp).clip(Corners.l)
                            .background(if (on) Palette.Accent else Palette.PanelHigh)
                            .selectable(on, role = Role.RadioButton) { if (!on) { fmt = i; Feedback.play(view, Cue.DetentMajor) } }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (on) Palette.OnAccent else Palette.OnSurface)
                        Spacer(Modifier.height(8.dp))
                        Text(note, style = MaterialTheme.typography.labelSmall,
                            color = (if (on) Palette.OnAccent else Palette.OnSurface).copy(alpha = 0.7f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val format = formats[fmt.coerceIn(formats.indices)].first
            when (format) {
                ExportFormat.TIFF -> Segmented(listOf("sRGB", "P3", "ProPhoto", "Adobe RGB"), space, true) { space = it; Feedback.play(view, Cue.Detent) }
                ExportFormat.HEIF -> {}
                else -> Segmented(listOf(tr("原尺寸"), tr("适合分享"), tr("最高画质")), jpegSize, true) { jpegSize = it; Feedback.play(view, Cue.Detent) }
            }
            Spacer(Modifier.height(18.dp))
            androidx.compose.material3.Button(
                onClick = {
                    onSave(when (format) {
                        ExportFormat.TIFF -> SaveChoice.File(ExportFormat.TIFF, ExportSize.FULL, spaces[space])
                        ExportFormat.HEIF -> SaveChoice.File(ExportFormat.HEIF, ExportSize.FULL)
                        else -> when (jpegSize) {
                            1 -> SaveChoice.File(ExportFormat.JPEG, ExportSize.SHARE)
                            2 -> SaveChoice.Highest
                            else -> SaveChoice.File(ExportFormat.JPEG, ExportSize.FULL)
                        }
                    })
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(tr("保存"), style = MaterialTheme.typography.titleMedium) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                for (target in listOf("sRGB", "Display P3")) TextButton(enabled = proofEnabled, onClick = { onProof(target) }) {
                    Text(tr("JPEG 颜色预览 · {0}", target), style = MaterialTheme.typography.labelMedium, color = Palette.OnSurfaceSoft)
                }
            }
        }
    }
}
