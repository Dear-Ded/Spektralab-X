package com.dearded.spektralab

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved look: film, paper and everything printed or simulated after them.
 * Per-photo choices (exposure, auto exposure, geometry, RAW white balance)
 * are deliberately not part of it, so applying a preset never reframes or
 * re-exposes the open photo.
 */
data class Preset(
    val name: String,
    val film: String,
    val paper: String,
    val scanFilm: Boolean,
    val filmFormatMm: Double,
    val printExposure: Double,
    val yShift: Double,
    val mShift: Double,
    val grainActive: Boolean,
    val grainAmount: Double,
    val halationActive: Boolean,
    val halationAmount: Double,
    val glareActive: Boolean,
    val glareAmount: Double,
) {
    fun applyTo(edit: Edit) = edit.copy(
        film = film, paper = paper, scanFilm = scanFilm, filmFormatMm = filmFormatMm,
        printExposure = printExposure, yShift = yShift, mShift = mShift,
        grainActive = grainActive, grainAmount = grainAmount,
        halationActive = halationActive, halationAmount = halationAmount,
        glareActive = glareActive, glareAmount = glareAmount,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("film", film).put("paper", paper).put("scanFilm", scanFilm)
        .put("filmFormatMm", filmFormatMm).put("printExposure", printExposure)
        .put("yShift", yShift).put("mShift", mShift)
        .put("grainActive", grainActive).put("grainAmount", grainAmount)
        .put("halationActive", halationActive).put("halationAmount", halationAmount)
        .put("glareActive", glareActive).put("glareAmount", glareAmount)

    companion object {
        fun of(name: String, e: Edit) = Preset(
            name, e.film, e.paper, e.scanFilm, e.filmFormatMm, e.printExposure, e.yShift, e.mShift,
            e.grainActive, e.grainAmount, e.halationActive, e.halationAmount, e.glareActive, e.glareAmount,
        )

        /** Missing keys take the defaults of a fresh [Edit], so older saves keep loading. */
        fun fromJson(o: JSONObject): Preset? {
            val d = Edit()
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            return Preset(
                name = name,
                film = o.optString("film", d.film),
                paper = o.optString("paper", d.paper),
                scanFilm = o.optBoolean("scanFilm", d.scanFilm),
                filmFormatMm = o.optDouble("filmFormatMm", d.filmFormatMm),
                printExposure = o.optDouble("printExposure", d.printExposure),
                yShift = o.optDouble("yShift", d.yShift),
                mShift = o.optDouble("mShift", d.mShift),
                grainActive = o.optBoolean("grainActive", d.grainActive),
                grainAmount = o.optDouble("grainAmount", d.grainAmount),
                halationActive = o.optBoolean("halationActive", d.halationActive),
                halationAmount = o.optDouble("halationAmount", d.halationAmount),
                glareActive = o.optBoolean("glareActive", d.glareActive),
                glareAmount = o.optDouble("glareAmount", d.glareAmount),
            )
        }
    }
}

/** User presets in SharedPreferences, as one JSON array, in the order saved. */
class PresetStore(private val prefs: SharedPreferences) {
    fun load(): List<Preset> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Preset::fromJson) }
    }.getOrDefault(emptyList())

    /** Saving under an existing name replaces that preset in place. */
    fun save(preset: Preset): List<Preset> {
        val list = load().toMutableList()
        val at = list.indexOfFirst { it.name == preset.name }
        if (at >= 0) list[at] = preset else list += preset
        return write(list)
    }

    fun delete(name: String): List<Preset> = write(load().filterNot { it.name == name })

    private fun write(list: List<Preset>): List<Preset> {
        prefs.edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
        return list
    }

    private companion object { const val KEY = "presets" }
}
