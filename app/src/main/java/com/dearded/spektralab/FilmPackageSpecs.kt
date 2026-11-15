package com.dearded.spektralab

import androidx.compose.ui.graphics.Color

internal enum class PackageLayout { Portra, Ektar, TriX, Fuji, CineStill, Ilford, Kodak, Reference }
internal enum class PackageFilmBase(val colour: Color) {
    Negative(Color(0xFF8B5A2B).copy(alpha = .88f)),
    Slide(Color(0xFF2C2F36).copy(alpha = .90f)),
    Silver(Color(0xFF4A4658).copy(alpha = .88f)),
    Cinema(Color(0xFF3B2824).copy(alpha = .90f)),
}

/** Packaging facts are independent of the engine's profiles and pushed exposure indices. */
internal data class FilmPackageSpec(
    val brand: String, val model: String, val speed: String?, val iso: Int?,
    val process: String?, val retail36: Boolean, val layout: PackageLayout,
    val face: Color, val ink: Color = Color(0xFF161618), val base: PackageFilmBase = PackageFilmBase.Negative,
) {
    val sensitivity: String? get() = iso?.let {
        val din = kotlin.math.round(10.0 * kotlin.math.log10(it.toDouble()) + 1.0).toInt()
        "$it/$din\u00b0"
    }
}

internal fun filmPackageSpec(stock: Stock): FilmPackageSpec {
    val gold = Color(0xFFF4B41A)
    val paper = Color(0xFFF0F0F2)
    fun kodak(model: String, speed: String, iso: Int, layout: PackageLayout = PackageLayout.Kodak,
              face: Color = gold, process: String = "C-41", base: PackageFilmBase = PackageFilmBase.Negative,
              retail: Boolean = true) = FilmPackageSpec("KODAK", model, speed, iso, process, retail, layout, face,
        if (layout == PackageLayout.TriX) paper else Color(0xFF161618), base)
    fun fuji(model: String, speed: String, iso: Int, face: Color, process: String = "E-6",
             base: PackageFilmBase = PackageFilmBase.Slide) = FilmPackageSpec("FUJIFILM", model, speed, iso, process, true,
        PackageLayout.Fuji, face, Color.White, base)
    return when (stock.id) {
        "kodak_portra_160" -> kodak("PORTRA", "160", 160, PackageLayout.Portra)
        "kodak_portra_400" -> kodak("PORTRA", "400", 400, PackageLayout.Portra)
        "kodak_portra_800", "kodak_portra_800_push1", "kodak_portra_800_push2" ->
            kodak("PORTRA", "800", 800, PackageLayout.Portra)
        "kodak_ektar_100" -> kodak("EKTAR", "100", 100, PackageLayout.Ektar, Color(0xFFE63329))
        "kodak_tri_x_400" -> kodak("TRI-X", "400TX", 400, PackageLayout.TriX,
            Color(0xFF161618), "B&W", PackageFilmBase.Silver)
        "kodak_tmax_100" -> kodak("T-MAX", "100", 100, process = "B&W", base = PackageFilmBase.Silver)
        "kodak_gold_200" -> kodak("GOLD", "200", 200)
        "kodak_ultramax_400" -> kodak("ULTRAMAX", "400", 400)
        "kodak_ektachrome_100" -> kodak("EKTACHROME", "100", 100, process = "E-6", base = PackageFilmBase.Slide)
        "kodak_kodachrome_64" -> kodak("KODACHROME", "64", 64, process = "K-14", base = PackageFilmBase.Slide)
        "kodak_vision3_50d" -> kodak("VISION3", "50D", 50, process = "ECN-2", base = PackageFilmBase.Cinema, retail = false)
        "kodak_vision3_250d" -> kodak("VISION3", "250D", 250, process = "ECN-2", base = PackageFilmBase.Cinema, retail = false)
        "kodak_vision3_200t" -> kodak("VISION3", "200T", 200, process = "ECN-2", base = PackageFilmBase.Cinema, retail = false)
        "kodak_vision3_500t" -> kodak("VISION3", "500T", 500, process = "ECN-2", base = PackageFilmBase.Cinema, retail = false)
        "cinestill_800t" -> FilmPackageSpec("CineStill", "TUNGSTEN", "800T", 800, "C-41", true,
            PackageLayout.CineStill, Color(0xFF18181C), Color(0xFFD94A38), PackageFilmBase.Cinema)
        "cinestill_50d" -> FilmPackageSpec("CineStill", "DAYLIGHT", "50D", 50, "C-41", true,
            PackageLayout.CineStill, Color(0xFF18181C), Color(0xFFD94A38), PackageFilmBase.Cinema)
        "fujifilm_velvia_100" -> fuji("Velvia", "100", 100, Color(0xFF0E7C47))
        "fujifilm_provia_100f" -> fuji("PROVIA", "100F", 100, Color(0xFF1A4B9C))
        "fujifilm_c200" -> fuji("FUJICOLOR", "200", 200, Color(0xFF0E7C47), "C-41", PackageFilmBase.Negative)
        "fujifilm_xtra_400" -> fuji("SUPERIA", "400", 400, Color(0xFF0E7C47), "C-41", PackageFilmBase.Negative)
        "fujifilm_pro_400h" -> fuji("PRO 400H", "400", 400, Color(0xFF0E7C47), "C-41", PackageFilmBase.Negative)
        "fujifilm_neopan_acros_100_ii" -> fuji("ACROS II", "100", 100, Color(0xFF26282A), "B&W", PackageFilmBase.Silver)
        "ilford_hp5_plus_400" -> FilmPackageSpec("ILFORD", "HP5 PLUS", "400", 400, "B&W", true,
            PackageLayout.Ilford, paper, Color(0xFF141416), PackageFilmBase.Silver)
        else -> FilmPackageSpec(Brands.filmBrand(stock.label)?.name.orEmpty(), stock.label, null, null,
            null, false, PackageLayout.Reference, Color(0xFF343438), paper,
            if (stock.bw) PackageFilmBase.Silver else if (stock.positive) PackageFilmBase.Slide else PackageFilmBase.Negative)
    }
}
