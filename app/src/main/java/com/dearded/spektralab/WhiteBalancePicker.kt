package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal data class WhiteBalancePick(val temperature: Double, val tint: Double, val limited: Boolean)
internal enum class WhiteBalanceSampleError { DARK, CLIPPED, INVALID, PALETTE_OFF }
internal data class WhiteBalanceSample(val pick: WhiteBalancePick? = null, val error: WhiteBalanceSampleError? = null)

/** A private snapshot of the exact print stage immediately before Layer 2 WB. */
internal class WhiteBalanceReference private constructor(val width: Int, val height: Int, private val pixels: FloatArray) {
    fun bitmap(): Bitmap {
        val argb = IntArray(width * height) { i ->
            fun code(k: Int) = (pixels[2 + i * 6 + 3 + k].coerceIn(0f, 1f) * 255f).roundToInt()
            (0xff shl 24) or (code(0) shl 16) or (code(1) shl 8) or code(2)
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Average a 7 x 7 patch in working codes, rather than a single display pixel. */
    fun sample(u: Float, v: Float, look: Look): WhiteBalanceSample {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f)
            return WhiteBalanceSample(error = WhiteBalanceSampleError.INVALID)
        val cx = (u * (width - 1)).roundToInt(); val cy = (v * (height - 1)).roundToInt()
        val sum = DoubleArray(3)
        var count = 0; var clipped = 0
        for (y in maxOf(0, cy - 3)..minOf(height - 1, cy + 3)) {
            for (x in maxOf(0, cx - 3)..minOf(width - 1, cx + 3)) {
                val i = 2 + (y * width + x) * 6
                val r = pixels[i].toDouble(); val g = pixels[i + 1].toDouble(); val b = pixels[i + 2].toDouble()
                if (!r.isFinite() || !g.isFinite() || !b.isFinite() || minOf(r, g, b) < 0)
                    return WhiteBalanceSample(error = WhiteBalanceSampleError.INVALID)
                if (maxOf(r, g, b) >= 0.995) clipped++
                sum[0] += r; sum[1] += g; sum[2] += b; count++
            }
        }
        if (clipped * 5 > count) return WhiteBalanceSample(error = WhiteBalanceSampleError.CLIPPED)
        return solveWhiteBalance(sum[0] / count, sum[1] / count, sum[2] / count, look.palette)
    }

    companion object {
        fun fromNative(values: FloatArray): WhiteBalanceReference? {
            if (values.size < 8 || !values[0].isFinite() || !values[1].isFinite()) return null
            val w = values[0].toInt(); val h = values[1].toInt()
            if (w !in 1..512 || h !in 1..512 || values[0] != w.toFloat() || values[1] != h.toFloat() ||
                values.size != 2 + w * h * 6 || values.any { !it.isFinite() }) return null
            return WhiteBalanceReference(w, h, values)
        }
    }
}

/** Inverse of layer2.cpp's existing gains; no display-space or Kelvin approximation. */
internal fun solveWhiteBalance(r: Double, g: Double, b: Double, palette: Double): WhiteBalanceSample {
    if (!r.isFinite() || !g.isFinite() || !b.isFinite() || minOf(r, g, b) < 0 || !palette.isFinite())
        return WhiteBalanceSample(error = WhiteBalanceSampleError.INVALID)
    if (minOf(r, g, b) < 0.02 || (r + g + b) / 3 < 0.04)
        return WhiteBalanceSample(error = WhiteBalanceSampleError.DARK)
    if (maxOf(r, g, b) >= 0.995) return WhiteBalanceSample(error = WhiteBalanceSampleError.CLIPPED)
    val strength = (palette / 100).coerceIn(0.0, 1.0)
    if (strength <= 0) return WhiteBalanceSample(error = WhiteBalanceSampleError.PALETTE_OFF)
    val requestedT = (b - r) / (0.0018 * (r + b)) / strength
    val t = requestedT.coerceIn(-100.0, 100.0)
    val neutral = (r * (1 + 0.0018 * t * strength) + b * (1 - 0.0018 * t * strength)) / 2
    val requestedG = (1 - neutral / g) / 0.0012 / strength
    val tint = requestedG.coerceIn(-100.0, 100.0)
    return WhiteBalanceSample(WhiteBalancePick(t, tint, abs(t - requestedT) > 1e-7 || abs(tint - requestedG) > 1e-7))
}

internal fun whiteBalanceImageRect(width: Float, height: Float, imageWidth: Int, imageHeight: Int): Rect {
    val scale = min(width / imageWidth, height / imageHeight)
    val w = imageWidth * scale; val h = imageHeight * scale
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

@Composable
internal fun WhiteBalancePickerButton(ui: UiState, vm: EditorViewModel, enabled: Boolean) {
    var open by remember(ui.previewSource) { mutableStateOf(false) }
    QuietButton(tr("白平衡吸管"), enabled = enabled && ui.image != null && !ui.rendering) { open = true }
    if (open) {
        val edit = remember { ui.edit }
        val source = remember { ui.previewSource }
        var loading by remember(edit, source) { mutableStateOf(true) }
        val reference by produceState<WhiteBalanceReference?>(null, edit, source) {
            try { value = vm.whiteBalanceReference(edit, source) } finally { loading = false }
        }
        WhiteBalancePickerDialog(reference, loading, edit.look, onDismiss = { open = false }) {
            vm.applyWhiteBalancePick(edit, source, it); open = false
        }
    }
}

@Composable
internal fun WhiteBalancePickerDialog(reference: WhiteBalanceReference?, loading: Boolean, look: Look,
    onDismiss: () -> Unit, onApply: (WhiteBalancePick) -> Unit) {
    var position by remember(reference) { mutableStateOf<Offset?>(null) }
    val bitmap = remember(reference) { reference?.bitmap() }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    val sample = remember(reference, position, look) {
        position?.let { reference?.sample(it.x, it.y, look) }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Surface).safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("白平衡吸管"), style = MaterialTheme.typography.titleLarge, color = Palette.OnSurface)
            Text(tr("点选应为中性灰的区域，也可拖动重新取样。"), style = MaterialTheme.typography.bodyMedium,
                color = Palette.OnSurfaceSoft)
            Text(tr("取样时暂时隐藏细调效果，应用后保留原来的调色。"),
                style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft)
            Box(Modifier.fillMaxWidth().weight(1f).background(Palette.Surround).testTag("white-balance-photo")
                .semantics { contentDescription = tr("白平衡取样照片") }
                .pointerInput(reference) {
                    if (reference == null) return@pointerInput
                    fun pick(p: Offset) {
                        val r = whiteBalanceImageRect(size.width.toFloat(), size.height.toFloat(), reference.width, reference.height)
                        if (r.contains(p)) position = Offset((p.x - r.left) / r.width, (p.y - r.top) / r.height)
                    }
                    detectTapGestures(onTap = ::pick)
                }.pointerInput(reference) {
                    if (reference == null) return@pointerInput
                    detectDragGestures { change, _ ->
                        val r = whiteBalanceImageRect(size.width.toFloat(), size.height.toFloat(), reference.width, reference.height)
                        if (r.contains(change.position)) {
                            position = Offset((change.position.x - r.left) / r.width, (change.position.y - r.top) / r.height)
                            change.consume()
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        position?.let { p ->
                            val r = whiteBalanceImageRect(size.width, size.height, bitmap.width, bitmap.height)
                            val point = Offset(r.left + p.x * r.width, r.top + p.y * r.height)
                            drawCircle(Color.Black, 15.dp.toPx(), point, style = Stroke(4.dp.toPx()))
                            drawCircle(Color.White, 15.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                            drawLine(Color.White, point - Offset(7.dp.toPx(), 0f), point + Offset(7.dp.toPx(), 0f), 1.dp.toPx())
                            drawLine(Color.White, point - Offset(0f, 7.dp.toPx()), point + Offset(0f, 7.dp.toPx()), 1.dp.toPx())
                        }
                    }
                } else Text(if (loading) tr("正在准备取样…") else tr("画面尚未准备好，请返回后重试"), color = Palette.OnSurfaceSoft)
            }
            val message = when (sample?.error) {
                WhiteBalanceSampleError.DARK -> tr("这块区域太暗，请换一个较亮的灰色区域")
                WhiteBalanceSampleError.CLIPPED -> tr("这块区域高光已溢出，请换一个灰色区域")
                WhiteBalanceSampleError.INVALID -> tr("这块区域无法取样，请换一个位置")
                WhiteBalanceSampleError.PALETTE_OFF -> tr("请先提高调色盘的浓淡，再使用吸管")
                null -> sample?.pick?.let {
                    String.format(Locale.ROOT, tr("色温 %+.1f · 色调 %+.1f"), it.temperature, it.tint) +
                        if (it.limited) "\n" + tr("偏色超出可调范围，已调整到最接近的值") else ""
                } ?: tr("只校正色温和色调，确认后应用，可撤销。")
            }
            Text(message, style = Readout, color = if (sample?.error != null || sample?.pick?.limited == true) Palette.Warning else Palette.OnSurface)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(tr("取消")) }
                TextButton(enabled = sample?.pick != null, onClick = { sample?.pick?.let(onApply) }) { Text(tr("应用白平衡")) }
            }
        }
    }
}
