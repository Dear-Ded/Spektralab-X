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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch

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
    /**
     * The file named no film (a Layer 2-only XMP or .cube, or a style saved from a 无
     * photo): [film] is only the photo's leftover stock, so the photo's own 无 and
     * 相纸「无」 stay as they are (bughunt_main 2).
     */
    val keepFilmState: Boolean = false,
) {
    /** [edit] in this style; per-photo geometry, white balance and borders stay. */
    fun applyTo(edit: Edit, catalog: Catalog): Edit? {
        val stock = catalog.film(film) ?: return null
        // A style is a film on a paper: 无 off, the paper chosen. Coming from 效果「无」
        // it develops with the scanner's own sharpening, as every style was tuned with.
        val base = if (keepFilmState) edit.copy(film = film,
                paper = if (catalog.paper(paper) != null) paper else edit.paper)
            else edit.copy(film = film, scanFilm = stock.positive, noFilm = false, digitalIntermediate = false,
                unsharpAmount = if (edit.effectsNone) Edit.SCAN_UNSHARP else edit.unsharpAmount,
                paper = if (catalog.paper(paper) != null) paper else stock.targetPrint ?: edit.paper)
        return catalog.normalize(base.copy(
            exposureEv = exposureEv, printExposure = printExposure,
            yShift = (yCc / Edit.FILTER_SHIFT_CC).coerceIn(-1.0, 1.0),
            mShift = (mCc / Edit.FILTER_SHIFT_CC).coerceIn(-1.0, 1.0),
            filmFormatMm = formatMm,
            grainActive = grain > 0, grainAmount = grain.coerceAtLeast(0.0),
            halationActive = halation > 0, halationAmount = halation.coerceAtLeast(0.0),
            glareActive = glare > 0, glareAmount = glare.coerceAtLeast(0.0),
            look = (PAD_HOMES[id] ?: (0.0 to 0.0)).let { (tH, cH) ->
                look.copy(tone = tH, colour = cH, toneHome = tH, colourHome = cH)
            },
        ))
    }

    fun matches(edit: Edit, catalog: Catalog) = applyTo(edit, catalog) == edit

    /**
     * This style with its finish at [k] of full strength (0..1), linearly from
     * neutral. Film, paper, print and effects stay as they are; at 1 it is this style.
     */
    fun atStrength(k: Double): Style = if (k >= 1.0) this else copy(look = look.finishAt(k.coerceIn(0.0, 1.0)))

    /** This style as a .spklook (what a Fuji recipe builds on, and what export writes). */
    fun toSpkLook() = SpkLook(
        name = name, category = group,
        engine = SpkEngine(film = film, paper = paper, exposureEv = exposureEv, printExposure = printExposure,
            yCc = yCc, mCc = mCc, formatMm = formatMm, grain = grain, halation = halation, glare = glare),
        layer2 = look.copy(lut = "", lutStrength = 1.0),
        source = SpkSource("style", id, inspiredBy),
    )

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

        /**
         * Each style's place on the palette pad (docs/UI.md 2.8): where its finish
         * already sits on the pad's own axes, read back through Look's PAD_* reach.
         * The dot starts there and a double tap returns there; the pad moves only
         * the offset from it, so a style at home renders exactly as before.
         */
        val PAD_HOMES: Map<String, Pair<Double, Double>> = ALL.associate { it.id to padHome(it.look) }

        private fun padHome(l: Look): Pair<Double, Double> {
            val up = maxOf(0.0, l.shadows) / Look.PAD_SHADOWS + maxOf(0.0, -l.highlights) / Look.PAD_HIGHLIGHTS +
                maxOf(0.0, -l.contrast) / Look.PAD_FLATTEN
            val down = maxOf(0.0, -l.shadows) / Look.PAD_SHADOWS + maxOf(0.0, l.contrast) / Look.PAD_DEEPEN +
                l.blackPoint / Look.PAD_BLACK
            val tone = ((up - down) / 3).coerceIn(-1.0, 1.0)
            val colour = ((l.saturation / Look.PAD_SATURATION + l.vibrance / Look.PAD_VIBRANCE) / 2).coerceIn(-1.0, 1.0)
            return Math.round(tone * 100).toDouble() to Math.round(colour * 100).toDouble()
        }
    }
}

/** SharedPreferences file of each style's strength (0..1), built-in and imported alike. */
internal const val STYLE_STRENGTH_PREFS = "style_strength"
// In the same file; style ids never start with "_", so these keys cannot be a strength.
private const val HOT_GROUP = "热门"
private const val FAV_GROUP = "收藏"
private const val RECENT_GROUP = "最近"
/** The photos edited last on the light table (ui2_snapseed.md 6): one tap pastes their settings here. */
private const val EDITS_GROUP = "最近编辑"
/** Styles saved from this editor; its first card is + 存为风格. */
internal const val MINE_GROUP = "我的"
/** 热门: one each of the looks people ask for by name (富士味, 日系, CCD, 徕卡味, 电影感, 黑白). */
private val HOT = listOf(
    "lab_daily", "cam_classic_neg", "cam_chrome", "lab_airy", "ph_ccd", "cine_night",
    "cam_teal_orange", "slide_velvia", "neg_expired", "ph_authentic", "bw_silver", "cam_instant",
)
private const val LAST_STYLE = "_last_style"
private const val LAST_GROUP = "_last_group"

/**
 * The 风格 row: the eight group chips over cards, each showing the open photo
 * through the style's film (the film card already rendered) with its name and
 * what it is inspired by. One tap applies the style at the strength last used
 * for it; tapping the applied style again opens its strength slider, which
 * scales only the finish. Strengths are kept per style across photos and launches.
 */
@Composable
internal fun StyleStrip(
    ui: UiState, enabled: Boolean,
    /** A whole edit to apply (最近编辑), as one undo step. */
    onEdit: (Edit) -> Unit = {},
    /** 无: no film, no film effects, no adjustments. */
    onNone: () -> Unit = {},
    onPick: (Style) -> Unit,
) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val prefs = remember(context) { context.getSharedPreferences(STYLE_STRENGTH_PREFS, 0) }
    val strengths = remember { mutableStateMapOf<String, Double>() }
    fun strengthOf(id: String) = strengths[id] ?: prefs.getFloat(id, 1f).toDouble().coerceIn(0.0, 1.0)
    fun applied(s: Style) = s.atStrength(strengthOf(s.id)).matches(ui.edit, ui.catalog)

    // Imported styles (StyleImport.kt) after the built-in ones, under their own categories.
    val library = remember(context) { UserLibrary.get(context) }
    val imported by library.styles.all.collectAsState()
    val all = Style.ALL + imported.map { it.look.toStyle(it.id, ui.edit, it.lutId) }
    // 热门 first (UI2.md 2.2): the picks a newcomer is most likely after, by folk name.
    // 收藏 and 最近 in front, each only once it has something (ui2_derivatives.md 9).
    val picks = rememberPicks("style")
    val favIds = picks.favourites.filter { id -> all.any { it.id == id } }
    val recentIds = picks.recent.filter { id -> all.any { it.id == id } }
    // 最近编辑: the light table's last edited photos, this one left out, read off the main thread.
    val store = remember(context) { FrameStore.of(context) }
    val frames by store.frames.collectAsState()
    val recentFrames = frames.filter { it.edited && it.name != ui.source?.name }.sortedByDescending { it.editedAt }.take(8)
    val recentEdits by androidx.compose.runtime.produceState(emptyList<Pair<Frame, Sidecar>>(), recentFrames.map { it.id to it.editedAt }) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            recentFrames.mapNotNull { f -> store.sidecar(f.id)?.let { f to it } }
        }
    }
    val groups = (listOfNotNull(FAV_GROUP.takeIf { favIds.isNotEmpty() }, RECENT_GROUP.takeIf { recentIds.isNotEmpty() },
        EDITS_GROUP.takeIf { recentEdits.isNotEmpty() }) +
        listOf(HOT_GROUP, MINE_GROUP) + Style.GROUPS + imported.map { it.category }).distinct()

    // The group opens on the applied style, else on the last style picked, else the last
    // group browsed: an edit tweaked after a style no longer matches it, but the row stays put.
    var group by remember {
        val last = prefs.getString(LAST_STYLE, null)?.let { id -> all.firstOrNull { it.id == id }?.group }
        val browsed = prefs.getString(LAST_GROUP, null)?.takeIf { it in groups }
        mutableStateOf(all.firstOrNull { applied(it) }?.group ?: last ?: browsed ?: groups.first())
    }
    // Un-starring the last favourite empties 收藏: fall back to 热门 rather than a blank row.
    if ((group == FAV_GROUP && favIds.isEmpty()) || (group == RECENT_GROUP && recentIds.isEmpty()) ||
        (group == EDITS_GROUP && recentEdits.isEmpty())) group = HOT_GROUP
    var naming by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun browse(g: String) { group = g; prefs.edit().putString(LAST_GROUP, g).apply() }
    fun pick(s: Style, k: Double) {
        prefs.edit().putString(LAST_STYLE, s.id).putString(LAST_GROUP, s.group).putFloat(s.id, k.toFloat()).apply()
        picks.used(s.id)
        onPick(s.atStrength(k))
    }
    var open by remember { mutableStateOf<String?>(null) }
    // Search across every group by name, inspiration, film and folk name (StyleTags.kt).
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // The panel's title row and the group pills (UI2.md 7.2 point 2); search sits first, as a pill.
    PillRow {
        SelectPill(selected = searching, onClick = { searching = !searching; if (!searching) query = "" },
            label = { Text("搜索") })
        for (g in groups) SelectPill(selected = !searching && group == g,
            onClick = { searching = false; query = ""; browse(g) }, label = { Text(g) })
    }
    if (searching) {
        OutlinedTextField(value = query, onValueChange = { query = it.take(30) }, singleLine = true,
            placeholder = { Text("富士味、CCD、徕卡味、电影感…") }, modifier = Modifier.fillMaxWidth())
    }
    val styles = all.filter { s ->
        ui.catalog.film(s.film) != null &&
            (if (searching) s.matchesQuery(query, ui.catalog.film(s.film)?.label.orEmpty())
             else when (group) {
                 HOT_GROUP -> s.id in HOT
                 FAV_GROUP -> s.id in favIds
                 RECENT_GROUP -> s.id in recentIds
                 else -> s.group == group
             })
    }.let { list ->
        when {
            searching -> list
            group == HOT_GROUP -> list.sortedBy { HOT.indexOf(it.id) }
            group == FAV_GROUP -> list.sortedBy { favIds.indexOf(it.id) }
            group == RECENT_GROUP -> list.sortedBy { recentIds.indexOf(it.id) }
            else -> list
        }
    }
    if (searching && styles.isEmpty()) {
        Text("没有找到“${query.trim()}”", style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp).coachAnchor(CoachStep.STYLE)) {
        // 无 first (tools/work/no_film_research.md): no film, no film effects, no adjustments.
        if (!searching) item(key = "_none") {
            val original = remember(ui.before) { ui.before?.asImageBitmap() }
            NoneCard(selected = ui.edit.styleNone, original = original, enabled = enabled, subtitle = "不加胶片和调整") {
                if (!ui.edit.styleNone) { view.tick(); onNone() }
            }
        }
        // + 存为风格 leads 我的 (Snapseed's + SAVE AS LOOK): the open edit, kept as a one-tap style.
        if (!searching && group == MINE_GROUP) item(key = "_save") {
            TextCard(selected = false, title = "+ 存为风格", subtitle = "把现在的胶卷、相纸、效果和调整存成一张卡",
                enabled = enabled) { naming = true }
        }
        // 最近编辑: another photo's settings onto this one; border and RAW white balance stay this photo's.
        if (!searching && group == EDITS_GROUP) items(recentEdits, key = { "_edit_" + it.first.id }) { (f, car) ->
            // A 无 edit is shown as what it is, the photo itself (bughunt_main 7).
            val card = if (car.edit.noFilm) ui.before else ui.filmCards[car.edit.film]
            val bitmap = remember(card) { card?.asImageBitmap() }
            ThumbCard(
                selected = false, image = bitmap,
                title = f.name.substringBeforeLast("."),
                subtitle = if (car.edit.noFilm) "原图" else ui.catalog.film(car.edit.film)?.label ?: "原图",
                enabled = enabled && ui.source != null, placeholder = f.name,
            ) {
                val src = ui.source ?: return@ThumbCard
                val here = Frame(id = "", uri = "", name = src.name, size = 0, date = 0, mime = "",
                    raw = src.kind == "raw", width = src.width, height = src.height,
                    exifDate = ui.exif.dateTimeOriginal.orEmpty())
                val p = pasteLook(Sidecar(ui.edit, src.wbK, src.wbTint), here,
                    CopiedLook(car.edit, car.wbK, car.wbTint, f.name),
                    LookGroup.entries.toSet() - LookGroup.BORDER - LookGroup.WHITE_BALANCE, ui.catalog)
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                onEdit(p.edit)
            }
        }
        if (searching || group != EDITS_GROUP) items(styles, key = { it.id }) { s ->
            val on = applied(s)
            val card = ui.filmCards[s.film]
            val bitmap = remember(card) { card?.asImageBitmap() }
            // UI2.md 7.2 points 3-4: the shared picture card; a tap on the chosen one opens its strength.
            ThumbCard(
                selected = on,
                image = bitmap,
                title = s.name,
                subtitle = s.inspiredBy.ifEmpty { ui.catalog.film(s.film)?.label ?: s.film },
                enabled = enabled,
                placeholder = s.name,
                starred = picks.isFavourite(s.id),
                onLongClick = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); picks.toggleFavourite(s.id) },
            ) {
                if (on) open = if (open == s.id) null else s.id
                else {
                    open = null
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    pick(s, strengthOf(s.id))
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("存为风格") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                        placeholder = { Text("例如：傍晚暖调") }, modifier = Modifier.fillMaxWidth())
                    Hint("存胶卷、相纸、曝光、效果和调整层；边框、裁剪和白平衡属于这张照片，不存。存好后在「我的」里。",
                        Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    val edit = ui.edit
                    val photo = ui.image
                    val title = name.trim()
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            saveAsStyle(library, edit, title, StyleImports.thumbnail(photo))
                        }
                        browse(MINE_GROUP)
                    }
                }) { Text("保存") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { naming = false }) { Text("取消") } },
        )
    }
    // The slider belongs to the applied style only; changing photo, style or finish elsewhere hides it.
    val current = all.firstOrNull { it.id == open }?.takeIf { applied(it) }
    // UI2.md 3.7: the slider opens in place under the cards (height on defaultSpatial, faded in)
    // and, closing, keeps showing the style it belonged to until it has folded away. A plain
    // holder, not state: remembering the last style must not recompose anything.
    val last = remember { arrayOfNulls<Style>(1) }
    if (current != null) last[0] = current
    val shown = current ?: last[0]
    Expandable(open = current != null, verticalArrangement = Arrangement.spacedBy(RowGap)) {
        val s = shown ?: return@Expandable
        // ui2_icecream.md 6.3: three steps to tap, the slider under them stays continuous.
        // A step is chosen only while the strength sits exactly on it.
        val k = strengthOf(s.id)
        val steps = listOf("轻" to 0.4, "中" to 0.7, "重" to 1.0)
        Segmented(steps.map { it.first }, steps.indexOfFirst { kotlin.math.abs(k - it.second) < 0.005 },
            enabled && current != null) { i ->
            view.tick()
            strengths[s.id] = steps[i].second
            pick(s, steps[i].second)
        }
        LabeledSlider("强度 · ${s.name}", k * 100, 0.0, 100.0, 100.0, enabled && current != null, "%.0f%%") { v ->
            val k = (v / 100).coerceIn(0.0, 1.0)
            strengths[s.id] = k
            pick(s, k)
        }
    }
}

/**
 * [edit] saved into the user library as a style in 我的 (ui2_snapseed.md 6): the
 * same fields an exported .spklook carries; a colour table already in the
 * library is referenced by its id, not copied again. Runs on an IO thread.
 */
internal fun saveAsStyle(library: StyleImports, edit: Edit, name: String, thumbJpeg: ByteArray?) {
    val r = edit.look.resolved()
    val table = r.lut.takeIf { it.isNotEmpty() }
    val look = SpkLook(
        name = name.take(60), category = MINE_GROUP,
        engine = SpkEngine(
            film = edit.film.takeUnless { edit.noFilm }, paper = edit.paper.takeUnless { edit.noFilm },
            exposureEv = edit.exposureEv, printExposure = edit.printExposure,
            yCc = edit.yShift * Edit.FILTER_SHIFT_CC, mCc = edit.mShift * Edit.FILTER_SHIFT_CC,
            formatMm = edit.filmFormatMm,
            grain = if (edit.grainActive) edit.grainAmount else 0.0,
            halation = if (edit.halationActive) edit.halationAmount else 0.0,
            glare = if (edit.glareActive) edit.glareAmount else 0.0,
        ),
        layer2 = r.copy(lut = "", lutStrength = 1.0),
        lut = table?.let { SpkLut(strength = r.lutStrength, space = library.luts.original(it)?.second ?: SpkLut.SPACE_SRGB) },
        source = SpkSource("spektralab-x", "", "存自这台手机"),
    )
    library.styles.save(UserStyle(library.styles.newId(), look, table.orEmpty(), System.currentTimeMillis()), thumbJpeg)
}
