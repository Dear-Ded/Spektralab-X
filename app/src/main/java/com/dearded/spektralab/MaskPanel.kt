package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the 蒙版 section on the 调整 page and the overlay on the photo share:
 * the editor shows one of each. The brush settings are the tool's, not the
 * photo's, so they live here and not in the Look.
 */
internal object MaskUi {
    /** The section is expanded on screen: the overlay draws and takes its gestures. */
    var open by mutableStateOf(false)
    var selected by mutableIntStateOf(0)
    var showCover by mutableStateOf(true)
    var erase by mutableStateOf(false)
    var brushSize by mutableFloatStateOf(6f)    // % of the long edge
    var brushSoft by mutableFloatStateOf(50f)
    var brushFlow by mutableFloatStateOf(60f)
    /** Where the overlay sends its edits; set while the section is composed. */
    var vm: EditorViewModel? = null
}

private fun EditorViewModel.setMasks(f: (List<EditMask>) -> List<EditMask>) {
    val e = ui.value.edit
    update(e.copy(look = e.look.copy(masks = f(e.look.masks))))
}

private fun EditorViewModel.setMask(i: Int, f: (EditMask) -> EditMask) =
    setMasks { l -> if (i in l.indices) l.toMutableList().also { it[i] = f(it[i]) } else l }

/**
 * 局部蒙版 (upstream SpektraLab's masks, Model/Mask.swift): each mask is one
 * region — a linear or radial gradient, or brush strokes — with its own set
 * of upstream's local adjustments. Layer 2, so every change re-packs the last
 * render. Collapsed by default under the 调整 page's segments.
 */
@Composable
internal fun MaskSection(ui: UiState, vm: EditorViewModel, enabled: Boolean) {
    val masks = ui.edit.look.masks
    var open by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(vm) {
        MaskUi.vm = vm
        onDispose { MaskUi.vm = null; MaskUi.open = false }
    }
    SideEffect { MaskUi.open = open && enabled }
    val sel = MaskUi.selected.coerceIn(0, max(0, masks.size - 1))

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("局部蒙版", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { open = !open }) {
            Text(when { open -> "收起"; masks.isEmpty() -> "展开"; else -> "展开（${masks.size} 个）" })
        }
    }
    Hint("只调画面的一部分：拉一道渐变、圈出一块，或用手指涂。每个蒙版带自己的一组调整。")
    if (!open) return

    if (masks.isNotEmpty()) Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        masks.forEachIndexed { i, m ->
            SelectPill(selected = i == sel, onClick = { MaskUi.selected = i },
                label = { Text("${i + 1} · ${m.kind.label}") }, enabled = enabled, edited = m.active)
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MaskKind.entries.forEach { k ->
            SelectPill(selected = false, onClick = {
                vm.setMasks { it + EditMask.make(k) }
                MaskUi.selected = masks.size
            }, label = { Text("+ ${k.label}") }, enabled = enabled && masks.size < LocalEdits.MAX_MASKS)
        }
    }
    Hint("线性渐变适合天空和地平线，径向渐变圈出主体，画笔涂出任意形状。最多 ${LocalEdits.MAX_MASKS} 个。")
    if (masks.isEmpty()) return

    val m = masks[sel]
    fun set(f: (EditMask) -> EditMask) = vm.setMask(sel, f)
    Hint(when (m.kind) {
        MaskKind.LINEAR -> "在照片上拖两端的圆点改方向和过渡宽度，拖中间的圆点整体移动。实线一端是全部效果，虚线一端没有效果。"
        MaskKind.RADIAL -> "在照片上拖中心移动，拖椭圆边上的两个圆点改大小和角度。"
        MaskKind.BRUSH -> "单指在照片上涂；两指同时落下照常缩放照片。"
    })
    SwitchRow("显示范围", MaskUi.showCover, enabled) { MaskUi.showCover = it }
    Hint("照片上用红色标出这个蒙版作用的地方。只在编辑时显示，不会导出。")
    SwitchRow("反选", m.inverted, enabled) { on -> set { it.copy(inverted = on) } }
    Hint("改为作用在区域以外的地方。")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = enabled && masks.size < LocalEdits.MAX_MASKS, onClick = {
            vm.setMasks { it + m.copy(inverted = !m.inverted, tone = MaskTone()) }
            MaskUi.selected = masks.size
        }) { Text("复制并反选") }
        TextButton(enabled = enabled, onClick = {
            vm.setMasks { l -> l.filterIndexed { j, _ -> j != sel } }
            MaskUi.selected = max(0, sel - 1)
        }) { Text("删除这个蒙版", color = Palette.Warning) }
    }
    Hint("复制并反选：做出互补的一对，例如天空压暗、地面提亮。")

    val drag = vm::setInteracting
    LabeledSlider("总量", m.amount, 0.0, 100.0, 100.0, enabled, "%.0f", onDrag = drag) { v -> set { it.copy(amount = v) } }
    Hint("整个蒙版的不透明度：100 是满额，0 等于关掉。")
    when (m.kind) {
        MaskKind.RADIAL -> {
            LabeledSlider("羽化", m.feather, 0.0, 100.0, 50.0, enabled, "%.0f", onDrag = drag) { v -> set { it.copy(feather = v) } }
            Hint("边缘过渡的宽度：0 是硬边，100 从中心就开始减弱。")
        }
        MaskKind.BRUSH -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                SelectPill(selected = !MaskUi.erase, onClick = { MaskUi.erase = false }, label = { Text("涂抹") }, enabled = enabled)
                SelectPill(selected = MaskUi.erase, onClick = { MaskUi.erase = true }, label = { Text("擦除") }, enabled = enabled)
            }
            Hint("涂抹加进蒙版，擦除从蒙版里去掉涂过的地方。")
            LabeledSlider("画笔大小", MaskUi.brushSize.toDouble(), 1.0, 30.0, 6.0, enabled, "%.0f%%") { MaskUi.brushSize = it.toFloat() }
            Hint("画笔直径的一半，按照片长边的百分比，放大照片时不变。")
            LabeledSlider("柔和度", MaskUi.brushSoft.toDouble(), 0.0, 100.0, 50.0, enabled, "%.0f") { MaskUi.brushSoft = it.toFloat() }
            Hint("笔触边缘多软：0 是硬边，100 从中心就开始变淡。")
            LabeledSlider("流量", MaskUi.brushFlow.toDouble(), 5.0, 100.0, 60.0, enabled, "%.0f") { MaskUi.brushFlow = it.toFloat() }
            Hint("每一笔落下多少；来回涂会越涂越满。")
            TextButton(enabled = enabled && m.stamps.isNotEmpty(), onClick = { set { it.copy(stamps = emptyList()) } }) {
                Text("清除笔画")
            }
        }
        MaskKind.LINEAR -> Unit
    }

    Text("这个蒙版的调整", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    val t = m.tone
    fun tone(f: (MaskTone) -> MaskTone) = set { it.copy(tone = f(it.tone)) }
    LabeledSlider("曝光", t.exposure, -3.0, 3.0, 0.0, enabled, "%+.2f EV", onDrag = drag) { v -> tone { it.copy(exposure = v) } }
    Hint("区域整体变亮或变暗，单位是档。")
    LabeledSlider("对比度", t.contrast, -50.0, 50.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(contrast = v) } }
    Hint("区域里亮的更亮、暗的更暗；负值变平。")
    LabeledSlider("亮度", t.brightness, -50.0, 50.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(brightness = v) } }
    Hint("提亮或压暗中间调，高光和阴影动得少。")
    LabeledSlider("高光", t.highlights, -100.0, 100.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(highlights = v) } }
    Hint("只调区域里的亮部；负值找回天空的层次。")
    LabeledSlider("阴影", t.shadows, -100.0, 100.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(shadows = v) } }
    Hint("只调区域里的暗部；正值提亮暗处。")
    LabeledSlider("黑场", t.blackPoint, 0.0, 50.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(blackPoint = v) } }
    Hint("把区域最暗的部分压成黑色，画面更扎实。")
    LabeledSlider("白场", t.whitePoint, 0.0, 50.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(whitePoint = v) } }
    Hint("把区域最亮的部分推成白色。")
    LabeledSlider("色温", t.temperature, -100.0, 100.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(temperature = v) } }
    Hint("区域偏冷（负）或偏暖（正）。")
    LabeledSlider("色调", t.tint, -100.0, 100.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(tint = v) } }
    Hint("区域偏绿（负）或偏品红（正）。")
    LabeledSlider("饱和度", t.saturation, -100.0, 100.0, 0.0, enabled, "%+.0f", onDrag = drag) { v -> tone { it.copy(saturation = v) } }
    Hint("区域颜色更浓或更淡；-100 是黑白。")
    TextButton(enabled = enabled && !t.neutral, onClick = { set { it.copy(tone = MaskTone()) } }) { Text("重置这个蒙版的调整") }
}

/**
 * 灰尘与划痕 on the 效果 page: scan dust, hairs and scratches, procedural from
 * a seed (layer2_local.hpp), so the same photo exports the same specks every
 * time. Layer 2 like the masks: a drag re-packs, nothing develops again.
 */
@Composable
internal fun DustSection(look: Look, enabled: Boolean, onDrag: (Boolean) -> Unit, onChange: (Look) -> Unit) {
    val d = look.dust
    fun set(next: Dust) = onChange(look.copy(dust = next))
    Spacer(Modifier.height(8.dp))
    SwitchRow("灰尘与划痕", d.strength > 0, enabled) { on -> set(d.copy(strength = if (on) 50.0 else 0.0)) }
    Hint("扫描时落在底片、相纸和玻璃上的灰尘、毛发和划痕。同一组参数，每次导出完全一样。")
    if (d.strength <= 0) return
    LabeledSlider("灰尘强度", d.strength, 1.0, 100.0, 50.0, enabled, "%.0f", onDrag = onDrag) { set(d.copy(strength = it)) }
    Hint("灰尘、毛发和划痕有多明显。")
    LabeledSlider("灰尘数量", d.count, 0.0, 100.0, 35.0, enabled, "%.0f", onDrag = onDrag) { set(d.copy(count = it)) }
    Hint("画面里有多少颗灰尘和毛发；0 只留划痕。")
    LabeledSlider("灰尘大小", d.size, 0.0, 100.0, 50.0, enabled, "%.0f", onDrag = onDrag) { set(d.copy(size = it)) }
    Hint("所有颗粒一起放大或缩小；50 接近 35 mm 底片上的真实尺寸。")
    LabeledSlider("黑点比例", d.dark, 0.0, 100.0, 20.0, enabled, "%.0f%%", onDrag = onDrag) { set(d.copy(dark = it)) }
    Hint("白点是底片上的灰尘，黑点是相纸或扫描玻璃上的灰尘。")
    LabeledSlider("划痕", d.scratches, 0.0, 100.0, 0.0, enabled, "%.0f", onDrag = onDrag) { set(d.copy(scratches = it)) }
    Hint("沿胶片走向的细长划痕有多少条；0 没有划痕。")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("种子 ${d.seed}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(enabled = enabled, onClick = { set(d.copy(seed = kotlin.random.Random.nextInt(1, Dust.MAX_SEED))) }) {
            Text("换一组")
        }
    }
    Hint("决定灰尘落在哪里。种子不变，结果永远相同；换一组只换位置。")
    TextButton(enabled = enabled && d != Dust(strength = d.strength), onClick = { set(Dust(strength = d.strength)) }) {
        Text("恢复默认")
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))
}

// --- the overlay on the photo ------------------------------------------------

/**
 * Where the print sits in the preview box, as FramedImage lays it out, and
 * the long-edge units layer2_local.hpp measures in: one unit is the print's
 * long side in pixels, the same on both axes.
 */
private class Place(val left: Float, val top: Float, val width: Float, val height: Float, val aspect: Double) {
    val long get() = max(width, height)
    fun px(x: Double, y: Double) = Offset(left + x.toFloat() * long, top + y.toFloat() * long)
    fun le(o: Offset) = ((o.x - left) / long).toDouble() to ((o.y - top) / long).toDouble()
    fun uv(o: Offset) = ((o.x - left) / width).toDouble() to ((o.y - top) / height).toDouble()
    fun at(u: Double, v: Double) = Offset(left + u.toFloat() * width, top + v.toFloat() * height)
}

private enum class Grip { A, B, MID, CENTRE, RX, RY }

private fun grips(m: EditMask, p: Place): List<Pair<Grip, Offset>> = when (m.kind) {
    MaskKind.LINEAR -> listOf(Grip.A to p.at(m.ax, m.ay), Grip.B to p.at(m.bx, m.by),
        Grip.MID to p.at((m.ax + m.bx) / 2, (m.ay + m.by) / 2))
    MaskKind.RADIAL -> {
        val (cx, cy) = LocalEdits.toLongEdge(m.ax, m.ay, p.aspect)
        val a = Math.toRadians(m.angle)
        listOf(Grip.CENTRE to p.px(cx, cy), Grip.RX to p.px(cx + m.rx * cos(a), cy + m.rx * sin(a)),
            Grip.RY to p.px(cx - m.ry * sin(a), cy + m.ry * cos(a)))
    }
    MaskKind.BRUSH -> emptyList()
}

private fun dragged(start: EditMask, grip: Grip, from: Offset, to: Offset, p: Place): EditMask {
    fun c(x: Double) = x.coerceIn(-0.5, 1.5)
    val (u, v) = p.uv(to)
    val (u0, v0) = p.uv(from)
    val du = u - u0
    val dv = v - v0
    return when (grip) {
        Grip.A -> start.copy(ax = c(u), ay = c(v))
        Grip.B -> start.copy(bx = c(u), by = c(v))
        Grip.MID -> start.copy(ax = c(start.ax + du), ay = c(start.ay + dv), bx = c(start.bx + du), by = c(start.by + dv))
        Grip.CENTRE -> start.copy(ax = c(start.ax + du), ay = c(start.ay + dv))
        Grip.RX, Grip.RY -> {
            val (cx, cy) = LocalEdits.toLongEdge(start.ax, start.ay, p.aspect)
            val (fx, fy) = p.le(to)
            val dx = fx - cx
            val dy = fy - cy
            if (grip == Grip.RX) start.copy(rx = hypot(dx, dy).coerceIn(0.01, 3.0), angle = Math.toDegrees(atan2(dy, dx)))
            else {
                val a = Math.toRadians(start.angle)
                start.copy(ry = abs(-sin(a) * dx + cos(a) * dy).coerceIn(0.01, 3.0))
            }
        }
    }
}

/** The selected mask's coverage as a small red picture, scaled up under the handles. */
private fun coverImage(m: EditMask, aspect: Double): ImageBitmap {
    val long = if (m.kind == MaskKind.BRUSH) 96 else 160
    val bw = if (aspect >= 1) long else max(1, (long * aspect).roundToInt())
    val bh = if (aspect >= 1) max(1, (long / aspect).roundToInt()) else long
    val px = IntArray(bw * bh)
    val k = (m.amount / 100).coerceIn(0.0, 1.0)
    // The brush in long-edge units once, not per pixel.
    val st = if (m.kind == MaskKind.BRUSH) m.stamps.map {
        val (x, y) = LocalEdits.toLongEdge(it.x, it.y, aspect)
        doubleArrayOf(x, y, it.radius.coerceIn(1e-4, 1.0), it.softness.coerceIn(0.001, 1.0), it.flow, if (it.erase) 1.0 else 0.0)
    } else emptyList()
    for (y in 0 until bh) for (x in 0 until bw) {
        val u = (x + 0.5) / bw
        val v = (y + 0.5) / bh
        val c = if (m.kind != MaskKind.BRUSH) LocalEdits.coverage(m, u, v, aspect) else {
            val (lx, ly) = LocalEdits.toLongEdge(u, v, aspect)
            var acc = 0.0
            for (s in st) {
                val d2 = (lx - s[0]) * (lx - s[0]) + (ly - s[1]) * (ly - s[1])
                if (d2 >= s[2] * s[2]) continue
                val t = ((sqrt(d2) / s[2] - (1 - s[3])) / s[3]).coerceIn(0.0, 1.0)
                val f = s[4] * (1 - t * t * (3 - 2 * t))
                acc = if (s[5] > 0) acc * (1 - f) else acc + f * (1 - acc)
            }
            (if (m.inverted) 1 - acc else acc).coerceIn(0.0, 1.0)
        }
        val a = (c * k * 0.45 * 255).roundToInt().coerceIn(0, 255)
        px[y * bw + x] = (a shl 24) or 0xD92626
    }
    return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Over the print while the 蒙版 section is open: the selected mask's coverage
 * in red, its handles, and the brush. Placed inside the zoomed box, so it
 * moves and scales with the photo. It takes a gesture only when it starts on a
 * handle (or paints with one finger in brush mode); every other gesture goes
 * on to the photo (UI2.md 3.3). Drawn in Compose, never by Layer 2, so the red
 * can never reach an export.
 */
@Composable
internal fun MaskOverlay(ui: UiState, modifier: Modifier) {
    val vm = MaskUi.vm
    val image = ui.image
    val masks = ui.edit.look.masks
    val i = MaskUi.selected
    if (vm == null || image == null || !MaskUi.open || i !in masks.indices) return
    val mask = masks[i]
    val w = image.width
    val h = image.height
    val aspect = w.toDouble() / h
    val layout = remember(ui.edit.frame, w, h) { ui.edit.frame.layout(w, h) }
    val showCover = MaskUi.showCover
    val cover by produceState<ImageBitmap?>(null, mask, aspect, showCover) {
        value = if (showCover) withContext(Dispatchers.Default) { coverImage(mask, aspect) } else null
    }
    val latest by rememberUpdatedState(mask)
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val grab = with(density) { 28.dp.toPx() }
    val dot = with(density) { 8.dp.toPx() }
    val line = with(density) { 1.5.dp.toPx() }

    fun place(sw: Float, sh: Float): Place {
        val k = min(sw / layout.width, sh / layout.height)
        val ox = (sw - layout.width * k) / 2
        val oy = (sh - layout.height * k) / 2
        return Place(ox + layout.x * k, oy + layout.y * k, w * k, h * k, aspect)
    }

    val gestures = Modifier.pointerInput(i, mask.kind, layout, w, h) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val p = place(size.width.toFloat(), size.height.toFloat())
            val start = latest
            if (start.kind == MaskKind.BRUSH) {
                var painting = false
                var last = p.le(down.position)
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } > 1) break   // two fingers: the photo's pinch
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val fresh = ArrayList<BrushStamp>()
                        val r = MaskUi.brushSize / 100.0
                        fun dab(x: Double, y: Double) {
                            val (u, v) = LocalEdits.fromLongEdge(x, y, aspect)
                            fresh += BrushStamp(u, v, r, MaskUi.brushSoft / 100.0, MaskUi.brushFlow / 100.0, MaskUi.erase)
                        }
                        if (!painting) {
                            if ((ch.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                            painting = true
                            vm.setInteracting(true)
                            dab(last.first, last.second)
                        }
                        ev.changes.forEach { it.consume() }
                        cursor = ch.position
                        // Dabs a third of a radius apart along the path, so a fast stroke stays a stroke.
                        val (x, y) = p.le(ch.position)
                        val gap = max(r * 0.3, 1e-4)
                        val dist = hypot(x - last.first, y - last.second)
                        val n = floor(dist / gap).toInt()
                        for (k in 1..n) {
                            val f = k * gap / dist
                            val px = last.first + (x - last.first) * f
                            val py = last.second + (y - last.second) * f
                            dab(px, py)
                            if (k == n) last = px to py
                        }
                        if (fresh.isNotEmpty()) vm.setMask(i) { m ->
                            if (m.stamps.size >= LocalEdits.MAX_STAMPS) m
                            else m.copy(stamps = (m.stamps + fresh).take(LocalEdits.MAX_STAMPS))
                        }
                    }
                } finally {
                    if (painting) vm.setInteracting(false)
                    cursor = null
                }
                return@awaitEachGesture
            }
            val grip = grips(start, p).map { (g, o) -> g to (o - down.position).getDistance() }
                .filter { it.second <= grab }.minByOrNull { it.second }?.first ?: return@awaitEachGesture
            down.consume()
            vm.setInteracting(true)
            try {
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    ev.changes.forEach { it.consume() }
                    if (!ch.pressed) break
                    val next = dragged(start, grip, down.position, ch.position, p)
                    vm.setMask(i) { next }
                }
            } finally {
                vm.setInteracting(false)
            }
        }
    }

    Canvas(modifier.then(gestures)) {
        val p = place(size.width, size.height)
        cover?.let {
            drawImage(it, dstOffset = IntOffset(p.left.roundToInt(), p.top.roundToInt()),
                dstSize = IntSize(p.width.roundToInt().coerceAtLeast(1), p.height.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Low)
        }
        when (mask.kind) {
            MaskKind.LINEAR -> clipRect(p.left, p.top, p.left + p.width, p.top + p.height) {
                val a = p.at(mask.ax, mask.ay)
                val b = p.at(mask.bx, mask.by)
                val d = b - a
                val len = d.getDistance().coerceAtLeast(1f)
                val n = Offset(-d.y / len, d.x / len) * (p.long * 2)
                guide(a - n, a + n, line, dashed = true)
                guide((a + b) / 2f - n, (a + b) / 2f + n, line, dashed = true)
                guide(b - n, b + n, line, dashed = false)
            }
            MaskKind.RADIAL -> {
                val (cx, cy) = LocalEdits.toLongEdge(mask.ax, mask.ay, aspect)
                val a = Math.toRadians(mask.angle)
                fun ellipse(k: Double) = Path().apply {
                    for (s in 0..64) {
                        val th = s / 64.0 * 2 * Math.PI
                        val ex = k * mask.rx * cos(th)
                        val ey = k * mask.ry * sin(th)
                        val o = p.px(cx + ex * cos(a) - ey * sin(a), cy + ex * sin(a) + ey * cos(a))
                        if (s == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                }
                outline(ellipse(1.0), line, dashed = false)
                val inner = 1 - (mask.feather / 100).coerceIn(0.0, 1.0)
                if (inner > 0.02) outline(ellipse(inner), line, dashed = true)
            }
            MaskKind.BRUSH -> cursor?.let {
                val r = MaskUi.brushSize / 100f * p.long
                drawCircle(Color.Black.copy(alpha = 0.5f), r, it, style = Stroke(line * 2))
                drawCircle(Color.White, r, it, style = Stroke(line))
            }
        }
        for ((_, o) in grips(mask, p)) {
            drawCircle(Color.Black.copy(alpha = 0.45f), dot + line * 1.5f, o)
            drawCircle(Color.White, dot, o, style = Stroke(line * 1.5f))
            drawCircle(Color.White, dot * 0.4f, o)
        }
    }
}

private fun DrawScope.guide(from: Offset, to: Offset, width: Float, dashed: Boolean) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 6, width * 4)) else null
    drawLine(Color.Black.copy(alpha = 0.45f), from, to, width * 2.5f, pathEffect = effect)
    drawLine(Color.White, from, to, width, pathEffect = effect)
}

private fun DrawScope.outline(path: Path, width: Float, dashed: Boolean) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 6, width * 4)) else null
    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(width * 2.5f, pathEffect = effect))
    drawPath(path, Color.White, style = Stroke(width, pathEffect = effect))
}
