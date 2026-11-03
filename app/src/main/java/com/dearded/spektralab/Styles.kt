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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    /** What the style imitates, for a "灵感来自 …" subtitle. Display only; never applied or saved. */
    val inspiredBy: String = "",
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

    /**
     * This style with its finish at [k] of full strength (0..1), linearly from
     * neutral. Film, paper, print and effects stay as they are; at 1 it is this style.
     */
    fun atStrength(k: Double): Style = if (k >= 1.0) this else copy(look = look.scaled(k.coerceIn(0.0, 1.0)))

    companion object {
        // Lab scanner signatures, as the labs describe them (Carmencita, theFINDlab,
        // Richard Photo Lab): Frontier punchy and golden with cyan shadows, Noritsu
        // flatter with lifted blacks, neutral shadows and more highlight room.
        private val FRONTIER = Look(contrast = 8.0, saturation = 10.0,
            shadowZone = Zone(hue = 190.0, sat = 10.0), highlightZone = Zone(hue = 45.0, sat = 8.0))
        private val NORITSU = Look(contrast = -6.0, saturation = -8.0, fade = 22.0, highlights = -15.0,
            midZone = Zone(hue = 15.0, sat = 6.0))

        /** The eight HSL bands with [bands] set: 0 红, 1 橙, 2 黄, 3 绿, 4 青, 5 蓝, 6 紫, 7 品红. */
        private fun hsl(vararg bands: Pair<Int, Band>) =
            Look.NEUTRAL.hsl.toMutableList().also { list -> for ((i, b) in bands) list[i] = b }

        // Every entry is mapped, with its source and limits, in docs/STYLES.md section 4.
        // Effects the tables leave out follow the existing styles: slides grain 0.6 and
        // halation 0.5, cine prints glare 0.5, phone looks grain 0.3-0.4 and halation 0.5
        // (a phone file has neither), everything else the engine default of 1.
        val ALL = listOf(
            // 实验室：常见冲扫店的出片风格（4.1）
            Style("lab_gold", "暖金冲扫", "实验室", "kodak_portra_400", "kodak_supra_endura", inspiredBy = "Frontier 扫描仪出片", exposureEv = 1.0, grain = 0.8, look = FRONTIER),
            Style("lab_airy", "粉调冲扫", "实验室", "kodak_portra_400", "kodak_portra_endura", inspiredBy = "Noritsu 扫描仪出片", exposureEv = 1.3, printExposure = 0.85, mCc = -3.0, grain = 0.8, look = NORITSU),
            Style("lab_wedding", "明亮婚礼", "实验室", "fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", inspiredBy = "婚礼冲扫店的过曝出片", exposureEv = 2.0, printExposure = 0.8, yCc = -2.0, mCc = -2.0, formatMm = 56.0, grain = 0.6, halation = 0.8, look = NORITSU.copy(fade = 18.0)),
            Style("lab_vivid", "风光冲扫", "实验室", "kodak_ektar_100", "kodak_ektacolor_edge", inspiredBy = "Frontier 扫描的风光片", exposureEv = 0.3, printExposure = 1.05, grain = 0.5, look = FRONTIER.copy(saturation = 6.0)),
            Style("lab_daily", "日常彩负", "实验室", "kodak_ultramax_400", "kodak_supra_endura", inspiredBy = "街头冲扫店的日常彩负", exposureEv = 0.7, grain = 1.1, look = FRONTIER.copy(contrast = 4.0)),
            // 相机：器材厂商的风格模式，按官方描述近似（4.2）
            Style("cam_chrome", "静默纪实", "相机", "fujifilm_provia_100f", "kodak_supra_endura", inspiredBy = "富士胶片模拟 Classic Chrome", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(saturation = -28.0, contrast = 6.0, shadows = -14.0, highlights = -8.0, hsl = hsl(0 to Band(sat = -12.0), 3 to Band(hue = -10.0, sat = -20.0), 5 to Band(hue = -12.0, sat = -15.0)))),
            Style("cam_classic_neg", "硬调彩负", "相机", "fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", inspiredBy = "富士胶片模拟 Classic Neg.", exposureEv = 0.3, printExposure = 1.1, look = Look(contrast = 12.0, saturation = -6.0, shadowZone = Zone(hue = 180.0, sat = 14.0), highlightZone = Zone(hue = 20.0, sat = 8.0), hsl = hsl(0 to Band(sat = 8.0), 3 to Band(hue = 15.0, sat = -10.0)))),
            Style("cam_nostalgic", "琥珀相册", "相机", "kodak_ultramax_400", "kodak_ultra_endura", inspiredBy = "富士胶片模拟 Nostalgic Neg.", exposureEv = 0.5, look = Look(contrast = 6.0, shadows = -10.0, blackPoint = 2.0, highlightZone = Zone(hue = 40.0, sat = 16.0), hsl = hsl(5 to Band(sat = -15.0)))),
            Style("cam_eterna", "柔光电影", "相机", "kodak_vision3_50d", "kodak_2383", inspiredBy = "富士胶片模拟 Eterna", exposureEv = 0.3, glare = 0.5, look = Look(saturation = -25.0, contrast = -6.0, highlights = -12.0, shadows = -6.0, midZone = Zone(hue = 170.0, sat = 5.0))),
            Style("cam_bleach", "低饱高反", "相机", "kodak_vision3_250d", "kodak_2383", inspiredBy = "富士胶片模拟 Eterna Bleach Bypass", glare = 0.5, look = Look(saturation = -50.0, contrast = 18.0, blackPoint = 4.0, highlights = -10.0)),
            Style("cam_astia", "柔和正片", "相机", "fujifilm_provia_100f", "kodak_supra_endura", inspiredBy = "富士胶片模拟 Astia", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(contrast = -10.0, saturation = -8.0, highlights = -10.0, hsl = hsl(0 to Band(sat = -6.0), 1 to Band(lum = 6.0)))),
            Style("cam_cine_warm", "暖调影院", "相机", "kodak_vision3_250d", "kodak_2383", inspiredBy = "徕卡 Looks · Classic", exposureEv = 0.3, grain = 0.8, glare = 0.5, look = Look(contrast = 14.0, saturation = -15.0, temperature = 8.0, fade = 12.0)),
            Style("cam_bright_shadow", "明亮当代", "相机", "kodak_portra_160", "kodak_portra_endura", inspiredBy = "徕卡 Looks · Contemporary", exposureEv = 0.5, look = Look(shadows = 18.0, contrast = -4.0, master = Zone(hue = 10.0, sat = 4.0))),
            Style("cam_teal", "青调夜色", "相机", "kodak_vision3_250d", "kodak_2383", inspiredBy = "徕卡 Looks · Teal", exposureEv = -0.3, glare = 0.5, look = Look(temperature = -6.0, contrast = 8.0, shadowZone = Zone(hue = 190.0, sat = 16.0), highlightZone = Zone(hue = 35.0, sat = 10.0))),
            Style("cam_posi", "街拍正片", "相机", "kodak_ektachrome_100", "kodak_supra_endura", inspiredBy = "理光 GR 图像控制 · 正片", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(contrast = 8.0, saturation = 12.0, temperature = 4.0, hsl = hsl(3 to Band(hue = -8.0), 5 to Band(hue = -15.0, sat = 10.0)))),
            Style("cam_nega", "街拍负片", "相机", "kodak_ultramax_400", "kodak_supra_endura", inspiredBy = "理光 GR 图像控制 · 负片", exposureEv = 0.3, grain = 1.2, look = Look(saturation = -18.0, contrast = 10.0, highlights = -6.0, shadowZone = Zone(hue = 185.0, sat = 12.0))),
            Style("cam_cinema_y", "黄调电影", "相机", "kodak_verita_200d", "kodak_2383", inspiredBy = "理光 GR IV 电影（黄）、适马 Cinema", grain = 0.7, glare = 0.5, look = Look(saturation = -18.0, shadows = -10.0, master = Zone(hue = 55.0, sat = 6.0), highlightZone = Zone(hue = 50.0, sat = 6.0))),
            Style("cam_fl", "冷峻胶片", "相机", "fujifilm_c200", "fujifilm_crystal_archive_typeii", inspiredBy = "索尼创意外观 FL", exposureEv = 0.3, look = Look(saturation = -15.0, contrast = 12.0, shadowZone = Zone(hue = 200.0, sat = 6.0), hsl = hsl(3 to Band(sat = 10.0), 4 to Band(sat = 10.0), 5 to Band(sat = 15.0, lum = -10.0)))),
            Style("cam_instant", "即显哑光", "相机", "kodak_gold_200", "kodak_portra_endura", inspiredBy = "索尼创意外观 IN", exposureEv = 0.5, look = Look(contrast = -18.0, saturation = -20.0, fade = 30.0, highlights = -10.0)),
            Style("cam_skin", "暖肤人像", "相机", "kodak_portra_400", "kodak_portra_endura", inspiredBy = "佳能照片风格 · 人像", exposureEv = 0.7, look = Look(temperature = 3.0, vibrance = 8.0, hsl = hsl(0 to Band(sat = 6.0, lum = 4.0), 1 to Band(sat = 6.0, lum = 6.0)))),
            Style("cam_vivid_print", "鲜艳冲印", "相机", "kodak_ektar_100", "kodak_ektacolor_edge", inspiredBy = "尼康优化校准 · 鲜艳 / 风景", exposureEv = 0.3, printExposure = 1.05, look = Look(contrast = 8.0, saturation = 15.0, hsl = hsl(3 to Band(sat = 12.0), 5 to Band(sat = 12.0)))),
            Style("cam_denim", "牛仔蓝调", "相机", "fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", inspiredBy = "尼康创意优化校准 · Denim", exposureEv = 0.3, look = Look(temperature = -12.0, fade = 18.0, saturation = -20.0, shadowZone = Zone(hue = 220.0, sat = 12.0))),
            Style("cam_classicneo", "清淡复古", "相机", "fujifilm_c200", "fujifilm_crystal_archive_typeii", inspiredBy = "松下照片风格 L.ClassicNeo", exposureEv = 0.5, look = Look(saturation = -22.0, fade = 15.0, contrast = -4.0, highlightZone = Zone(hue = 45.0, sat = 5.0), hsl = hsl(3 to Band(hue = -8.0, sat = -10.0), 5 to Band(sat = -20.0)))),
            Style("cam_pale", "淡彩轻柔", "相机", "fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", inspiredBy = "OM 艺术滤镜 · 淡雅色彩、宾得淡雅", exposureEv = 1.3, printExposure = 0.9, look = Look(saturation = -25.0, fade = 20.0, contrast = -8.0, highlightZone = Zone(hue = 200.0, sat = 5.0))),
            Style("cam_cross", "交叉冲洗", "相机", "kodak_ektachrome_100", "kodak_supra_endura", inspiredBy = "OM 艺术滤镜 / 宾得 · 交叉冲洗", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(contrast = 20.0, saturation = 15.0, tint = -8.0, shadowZone = Zone(hue = 220.0, sat = 14.0), highlightZone = Zone(hue = 70.0, sat = 16.0))),
            Style("cam_teal_orange", "青橙电影", "相机", "kodak_vision3_250d", "kodak_2383", inspiredBy = "适马色彩模式 Teal and Orange", exposureEv = 0.3, glare = 0.5, look = Look(contrast = 8.0, shadowZone = Zone(hue = 185.0, sat = 20.0), highlightZone = Zone(hue = 30.0, sat = 14.0), hsl = hsl(1 to Band(sat = 10.0), 3 to Band(hue = 30.0, sat = -20.0), 5 to Band(hue = -20.0)))),
            Style("cam_powder", "粉蓝清透", "相机", "fujifilm_provia_100f", "kodak_supra_endura", inspiredBy = "适马色彩模式 Powder Blue", grain = 0.6, halation = 0.5, look = Look(brightness = 10.0, saturation = -10.0, master = Zone(hue = 210.0, sat = 4.0), hsl = hsl(4 to Band(lum = 10.0), 5 to Band(sat = 10.0, lum = 10.0)))),
            Style("cam_forest", "森林绿意", "相机", "fujifilm_provia_100f", "kodak_supra_endura", inspiredBy = "适马色彩模式 Forest Green", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(contrast = 6.0, hsl = hsl(2 to Band(hue = 10.0, sat = 10.0), 3 to Band(sat = 25.0, lum = -5.0)))),
            Style("cam_satobi", "褪色昭和", "相机", "kodak_ektachrome_100", "kodak_supra_endura", inspiredBy = "宾得自定义图像 · 里美（SATOBI）", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(fade = 12.0, contrast = -4.0, hsl = hsl(0 to Band(sat = -20.0, lum = 5.0), 2 to Band(sat = -25.0), 5 to Band(hue = -20.0)))),
            Style("cam_gold_blue", "金辉蓝影", "相机", "kodak_portra_400", "kodak_supra_endura", inspiredBy = "宾得自定义图像 · 金色", exposureEv = 0.7, look = Look(highlightZone = Zone(hue = 50.0, sat = 18.0), shadowZone = Zone(hue = 225.0, sat = 12.0))),
            // 负片（4.3）
            Style("neg_amber", "琥珀记忆", "负片", "kodak_gold_200", "kodak_portra_endura", inspiredBy = "Gold 200 的暖调日常", exposureEv = 0.7, yCc = -4.0, grain = 0.9, look = Look(highlightZone = Zone(hue = 40.0, sat = 14.0), shadowZone = Zone(hue = 25.0, sat = 6.0))),
            Style("neg_hard", "硬负片", "负片", "fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", inspiredBy = "硬调彩负", exposureEv = 0.7, printExposure = 1.15, mCc = 2.0, look = Look(contrast = 10.0, hsl = hsl(0 to Band(hue = 8.0, sat = -12.0)))),
            Style("neg_cool", "冷负片", "负片", "fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", inspiredBy = "冷调彩负", exposureEv = 0.3, printExposure = 1.1, yCc = 4.0, mCc = 2.0, look = Look(contrast = 6.0, saturation = -15.0, shadowZone = Zone(hue = 190.0, sat = 12.0))),
            Style("neg_soft", "柔和写实", "负片", "fujifilm_c200", "fujifilm_crystal_archive_typeii", inspiredBy = "C200 的柔和写实", exposureEv = 0.7, grain = 0.8, look = Look(vibrance = -18.0, shadows = 10.0)),
            Style("neg_portrait", "柔肤人像", "负片", "fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", inspiredBy = "Pro 400H 人像、富士 Pro Neg. Std", exposureEv = 1.0, printExposure = 0.9, yCc = -1.0, mCc = -1.0, formatMm = 56.0, grain = 0.6, halation = 0.8, look = Look(contrast = -10.0)),
            Style("neg_natural", "自然", "负片", "kodak_portra_160", "kodak_endura_premier", inspiredBy = "Portra 160 中画幅", exposureEv = 0.5, formatMm = 70.0, grain = 0.4, halation = 0.5),
            Style("neg_classic", "深影经典", "负片", "kodak_portra_400", "kodak_ektacolor_edge", inspiredBy = "深影暗角的经典冲印", printExposure = 1.2, grain = 0.8, look = Look(vignette = -30.0, vignetteMid = 40.0)),
            Style("neg_push1", "增感一档", "负片", "kodak_portra_800_push1", "kodak_portra_endura", inspiredBy = "Portra 800 推一档", grain = 1.3),
            Style("neg_push2", "增感两档", "负片", "kodak_portra_800_push2", "kodak_portra_endura", inspiredBy = "Portra 800 推两档", grain = 1.6, look = Look(shadowZone = Zone(hue = 190.0, sat = 6.0), highlightZone = Zone(hue = 40.0, sat = 6.0))),
            Style("neg_night", "夜色人像", "负片", "kodak_portra_800", "kodak_portra_endura", inspiredBy = "Portra 800 按标称感光度", exposureEv = 0.7, grain = 1.1, halation = 1.2),
            Style("neg_pull", "减感柔调", "负片", "kodak_portra_400", "kodak_portra_endura", inspiredBy = "Portra 400 拉一档", exposureEv = 1.3, printExposure = 0.9, look = Look(contrast = -12.0, highlights = -8.0)),
            Style("neg_expired", "过期胶卷", "负片", "kodak_gold_200", "kodak_portra_endura", inspiredBy = "过期多年的彩色负片", exposureEv = -0.7, mCc = -5.0, grain = 1.6, look = Look(fade = 28.0, contrast = -14.0, saturation = -15.0, shadowZone = Zone(hue = 300.0, sat = 8.0))),
            // 反转片：直接扫描底片，不经过相纸（4.4）
            Style("slide_positive", "正片", "反转", "fujifilm_provia_100f", "kodak_supra_endura", inspiredBy = "Provia 正片", exposureEv = -0.3, grain = 0.6, halation = 0.5, look = Look(temperature = 8.0, saturation = 10.0)),
            Style("slide_velvia", "浓郁风光", "反转", "fujifilm_velvia_100", "kodak_supra_endura", inspiredBy = "Velvia 风光正片", exposureEv = -0.3, grain = 0.5, halation = 0.5),
            Style("slide_documentary", "旧杂志", "反转", "kodak_ektachrome_100", "kodak_supra_endura", inspiredBy = "旧杂志纪实正片", exposureEv = -0.3, grain = 0.7, halation = 0.5, look = Look(saturation = -25.0, contrast = 8.0, shadows = -10.0, hsl = hsl(5 to Band(hue = -20.0, sat = -10.0)))),
            Style("slide_kodachrome", "浓色街头", "反转", "kodak_kodachrome_64", "kodak_supra_endura", inspiredBy = "Kodachrome 街头", exposureEv = -0.7, grain = 0.8, halation = 0.5),
            Style("slide_dark", "欠曝浓彩", "反转", "fujifilm_velvia_100", "kodak_supra_endura", inspiredBy = "风光摄影常用的反转片欠曝", exposureEv = -1.0, grain = 0.6, halation = 0.5, look = Look(contrast = 6.0)),
            // 电影：电影负片印到拷贝片上（4.5）
            Style("cine_soft", "影院柔调", "电影", "kodak_vision3_250d", "kodak_2393", inspiredBy = "Vision3 250D 印 2393", exposureEv = 0.5, printExposure = 0.9, grain = 0.6, glare = 0.5, look = Look(saturation = -20.0, shadows = 15.0)),
            Style("cine_night", "霓虹夜行", "电影", "cinestill_800t", "kodak_2383", inspiredBy = "去掉防光晕层的电影卷夜景", exposureEv = -0.5, mCc = 4.0, grain = 1.4, halation = 1.5, glare = 0.5, look = Look(contrast = 8.0)),
            Style("cine_bypass", "留银", "电影", "kodak_vision3_500t", "kodak_2383", inspiredBy = "漂白旁路冲洗", grain = 1.2, halation = 1.2, glare = 0.5, look = Look(saturation = -45.0, contrast = 15.0)),
            Style("cine_day", "日光电影", "电影", "cinestill_50d", "kodak_2383", inspiredBy = "去掉防光晕层的日光电影卷", exposureEv = 0.3, grain = 0.5, glare = 0.5),
            Style("cine_verita", "浓彩电影", "电影", "kodak_verita_200d", "kodak_2383", inspiredBy = "Verita 200D 印 2383", exposureEv = 0.3, glare = 0.5),
            Style("cine_lamp", "灯光片柔调", "电影", "kodak_vision3_200t", "kodak_2383", inspiredBy = "Vision3 200T 印 2383", glare = 0.5, look = Look(saturation = -8.0, shadows = 6.0)),
            Style("cine_dark", "暗夜胶影", "电影", "kodak_vision3_500t", "kodak_2393", inspiredBy = "Vision3 500T 印 2393", exposureEv = -0.3, grain = 1.2, glare = 0.5, look = Look(contrast = 6.0, shadows = -8.0)),
            // 黑白：黑白胶片印在 Multigrade 相纸上；色轮只用来上色调（4.6）
            Style("bw_silver", "银盐黑白", "黑白", "fujifilm_neopan_acros_100_ii", "ilford_multigrade_iv_rc", inspiredBy = "Acros II 银盐"),
            Style("bw_street", "街头黑白", "黑白", "kodak_tri_x_400", "ilford_multigrade_iv_rc", inspiredBy = "Tri-X 街头", look = Look(contrast = 10.0)),
            Style("bw_harbour", "港街黑白", "黑白", "kodak_tri_x_400", "ilford_multigrade_iv_rc", inspiredBy = "Tri-X 中画幅街拍", formatMm = 56.0, grain = 0.9, look = Look(contrast = 15.0, vignette = -25.0)),
            Style("bw_soft", "柔和黑白", "黑白", "ilford_hp5_plus_400", "ilford_multigrade_iv_rc", inspiredBy = "HP5 柔和印放", look = Look(contrast = -8.0, fade = 15.0)),
            Style("bw_selenium", "硒调黑白", "黑白", "kodak_tmax_100", "ilford_multigrade_iv_rc", inspiredBy = "徕卡 Looks · Selenium", grain = 0.6, look = Look(contrast = 8.0, shadowZone = Zone(hue = 310.0, sat = 8.0))),
            Style("bw_sepia", "棕调黑白", "黑白", "ilford_hp5_plus_400", "ilford_multigrade_iv_rc", inspiredBy = "富士 Sepia、索尼 SE、OM 柔和棕褐", look = Look(contrast = -4.0, shadowZone = Zone(hue = 25.0, sat = 8.0), midZone = Zone(hue = 35.0, sat = 14.0), highlightZone = Zone(hue = 40.0, sat = 10.0))),
            Style("bw_hicon", "高反差黑白", "黑白", "kodak_tri_x_400", "ilford_multigrade_iv_rc", inspiredBy = "理光 GR 图像控制 · 高反差黑白", grain = 1.6, look = Look(contrast = 35.0, blackPoint = 6.0, vignette = -30.0)),
            Style("bw_deep", "深调黑白", "黑白", "fujifilm_neopan_acros_100_ii", "ilford_multigrade_iv_rc", inspiredBy = "尼康优化校准 · 深色调单色", look = Look(brightness = -10.0, contrast = 12.0, highlights = 12.0)),
            Style("bw_dynamic", "动感黑白", "黑白", "ilford_hp5_plus_400", "ilford_multigrade_iv_rc", inspiredBy = "松下照片风格 L.Monochrome D", grain = 1.1, look = Look(contrast = 22.0, highlights = 10.0, shadows = -8.0)),
            Style("bw_grainy", "粗粒黑白", "黑白", "kodak_tri_x_400", "ilford_multigrade_iv_rc", inspiredBy = "OM 艺术滤镜 · 粗颗粒胶片", formatMm = 24.0, grain = 2.0, look = Look(contrast = 25.0, vignette = -20.0)),
            Style("bw_push", "增感黑白", "黑白", "ilford_hp5_plus_400", "ilford_multigrade_iv_rc", inspiredBy = "HP5 推两档", exposureEv = -1.0, grain = 1.7, look = Look(contrast = 18.0, shadows = -10.0)),
            // 手机：手机厂商的色彩风格，沿用 ROADMAP M2 的调研（4.7）
            Style("ph_authentic", "原生光影", "手机", "kodak_portra_160", "kodak_supra_endura", inspiredBy = "小米 · 徕卡原生", exposureEv = 0.3, grain = 0.3, halation = 0.5, look = Look(contrast = 12.0, shadows = -12.0, vignette = -30.0)),
            Style("ph_vibrant", "鲜艳明快", "手机", "kodak_ektar_100", "kodak_endura_premier", inspiredBy = "小米 · 徕卡鲜艳", exposureEv = 0.3, grain = 0.3, halation = 0.5, look = Look(vibrance = 15.0, contrast = 4.0, shadows = 6.0)),
            Style("ph_ccd", "早期 CCD", "手机", "fujifilm_provia_100f", "kodak_supra_endura", inspiredBy = "徕卡 M9 CCD（小米徕卡一瞬）", exposureEv = -0.3, grain = 0.3, halation = 0.5, look = Look(contrast = 10.0, temperature = 3.0, hsl = hsl(0 to Band(sat = 12.0), 5 to Band(hue = -10.0)))),
            Style("ph_mono50", "低感黑白", "手机", "kodak_tmax_100", "ilford_multigrade_iv_rc", inspiredBy = "徕卡 M3 + 50 度黑白卷（小米徕卡一瞬）", formatMm = 56.0, grain = 0.3, halation = 0.5, look = Look(contrast = 6.0)),
            Style("ph_hncs", "自然色彩", "手机", "kodak_portra_160", "kodak_endura_premier", inspiredBy = "哈苏自然色彩方案（OPPO / 一加）", formatMm = 56.0, grain = 0.3, halation = 0.5, look = Look(vibrance = -5.0, highlights = -10.0)),
            Style("ph_zeiss_natural", "自然写实", "手机", "kodak_ektar_100", "kodak_endura_premier", inspiredBy = "vivo · 蔡司自然色彩", exposureEv = 0.3, grain = 0.3, halation = 0.5, look = Look(saturation = -12.0, temperature = -3.0, contrast = 4.0)),
            Style("ph_zeiss_vivid", "鲜明锐利", "手机", "kodak_ektar_100", "kodak_ektacolor_edge", inspiredBy = "vivo · 蔡司鲜明色彩", exposureEv = 0.3, grain = 0.3, halation = 0.5, look = Look(contrast = 10.0, saturation = 10.0, vibrance = 8.0, temperature = -2.0)),
            Style("ph_xmage", "原色真实", "手机", "kodak_portra_160", "kodak_ultra_endura", inspiredBy = "华为 XMAGE 原色", exposureEv = 0.3, grain = 0.3, halation = 0.5, look = Look(contrast = 8.0, saturation = -4.0)),
            Style("ph_xmage_bright", "明亮暖光", "手机", "kodak_portra_400", "kodak_ultra_endura", inspiredBy = "华为 XMAGE 明亮", exposureEv = 1.0, grain = 0.4, halation = 0.5, look = Look(shadows = 15.0, highlights = -10.0, contrast = -4.0, temperature = 3.0)),
            Style("ph_bright", "明亮通透", "手机", "kodak_portra_400", "kodak_endura_premier", inspiredBy = "三星相机的明亮直出", exposureEv = 0.7, grain = 0.4, halation = 0.5, look = Look(shadows = 20.0, vibrance = 15.0, contrast = -2.0)),
            Style("ph_cool", "冷峻高反", "手机", "kodak_ektar_100", "kodak_supra_endura", inspiredBy = "Pixel 相机的冷调高反差", grain = 0.3, halation = 0.5, look = Look(temperature = -8.0, contrast = 12.0, shadows = -10.0, saturation = -4.0)),
            Style("ph_studio_bw", "影棚黑白", "手机", "fujifilm_neopan_acros_100_ii", "ilford_multigrade_iv_rc", inspiredBy = "手机影棚人像黑白", formatMm = 56.0, grain = 0.3, halation = 0.5, look = Look(contrast = 14.0, highlights = 8.0, vignette = -40.0)),
            Style("ph_amber", "琥珀暖调", "手机", "kodak_portra_400", "kodak_portra_endura", inspiredBy = "苹果摄影风格 · 琥珀", exposureEv = 0.5, grain = 0.4, halation = 0.5, look = Look(temperature = 10.0, hsl = hsl(1 to Band(sat = 8.0)))),
            Style("ph_rose", "玫瑰金调", "手机", "kodak_portra_400", "kodak_portra_endura", inspiredBy = "苹果摄影风格 · 玫瑰金", exposureEv = 0.5, grain = 0.4, halation = 0.5, look = Look(tint = 8.0, temperature = 4.0, highlightZone = Zone(hue = 10.0, sat = 8.0))),
            Style("ph_quiet", "安静淡调", "手机", "kodak_portra_160", "kodak_portra_endura", inspiredBy = "苹果摄影风格 · 安静", exposureEv = 0.5, grain = 0.3, halation = 0.5, look = Look(fade = 20.0, saturation = -20.0, contrast = -8.0, temperature = 3.0)),
            Style("ph_dramatic", "戏剧光影", "手机", "kodak_portra_160", "kodak_supra_endura", inspiredBy = "苹果摄影风格 · 戏剧", grain = 0.3, halation = 0.5, look = Look(contrast = 18.0, shadows = -15.0, saturation = -6.0)),
            // 摄影师：按作品集描述的色调（4.8）
            Style("m_snow", "雪中街头", "摄影师", "kodak_kodachrome_64", "kodak_supra_endura", inspiredBy = "Saul Leiter 的雪中街头", exposureEv = 0.3, grain = 1.2, halation = 0.5, look = Look(fade = 25.0, saturation = -10.0, tint = 6.0)),
            Style("m_dye", "南方染印", "摄影师", "kodak_gold_200", "kodak_ektacolor_edge", inspiredBy = "William Eggleston 的染印色彩", exposureEv = 0.3, printExposure = 1.1, yCc = -3.0, grain = 0.8, look = Look(saturation = 15.0, hsl = hsl(0 to Band(sat = 15.0, lum = -10.0)))),
            // 4x5: the engine takes film_format_mm in 4..200 and rejects the whole delta outside it.
            Style("m_road", "公路大画幅", "摄影师", "kodak_portra_160", "kodak_portra_endura", inspiredBy = "Stephen Shore 的公路大画幅", exposureEv = 0.5, yCc = -1.0, formatMm = 127.0, grain = 0.3, halation = 0.3),
            Style("m_cape", "海角光", "摄影师", "kodak_portra_160", "kodak_supra_endura", inspiredBy = "Joel Meyerowitz 的海角光线", exposureEv = 1.0, printExposure = 0.9, formatMm = 127.0, grain = 0.3, halation = 0.3, look = Look(contrast = -10.0)),
            Style("m_airy", "日光轻盈", "摄影师", "fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", inspiredBy = "精致婚礼摄影的明亮胶片", exposureEv = 2.0, printExposure = 0.85, yCc = -1.0, formatMm = 56.0, grain = 0.5, halation = 0.8, look = NORITSU),
            Style("m_flash", "闪光日记", "摄影师", "kodak_ektachrome_100", "kodak_supra_endura", inspiredBy = "直闪日记式彩色摄影", exposureEv = -0.3, halation = 0.8, look = Look(midZone = Zone(hue = 30.0, sat = 10.0))),
        )

        val GROUPS = ALL.map { it.group }.distinct()
    }
}

/** Every field of the finish times [k], except vignetteMid (a shape) and the wheels' hues (a direction). */
private fun Look.scaled(k: Double): Look {
    fun zone(z: Zone) = z.copy(sat = z.sat * k, lum = z.lum * k)
    return copy(
        temperature = temperature * k, tint = tint * k, exposure = exposure * k, contrast = contrast * k,
        brightness = brightness * k, saturation = saturation * k, vibrance = vibrance * k,
        highlights = highlights * k, shadows = shadows * k, blackPoint = blackPoint * k, whitePoint = whitePoint * k,
        fade = fade * k, vignette = vignette * k,
        master = zone(master), shadowZone = zone(shadowZone), midZone = zone(midZone), highlightZone = zone(highlightZone),
        hsl = hsl.map { Band(it.hue * k, it.sat * k, it.lum * k) },
    )
}

/**
 * The 风格 row: the eight group chips over cards, each showing the open photo
 * through the style's film (the film card already rendered) with its name and
 * what it is inspired by. One tap applies the style at the strength last used
 * for it; tapping the applied style again opens its strength slider, which
 * scales only the finish. Strengths are kept per style across photos and launches.
 */
@Composable
internal fun StyleStrip(ui: UiState, enabled: Boolean, onPick: (Style) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("style_strength", 0) }
    val strengths = remember { mutableStateMapOf<String, Double>() }
    fun strengthOf(id: String) = strengths[id] ?: prefs.getFloat(id, 1f).toDouble().coerceIn(0.0, 1.0)
    fun applied(s: Style) = s.atStrength(strengthOf(s.id)).matches(ui.edit, ui.catalog)

    var group by remember { mutableStateOf(Style.ALL.firstOrNull { applied(it) }?.group ?: Style.GROUPS.first()) }
    var open by remember { mutableStateOf<String?>(null) }
    Text("一键风格", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in Style.GROUPS) FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
    }
    val styles = Style.ALL.filter { it.group == group && ui.catalog.film(it.film) != null }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(styles, key = { it.id }) { s ->
            val on = applied(s)
            val ring by animateColorAsState(if (on) Palette.Amber else Palette.OutlineSoft, Motion.defaultEffects(), label = "ring")
            Column(
                Modifier.width(92.dp).clip(RoundedCornerShape(10.dp))
                    .border(if (on) 2.dp else 1.dp, ring, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) {
                        if (on) open = if (open == s.id) null else s.id
                        else { open = null; onPick(s.atStrength(strengthOf(s.id))) }
                    }
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
                Text(s.inspiredBy.ifEmpty { ui.catalog.film(s.film)?.label ?: s.film }, style = MaterialTheme.typography.labelSmall,
                    color = Palette.OnSurfaceSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
    // The slider belongs to the applied style only; changing photo, style or finish elsewhere hides it.
    val current = Style.ALL.firstOrNull { it.id == open }?.takeIf { applied(it) }
    if (current != null) {
        LabeledSlider("强度 · ${current.name}", strengthOf(current.id) * 100, 0.0, 100.0, 100.0, enabled, "%.0f%%") { v ->
            val k = (v / 100).coerceIn(0.0, 1.0)
            strengths[current.id] = k
            prefs.edit().putFloat(current.id, k.toFloat()).apply()
            onPick(current.atStrength(k))
        }
    }
}
