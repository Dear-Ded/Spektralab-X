package com.dearded.spektralab

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A one-tap style: a film and paper with their print and effect settings, plus
 * a Layer 2 finish. The film and paper carry the colour science; the finish
 * only adds what a lab or a photographer's printing did afterwards, the way
 * Dehancer keeps film and print apart. Every style is an imitation of a
 * described look, not a reproduction of anyone's product.
 *
 * Filter shifts are in CC (the engine's filter_shift_scale is 40 CC a unit):
 * +Y prints bluer, +M greener, negative values warmer / more magenta.
 */
data class Style(
    val id: String,
    val name: String,
    val group: String,
    val film: String,
    val paper: String,
    val exposureEv: Double = 0.0,
    val printExposure: Double = 1.0,
    val yCc: Double = 0.0,
    val mCc: Double = 0.0,
    val formatMm: Double = 36.0,
    val grain: Double = 1.0,
    val halation: Double = 1.0,
    val glare: Double = 1.0,
    val look: Look = Look.NEUTRAL,
) {
    /** [edit] in this style; per-photo geometry, white balance and borders stay. */
    fun applyTo(edit: Edit, catalog: Catalog): Edit? {
        val stock = catalog.film(film) ?: return null
        val base = edit.copy(film = film, scanFilm = stock.positive,
            paper = if (catalog.paper(paper) != null) paper else stock.targetPrint ?: edit.paper)
        return catalog.normalize(base.copy(
            exposureEv = exposureEv, printExposure = printExposure,
            yShift = (yCc / Edit.FILTER_SHIFT_CC).coerceIn(-1.0, 1.0),
            mShift = (mCc / Edit.FILTER_SHIFT_CC).coerceIn(-1.0, 1.0),
            filmFormatMm = formatMm,
            grainActive = grain > 0, grainAmount = grain.coerceAtLeast(0.0),
            halationActive = halation > 0, halationAmount = halation.coerceAtLeast(0.0),
            glareActive = glare > 0, glareAmount = glare.coerceAtLeast(0.0),
            look = look,
        ))
    }

    fun matches(edit: Edit, catalog: Catalog) = applyTo(edit, catalog) == edit

    companion object {
        // Lab scanner signatures, as the labs describe them (Carmencita, theFINDlab,
        // Richard Photo Lab): Frontier punchy and golden with cyan shadows, Noritsu
        // flatter with lifted blacks, neutral shadows and more highlight room.
        private val FRONTIER = Look(contrast = 8.0, saturation = 10.0,
            shadowZone = Zone(hue = 190.0, sat = 10.0), highlightZone = Zone(hue = 45.0, sat = 8.0))
        private val NORITSU = Look(contrast = -6.0, saturation = -8.0, fade = 22.0, highlights = -15.0,
            midZone = Zone(hue = 15.0, sat = 6.0))

        val ALL = listOf(
            // 实验室：常见冲扫店的出片风格
            Style("lab_gold", "暖金冲扫", "实验室", "kodak_portra_400", "kodak_supra_endura", exposureEv = 1.0, grain = 0.8, look = FRONTIER),
            Style("lab_airy", "粉调冲扫", "实验室", "kodak_portra_400", "kodak_portra_endura", exposureEv = 1.3, printExposure = 0.85, mCc = -3.0, grain = 0.8, look = NORITSU),
            Style("lab_wedding", "明亮婚礼", "实验室", "fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", exposureEv = 2.0, printExposure = 0.8, yCc = -2.0, mCc = -2.0, formatMm = 56.0, grain = 0.6, halation = 0.8, look = NORITSU.copy(fade = 18.0)),
            Style("lab_vivid", "风光冲扫", "实验室", "kodak_ektar_100", "kodak_ektacolor_edge", exposureEv = 0.3, printExposure = 1.05, grain = 0.5, look = FRONTIER.copy(saturation = 6.0)),
            Style("lab_daily", "日常彩负", "实验室", "kodak_ultramax_400", "kodak_supra_endura", exposureEv = 0.7, grain = 1.1, look = FRONTIER.copy(contrast = 4.0)),
            // 负片
            Style("neg_amber", "琥珀记忆", "负片", "kodak_gold_200", "kodak_portra_endura", exposureEv = 0.7, yCc = -4.0, grain = 0.9, look = Look(highlightZone = Zone(hue = 40.0, sat = 14.0), shadowZone = Zone(hue = 25.0, sat = 6.0))),
            Style("neg_hard", "硬负片", "负片", "fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", exposureEv = 0.7, printExposure = 1.15, mCc = 2.0, look = Look(contrast = 10.0, hsl = Look.NEUTRAL.hsl.toMutableList().also { it[0] = Band(hue = 8.0, sat = -12.0) })),
            Style("neg_cool", "冷负片", "负片", "fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", exposureEv = 0.3, printExposure = 1.1, yCc = 4.0, mCc = 2.0, look = Look(contrast = 6.0, saturation = -15.0, shadowZone = Zone(hue = 190.0, sat = 12.0))),
            Style("neg_soft", "柔和写实", "负片", "fujifilm_c200", "fujifilm_crystal_archive_typeii", exposureEv = 0.7, grain = 0.8, look = Look(vibrance = -18.0, shadows = 10.0)),
            Style("neg_portrait", "柔肤人像", "负片", "fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", exposureEv = 1.0, printExposure = 0.9, yCc = -1.0, mCc = -1.0, formatMm = 56.0, grain = 0.6, halation = 0.8, look = Look(contrast = -10.0)),
            Style("neg_natural", "自然", "负片", "kodak_portra_160", "kodak_endura_premier", exposureEv = 0.5, formatMm = 70.0, grain = 0.4, halation = 0.5),
            Style("neg_classic", "深影经典", "负片", "kodak_portra_400", "kodak_ektacolor_edge", printExposure = 1.2, grain = 0.8, look = Look(vignette = -30.0, vignetteMid = 40.0)),
            // 反转片：直接扫描底片，不经过相纸
            Style("slide_positive", "正片", "反转", "fujifilm_provia_100f", "kodak_supra_endura", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(temperature = 8.0, saturation = 10.0)),
            Style("slide_velvia", "浓郁风光", "反转", "fujifilm_velvia_100", "kodak_supra_endura", exposureEv = -0.3, grain = 0.5, halation = 0.5),
            Style("slide_documentary", "旧杂志", "反转", "kodak_ektachrome_100", "kodak_supra_endura", exposureEv = -0.3, grain = 0.7, halation = 0.5, look = Look(saturation = -25.0, contrast = 8.0, shadows = -10.0, hsl = Look.NEUTRAL.hsl.toMutableList().also { it[5] = Band(hue = -20.0, sat = -10.0) })),
            Style("slide_kodachrome", "浓色街头", "反转", "kodak_kodachrome_64", "kodak_supra_endura", exposureEv = -0.7, grain = 0.8, halation = 0.5),
            // 电影：电影负片印到拷贝片上
            Style("cine_soft", "影院柔调", "电影", "kodak_vision3_250d", "kodak_2393", exposureEv = 0.5, printExposure = 0.9, grain = 0.6, glare = 0.5, look = Look(saturation = -20.0, shadows = 15.0)),
            Style("cine_night", "霓虹夜行", "电影", "cinestill_800t", "kodak_2383", exposureEv = -0.5, mCc = 4.0, grain = 1.4, halation = 1.5, glare = 0.5, look = Look(contrast = 8.0)),
            Style("cine_bypass", "留银", "电影", "kodak_vision3_500t", "kodak_2383", grain = 1.2, halation = 1.2, glare = 0.5, look = Look(saturation = -45.0, contrast = 15.0)),
            Style("cine_day", "日光电影", "电影", "cinestill_50d", "kodak_2383", exposureEv = 0.3, grain = 0.5, glare = 0.5),
            // 大师：按作品集描述的色调
            Style("m_snow", "雪中街头", "大师", "kodak_kodachrome_64", "kodak_supra_endura", exposureEv = 0.3, grain = 1.2, halation = 0.5, look = Look(fade = 25.0, saturation = -10.0, tint = 6.0)),
            Style("m_dye", "南方染印", "大师", "kodak_gold_200", "kodak_ektacolor_edge", exposureEv = 0.3, printExposure = 1.1, yCc = -3.0, grain = 0.8, look = Look(saturation = 15.0, hsl = Look.NEUTRAL.hsl.toMutableList().also { it[0] = Band(sat = 15.0, lum = -10.0) })),
            Style("m_road", "公路大画幅", "大师", "kodak_portra_160", "kodak_portra_endura", exposureEv = 0.5, yCc = -1.0, formatMm = 250.0, grain = 0.3, halation = 0.3),
            Style("m_cape", "海角光", "大师", "kodak_portra_160", "kodak_supra_endura", exposureEv = 1.0, printExposure = 0.9, formatMm = 250.0, grain = 0.3, halation = 0.3, look = Look(contrast = -10.0)),
            Style("m_airy", "日光轻盈", "大师", "fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", exposureEv = 2.0, printExposure = 0.85, yCc = -1.0, formatMm = 56.0, grain = 0.5, halation = 0.8, look = NORITSU),
            Style("m_flash", "闪光日记", "大师", "kodak_ektachrome_100", "kodak_supra_endura", exposureEv = -0.3, halation = 0.8, look = Look(midZone = Zone(hue = 30.0, sat = 10.0))),
            // 黑白：黑白胶片印在 Multigrade 相纸上
            Style("bw_silver", "银盐黑白", "黑白", "fujifilm_neopan_acros_100_ii", "ilford_multigrade_iv_rc"),
            Style("bw_street", "街头黑白", "黑白", "kodak_tri_x_400", "ilford_multigrade_iv_rc", look = Look(contrast = 10.0)),
            Style("bw_harbour", "港街黑白", "黑白", "kodak_tri_x_400", "ilford_multigrade_iv_rc", formatMm = 56.0, grain = 0.9, look = Look(contrast = 15.0, vignette = -25.0)),
            Style("bw_soft", "柔和黑白", "黑白", "ilford_hp5_plus_400", "ilford_multigrade_iv_rc", look = Look(contrast = -8.0, fade = 15.0)),
        )

        val GROUPS = ALL.map { it.group }.distinct()
    }
}

/**
 * The 风格 row: a category chip row over cards, each showing the open photo
 * through the style's film (the film card already rendered) with its name and
 * stock as a subtitle. One tap applies the whole style.
 */
@Composable
internal fun StyleStrip(ui: UiState, enabled: Boolean, onPick: (Style) -> Unit) {
    var group by remember { mutableStateOf(Style.GROUPS.first()) }
    Text("一键风格", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in Style.GROUPS) FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
    }
    val styles = Style.ALL.filter { it.group == group && ui.catalog.film(it.film) != null }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(styles, key = { it.id }) { s ->
            val on = s.matches(ui.edit, ui.catalog)
            val ring by animateColorAsState(if (on) Palette.Amber else Palette.OutlineSoft, Motion.defaultEffects(), label = "ring")
            Column(
                Modifier.width(92.dp).clip(RoundedCornerShape(10.dp))
                    .border(if (on) 2.dp else 1.dp, ring, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onPick(s) }
                    .padding(bottom = 6.dp),
            ) {
                Box(Modifier.height(64.dp).width(92.dp).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
                    val card = ui.filmCards[s.film]
                    if (card != null) {
                        val bitmap = remember(card) { card.asImageBitmap() }
                        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(s.group, style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
                    }
                }
                Text(s.name, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp))
                Text(ui.catalog.film(s.film)?.label ?: s.film, style = MaterialTheme.typography.labelSmall,
                    color = Palette.OnSurfaceSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}
