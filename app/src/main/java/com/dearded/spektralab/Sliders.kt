package com.dearded.spektralab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Every editing value in the app (docs/UI.md 2.3, ui-polish): drawn and turned as a [CameraDial],
 * a metal drum with detents. The signature is the slider's of old, so every caller became a dial
 * at once. Tap the value over the index to type one (Silverphase RulerParameterRow, upstream
 * ScrubSlider). [onDrag] brackets a drag; the model hears each step through [onChange].
 */
@Composable
internal fun LabeledSlider(
    label: String, value: Double, min: Double, max: Double, default: Double,
    enabled: Boolean, format: String, onDrag: (Boolean) -> Unit = {},
    /** Shown under the dial while it is off: why it cannot be used (UI2.md 4, question 3). */
    disabledReason: String = "",
    /** What each end does (偏黄 / 偏蓝), read out by TalkBack; on screen [tint] shows it. */
    ends: Pair<String, String>? = null,
    /** A colour along the range, drawn as a band on the drum (warm to cool, magenta to green). */
    tint: ((Float) -> androidx.compose.ui.graphics.Color)? = null,
    /** Values that get a dot and a firmer detent. */
    stops: List<Double> = emptyList(),
    showName: Boolean = true,
    onChange: (Double) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    CameraDial(label, value, min, max, default, enabled, format, onDrag, disabledReason, ends, tint, stops,
        onTypeValue = { typing = true }, showName = showName, onChange = onChange)

    // The typed value: digits, sign and point only (a unit such as EV, × or % is ignored),
    // clamped to the range; anything unreadable leaves the value alone.
    if (typing) {
        val lo = min.toFloat()
        val hi = max.toFloat()
        fun text(v: Float) = String.format(Locale.ROOT, format, v.toDouble())
        var entry by remember { mutableStateOf(String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')) }
        val parsed = entry.filter { it.isDigit() || it == '-' || it == '.' }.toFloatOrNull()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(label) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = entry, onValueChange = { entry = it.take(12) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(tr("范围 {0} 到 {1}，默认 {2}", text(lo), text(hi), text(default.toFloat())),
                        style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
                        modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = parsed != null, onClick = {
                    parsed?.let { onChange(it.coerceIn(lo, hi).toDouble()) }
                    typing = false
                }) { Text(tr("确定")) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { typing = false }) { Text(tr("取消")) } },
        )
    }
}

/**
 * UI2.md 3.1: while a finger drags any slider, the top bar and the photo's
 * overlays fade, leaving the photo and the slider. The editor provides it.
 */
val LocalSliderDrag = androidx.compose.runtime.staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * 欠曝 / 正常 / 过曝 from the print on screen (ui2_icecream.md 6.6): the mean of the
 * luminance bins, with clipping deciding first. Rough on purpose: it says which way to
 * look, it does not meter. Bins are encoded values (Histogram), so the mean of a
 * well-exposed print sits around the middle.
 */
internal fun exposureVerdict(h: Histogram): String {
    if (h.clippedHigh > 0.05f && h.clippedHigh > h.clippedLow) return tr("偏亮，高光有溢出")
    if (h.clippedLow > 0.10f && h.clippedLow > h.clippedHigh) return tr("偏暗，暗部有溢出")
    var n = 0L; var sum = 0.0
    h.y.forEachIndexed { i, c -> n += c; sum += c * (i + 0.5) }
    if (n == 0L) return tr("正常")
    val mean = sum / n / Histogram.BINS
    return when {
        mean < 0.28 -> tr("偏暗")
        mean > 0.68 -> tr("偏亮")
        else -> tr("正常")
    }
}
