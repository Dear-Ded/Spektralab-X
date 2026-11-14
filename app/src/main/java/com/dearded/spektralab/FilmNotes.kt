package com.dearded.spektralab

/*
 * The second line under a film card (ui2_derivatives.md, after
 * thetechgeekko/Spektrafilm-android): speed and colour balance, then one
 * plain phrase on what the stock looks like. Speed and balance come from the
 * name (the profiles carry no ISO); the phrase is ours, and a stock missing
 * from the table (an imported film) simply shows the spec alone.
 */
internal object FilmNotes {
    private val TRAITS get() = mapOf(
        "kodak_portra_160" to tr("细腻柔和，肤色干净"),
        "kodak_portra_400" to tr("低反差，肤色准，宽容度大"),
        "kodak_portra_800" to tr("高感暖调，暗光人像"),
        "kodak_portra_800_push1" to tr("推一档，反差更硬"),
        "kodak_portra_800_push2" to tr("推两档，颗粒更粗"),
        "kodak_ektar_100" to tr("高饱和，风光最细"),
        "kodak_gold_200" to tr("偏暖黄，饱和中等"),
        "kodak_ultramax_400" to tr("饱和偏高，通用"),
        "kodak_ektachrome_100" to tr("清透正片，偏冷"),
        "kodak_kodachrome_64" to tr("高饱和红黄，反差高"),
        "kodak_vision3_50d" to tr("电影日光，最细腻"),
        "kodak_vision3_250d" to tr("电影日光，层次厚"),
        "kodak_vision3_200t" to tr("电影灯光，柔和"),
        "kodak_vision3_500t" to tr("电影夜景，暗部干净"),
        "kodak_verita_200d" to tr("浓彩电影负片"),
        "kodak_tri_x_400" to tr("颗粒明显，反差中等"),
        "kodak_tmax_100" to tr("细颗粒黑白，锐利"),
        "cinestill_50d" to tr("无防光晕层，日光"),
        "cinestill_800t" to tr("灯光型，高光有红晕"),
        "fujifilm_c200" to tr("低饱和，偏绿"),
        "fujifilm_xtra_400" to tr("偏绿，通用"),
        "fujifilm_pro_400h" to tr("低反差，偏粉绿"),
        "fujifilm_provia_100f" to tr("标准正片，色准"),
        "fujifilm_velvia_100" to tr("极浓正片，风光"),
        "fujifilm_neopan_acros_100_ii" to tr("细腻黑白，长曝稳"),
        "ilford_hp5_plus_400" to tr("反差柔，宽容度大"),
    )

    /** "ISO 400 · 日光 · 人像标杆，宽容度大", or less when a part is unknown. */
    fun line(film: Stock): String {
        // A speed may carry one letter: 500T, 50D, Provia 100F, Pro 400H (bughunt_main 8).
        val iso = Regex("""(?<!\d)(\d{2,4})[A-Z]?\b""").findAll(film.label).lastOrNull()?.groupValues?.get(1)
        val balance = when {
            film.bw -> tr("黑白")
            Regex("""\d+T\b""").containsMatchIn(film.label) -> tr("灯光")
            else -> tr("日光")
        }
        return listOfNotNull(iso?.let { "ISO $it" }, balance, TRAITS[film.id]).joinToString(" · ")
    }
}

/**
 * 收藏 and 最近 for films and styles (ui2_derivatives.md 9, after Silverphase's
 * MaterialChooser): a long press on a card stars it, and every pick moves to
 * the front of 最近 (twelve kept). Kept per [kind] ("film" / "style") in the
 * settings file, so they survive photos and launches. Reads are snapshot
 * state, so a strip redraws as soon as a star changes.
 */
internal class Picks private constructor(context: android.content.Context, private val kind: String) {
    private val prefs = context.getSharedPreferences("settings", 0)
    private val favKey = "picks_${kind}_fav"
    private val recentKey = "picks_${kind}_recent"

    val favourites = androidx.compose.runtime.mutableStateListOf<String>().apply {
        addAll(prefs.getString(favKey, "").orEmpty().split(',').filter { it.isNotBlank() })
    }
    val recent = androidx.compose.runtime.mutableStateListOf<String>().apply {
        addAll(prefs.getString(recentKey, "").orEmpty().split(',').filter { it.isNotBlank() })
    }

    fun isFavourite(id: String) = id in favourites

    fun toggleFavourite(id: String) {
        if (!favourites.remove(id)) favourites.add(0, id)
        prefs.edit().putString(favKey, favourites.joinToString(",")).apply()
    }

    fun used(id: String) {
        recent.remove(id)
        recent.add(0, id)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        prefs.edit().putString(recentKey, recent.joinToString(",")).apply()
    }

    companion object {
        private const val MAX_RECENT = 12
        private val cache = HashMap<String, Picks>()
        fun get(context: android.content.Context, kind: String): Picks =
            synchronized(cache) { cache.getOrPut(kind) { Picks(context.applicationContext, kind) } }
    }
}

/** The shared store for [kind], held across recompositions. */
@androidx.compose.runtime.Composable
internal fun rememberPicks(kind: String): Picks {
    val c = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(c, kind) { Picks.get(c, kind) }
}
