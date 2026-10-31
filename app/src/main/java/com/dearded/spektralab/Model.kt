package com.dearded.spektralab

import org.json.JSONObject
import java.io.File

data class Stock(
    val id: String,
    val label: String,
    val positive: Boolean,
    val bw: Boolean,
    val targetPrint: String?,
)

/** Film and paper stocks, read from the bundled profiles the way the desktop host reads them. */
data class Catalog(val films: List<Stock>, val papers: List<Stock>) {
    fun film(id: String) = films.firstOrNull { it.id == id }
    fun paper(id: String) = papers.firstOrNull { it.id == id }

    companion object {
        val EMPTY = Catalog(emptyList(), emptyList())

        fun load(engineDir: File): Catalog {
            val files = File(engineDir, "profiles").listFiles { f -> f.extension == "json" }.orEmpty()
            val roots = files.associate { it.nameWithoutExtension to JSONObject(it.readText()) }
            val films = mutableListOf<Stock>()
            val papers = mutableListOf<Stock>()
            for ((id, root) in roots) {
                var info = root.optJSONObject("info") ?: JSONObject()
                // An alias (CineStill 800T) takes its base's info, then its own keys over it.
                val base = root.optString("derived_from", "")
                if (base.isNotEmpty()) {
                    val baseInfo = roots[base]?.optJSONObject("info") ?: continue
                    val merged = JSONObject(baseInfo.toString())
                    for (key in info.keys()) merged.put(key, info.get(key))
                    info = merged
                }
                val stock = Stock(
                    id = id,
                    label = info.optString("name", id),
                    positive = info.optString("type") == "positive",
                    bw = info.optString("channel_model") == "bw",
                    targetPrint = if (info.isNull("target_print")) null else info.optString("target_print"),
                )
                when (info.optString("stage")) {
                    "filming" -> films += stock
                    "printing" -> papers += stock
                }
            }
            return Catalog(films.sortedBy { it.label }, papers.sortedBy { it.label })
        }
    }
}

/** The user-facing subset of the engine's parameters (names as on the wire). */
data class Edit(
    val film: String = "kodak_portra_400",
    val paper: String = "kodak_portra_endura",
    val scanFilm: Boolean = false,
    val autoExposure: Boolean = true,
    val exposureEv: Double = 0.0,
    val filmFormatMm: Double = 35.0,
    val printExposure: Double = 1.0,
    val yShift: Double = 0.0,
    val mShift: Double = 0.0,
    val grainActive: Boolean = true,
    val grainAmount: Double = 1.0,
    val halationActive: Boolean = true,
    val halationAmount: Double = 1.0,
    val glareActive: Boolean = true,
    val glareAmount: Double = 1.0,
) {
    fun toJson(): JSONObject = JSONObject().apply { for ((key, get) in FIELDS) put(key, get(this@Edit)) }

    /**
     * Only changed fields: an unchanged shoot-side field would invalidate the
     * cached negative. `scan_film` goes with every film change, otherwise a
     * slide's direct-scan mode would survive switching back to a negative.
     */
    fun delta(previous: Edit): JSONObject = JSONObject().apply {
        for ((key, get) in FIELDS) {
            val value = get(this@Edit)
            if (value != get(previous)) put(key, value)
        }
        if (film != previous.film) put("scan_film", scanFilm)
    }

    companion object {
        private val FIELDS: List<Pair<String, (Edit) -> Any>> = listOf(
            "film_stock" to { e: Edit -> e.film },
            "print_stock" to { e: Edit -> e.paper },
            "scan_film" to { e: Edit -> e.scanFilm },
            "auto_exposure" to { e: Edit -> e.autoExposure },
            "exposure_compensation_ev" to { e: Edit -> e.exposureEv },
            "film_format_mm" to { e: Edit -> e.filmFormatMm },
            "print_exposure" to { e: Edit -> e.printExposure },
            "y_filter_shift" to { e: Edit -> e.yShift },
            "m_filter_shift" to { e: Edit -> e.mShift },
            "grain_active" to { e: Edit -> e.grainActive },
            "grain_amount" to { e: Edit -> e.grainAmount },
            "halation_active" to { e: Edit -> e.halationActive },
            "halation_amount" to { e: Edit -> e.halationAmount },
            "glare_active" to { e: Edit -> e.glareActive },
            "glare_amount" to { e: Edit -> e.glareAmount },
        )

        /** The session's resolved values, as `spk_get_params` reports them. */
        fun fromParams(p: JSONObject, fallback: Edit) = Edit(
            film = p.optString("film_stock", fallback.film),
            paper = p.optString("print_stock", fallback.paper),
            scanFilm = p.optBoolean("scan_film", fallback.scanFilm),
            autoExposure = p.optBoolean("auto_exposure", fallback.autoExposure),
            exposureEv = p.optDouble("exposure_compensation_ev", fallback.exposureEv),
            filmFormatMm = p.optDouble("film_format_mm", fallback.filmFormatMm),
            printExposure = p.optDouble("print_exposure", fallback.printExposure),
            yShift = p.optDouble("y_filter_shift", fallback.yShift),
            mShift = p.optDouble("m_filter_shift", fallback.mShift),
            grainActive = p.optBoolean("grain_active", fallback.grainActive),
            grainAmount = p.optDouble("grain_amount", fallback.grainAmount),
            halationActive = p.optBoolean("halation_active", fallback.halationActive),
            halationAmount = p.optDouble("halation_amount", fallback.halationAmount),
            glareActive = p.optBoolean("glare_active", fallback.glareActive),
            glareAmount = p.optDouble("glare_amount", fallback.glareAmount),
        )

        fun openingParams(edit: Edit, raw: Boolean): JSONObject = edit.toJson().apply {
            // LibRaw decodes to linear ProPhoto; gallery images are encoded sRGB.
            put("input_color_space", if (raw) "ProPhoto RGB" else "sRGB")
            put("input_cctf_decoding", !raw)
            put("output_color_space", "sRGB")
            put("output_cctf_encoding", true)
            put("extended_dynamic_range", false)
            put("product_defaults", true)
        }
    }
}

data class SourceInfo(
    val name: String,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val kind: String,
    val camera: String,
)
