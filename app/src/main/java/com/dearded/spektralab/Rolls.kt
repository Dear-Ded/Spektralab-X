package com.dearded.spektralab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How a stock is processed, for the 6 dp dot and the edge print's colour
 * (docs/UI.md appendix B). The process carries the film feel, not any maker's
 * colours or type.
 */
enum class FilmProcess(val label: String, val color: Color) {
    C41("C–41", Color(0xFFB07A4E)),
    E6("E–6", Color(0xFF8FA0B2)),
    BW("B&W", Color(0xFF8E8E8E)),
    ECN2("ECN–2", Color(0xFF4A4A4A)),
    NONE("未冲洗", Color(0xFF3A3A40));

    companion object {
        fun of(stock: Stock?): FilmProcess = when {
            stock == null -> NONE
            stock.bw -> BW
            stock.positive -> E6
            // Motion-picture negative: the Vision3 stocks.
            stock.id.contains("vision3") -> ECN2
            else -> C41
        }
    }
}

/**
 * One roll (docs/UI.md appendix D): frames developed through one film on one
 * day, in shooting order, at most [LENGTH]; a 37th frame starts 卷 2.
 * Frames not edited yet share a 未冲洗 roll per capture day.
 */
@Immutable
data class Roll(
    val id: String,
    val film: String,
    /** "Portra 400 风格", plain text: never a maker's logo or edge typography. */
    val name: String,
    val process: FilmProcess,
    val iso: String,
    val day: Long,
    val part: Int,
    val frames: List<Frame>,
    /** The film's short name as the edge prints it ("" for an undeveloped roll). */
    val model: String = "",
    /** A roll the user packed (ManualRolls.kt); null for an automatic one. */
    val manualId: String? = null,
) {
    val title get() = if (part > 1) "$name · 卷 $part" else name
    val manual get() = manualId != null

    /** The card's edge line: process, speed, date, frames used ("C–41 · 400 · 11.03 · 24/36"). */
    val edgeLine get() = listOf(
        process.label, iso, SimpleDateFormat("MM.dd", Locale.ROOT).format(Date(day)), "${frames.size}/$LENGTH",
        if (manual) "手动" else "",
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    /** The model as the strip's edge prints it ("" for an undeveloped roll). */
    val edgeModel get() = model

    companion object {
        const val LENGTH = 36
    }
}

fun shortFilmName(label: String) = label.removePrefix("Kodak ").removePrefix("Fujifilm ").removePrefix("Ilford ")

/** A 135 edge's frame numbers: 00, 1, 2 ... */
fun frameNumber(index: Int) = if (index == 0) "00" else "$index"

/** The half mark after a frame, between it and the next: 00A, 1A ... (UI.md appendix B: 00 → 00A → 1 → 1A). */
fun halfMark(index: Int) = frameNumber(index) + "A"

/** When a frame counts as developed: its last edit, else when it was shot, else when it was added. */
private fun developedAt(f: Frame) = when {
    f.edited && f.editedAt > 0 -> f.editedAt
    f.date > 0 -> f.date
    else -> f.addedAt
}

// The speed is the label's last number of two or more digits, a letter after it allowed
// (Portra 800 Push 1 is 800, Vision3 500T is 500, Pro 400H is 400).
private fun isoOf(stock: Stock?): String = stock?.label?.let { l ->
    Regex("""\b(\d{2,4})[A-Z]?\b""").findAll(l).lastOrNull()?.groupValues?.get(1)
}.orEmpty()

private fun modelOf(film: String, stock: Stock?) = when {
    film.isEmpty() -> ""
    stock != null -> shortFilmName(stock.label)
    else -> film
}

/**
 * The rolls, newest first. The user's own rolls ([manual]) come first and
 * keep their frames in the order packed; every other frame goes into the
 * automatic rolls: grouped by film and day, each group in shooting order and
 * cut every 36 frames. Picks and stars stay filters inside a roll, never
 * rolls of their own.
 */
fun rollsOf(frames: List<Frame>, catalog: Catalog, manual: List<ManualRoll> = emptyList()): List<Roll> {
    val rolls = ArrayList<Roll>()
    val byId = frames.associateBy { it.id }
    val claimed = HashSet<String>()
    for (m in manual) {
        // A frame is in one roll only; one taken off the table drops out.
        val members = m.frames.mapNotNull { byId[it] }.filter { claimed.add(it.id) }
        if (members.isEmpty()) continue
        // The roll's film is the one most of its developed frames went through.
        val film = members.filter { it.edited && it.film.isNotEmpty() }.groupingBy { it.film }.eachCount()
            .maxByOrNull { it.value }?.key.orEmpty()
        val stock = catalog.film(film)
        rolls += Roll("m|${m.id}", film, m.name, FilmProcess.of(stock), isoOf(stock),
            members.minOf { developedAt(it) }, 1, members, modelOf(film, stock), manualId = m.id)
    }
    val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    val groups = frames.filter { it.id !in claimed }
        .groupBy { f -> (if (f.edited) f.film else "") to dayFormat.format(Date(developedAt(f))) }
    for ((key, members) in groups) {
        val (film, day) = key
        val stock = catalog.film(film)
        val model = modelOf(film, stock)
        val name = if (film.isEmpty()) "未冲洗" else "$model 风格"
        val ordered = members.sortedWith(compareBy<Frame>({ it.date }, { it.addedAt }))
        ordered.chunked(Roll.LENGTH).forEachIndexed { i, chunk ->
            rolls += Roll("$film|$day|${i + 1}", film, name, FilmProcess.of(stock), isoOf(stock),
                chunk.minOf { developedAt(it) }, i + 1, chunk, model)
        }
    }
    return rolls.sortedWith(compareByDescending<Roll> { r -> r.frames.maxOf { developedAt(it) } }.thenBy { it.part })
}

/** The edge print's face: narrow, tabular, small. Barlow Condensed per UI.md once it is bundled. */
internal val EdgeType = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum", letterSpacing = 0.4.sp)

/**
 * The roll list: one plain card per roll, no film-can cabinet (UI.md appendix
 * D). It is the table's second view, not a page under the grid: the same
 * transparent head and floating bar, with 按卷 chosen in the bar's track.
 * Back leaves for the grid's own parent (the editor), as switching to 网格 is
 * a view change and not a step deeper.
 */
@Composable
fun RollList(table: LightTableViewModel, rolls: List<Roll>, onOpen: (Roll) -> Unit, onGrid: () -> Unit, onBack: () -> Unit) {
    // Back is LightTableNav's SeekBack: it scrubs the table back to the editor (UI2.md 3.8).
    val add = rememberAddPhotos(table)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HeadHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingBarSpace
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (rolls.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("观片台上还没有照片", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Hint("用右下角的“+”放上来，冲洗过的照片会按胶片和日期自动分卷。")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + 8.dp),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                items(rolls, key = { it.id }, contentType = { "roll" }) { roll -> RollCard(roll) { onOpen(roll) } }
            }
        }
        TableHead(
            title = "按卷查看",
            subtitle = "${rolls.size} 卷 · 按胶片和日期自动分卷",
            onBack = onBack,
        )
        TableBar(rolls = true, onGrid = onGrid, onRolls = {}, add = add, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RollCard(roll: Roll, onClick: () -> Unit) {
    Surface(
        color = Palette.Panel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .semantics { contentDescription = "${roll.title}，${roll.frames.size} 张，${roll.edgeLine}" },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(roll.process.color, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(roll.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, letterSpacing = 0.sp))
            }
            Spacer(Modifier.height(2.dp))
            // On the card the dot carries the process colour; the line stays readable grey.
            Text(roll.edgeLine, style = EdgeType, color = Palette.OnSurfaceSoft)
            Spacer(Modifier.height(10.dp))
            val cell = 56.dp
            val px = with(LocalDensity.current) { cell.roundToPx() }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (f in roll.frames.take(MAX_PREVIEW)) {
                    val thumb by rememberThumbnail(f, px)
                    Box(Modifier.size(cell).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh)) {
                        thumb?.let { b ->
                            val image = remember(b) { b.asImageBitmap() }
                            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_PREVIEW = 5
