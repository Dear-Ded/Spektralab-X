package com.dearded.spektralab

/*
 * The second line under a film card (ui2_derivatives.md, after
 * thetechgeekko/Spektrafilm-android): speed and colour balance, then one
 * plain phrase on what the stock looks like. Speed and balance come from the
 * name (the profiles carry no ISO); the phrase is ours, and a stock missing
 * from the table (an imported film) simply shows the spec alone.
 */
internal object FilmNotes {
    private val TRAITS = mapOf(
        "kodak_portra_160" to "细腻柔和，肤色干净",
        "kodak_portra_400" to "人像标杆，宽容度大",
        "kodak_portra_800" to "高感暖调，暗光人像",
        "kodak_portra_800_push1" to "推一档，反差更硬",
        "kodak_portra_800_push2" to "推两档，颗粒更粗",
        "kodak_ektar_100" to "高饱和，风光最细",
        "kodak_gold_200" to "暖黄日常，怀旧感",
        "kodak_ultramax_400" to "鲜亮通用，街拍顺手",
        "kodak_ektachrome_100" to "清透正片，偏冷",
        "kodak_kodachrome_64" to "浓郁红黄，老杂志味",
        "kodak_vision3_50d" to "电影日光，最细腻",
        "kodak_vision3_250d" to "电影日光，层次厚",
        "kodak_vision3_200t" to "电影灯光，柔和",
        "kodak_vision3_500t" to "电影夜景，暗部干净",
        "kodak_verita_200d" to "浓彩电影负片",
        "kodak_tri_x_400" to "经典街头黑白，颗粒感",
        "kodak_tmax_100" to "细颗粒黑白，锐利",
        "cinestill_50d" to "无防光晕层，日光",
        "cinestill_800t" to "夜景红晕，霓虹味",
        "fujifilm_c200" to "清淡偏绿，日系",
        "fujifilm_xtra_400" to "绿调通用，富士味",
        "fujifilm_pro_400h" to "淡雅粉绿，婚礼常用",
        "fujifilm_provia_100f" to "标准正片，色准",
        "fujifilm_velvia_100" to "极浓正片，风光",
        "fujifilm_neopan_acros_100_ii" to "细腻黑白，长曝稳",
        "ilford_hp5_plus_400" to "柔和黑白，好推冲",
    )

    /** "ISO 400 · 日光 · 人像标杆，宽容度大", or less when a part is unknown. */
    fun line(film: Stock): String {
        val iso = Regex("""(\d{2,4})(?:[TD])?\b""").findAll(film.label).lastOrNull()?.groupValues?.get(1)
        val balance = when {
            film.bw -> "黑白"
            Regex("""\d+T\b""").containsMatchIn(film.label) -> "灯光"
            else -> "日光"
        }
        return listOfNotNull(iso?.let { "ISO $it" }, balance, TRAITS[film.id]).joinToString(" · ")
    }
}
