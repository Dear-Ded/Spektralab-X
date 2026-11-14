package com.dearded.spektralab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User film stocks (docs/STYLES.md section 7.6). Two kinds of file, both
 * stored as `user_<name>.json` and copied into the engine's profiles after
 * every resource install:
 *
 *  - a film recipe: an alias of a bundled stock, the same construction as
 *    `profiles-extra/cinestill_50d.json` (`derived_from` plus the few `info`
 *    keys the engine overrides), with this app's suggested settings under
 *    `spektralab_x.recipe`. No spectral data, no licence to carry.
 *  - full film data: checked here against what `profile.cpp` requires, then
 *    opened once by the engine ([StyleImport]); a file that fails is deleted.
 *
 * Plain Kotlin and org.json only.
 */
object FilmRecipe {
    const val PREFIX = "user_"
    const val MAX_DATA_BYTES = 4 shl 20
    const val MAX_RECIPE_BYTES = 64 shl 10
    private const val WAVELENGTHS = 81
    private const val MONOTONE_TOLERANCE = 0.005

    private val ANTIHALATION = setOf("strong", "weak", "no")

    /** An alias of [base]: [engine] holds the settings it suggests (film is [id] itself). */
    data class Recipe(
        val id: String,
        val name: String,
        val base: String,
        val targetPrint: String? = null,
        val antihalation: String? = null,
        val engine: SpkEngine = SpkEngine(),
    ) {
        /** The profile file: what the engine and [Catalog.load] read. */
        fun toJson(): JSONObject = JSONObject().apply {
            put("derived_from", base)
            put("metadata", JSONObject()
                .put("what", "User film recipe: $base under another name, with suggested settings.")
                .put("data", "None of its own. The engine loads $base whole and overrides only the info keys below."))
            put("info", JSONObject().apply {
                put("name", name)
                targetPrint?.let { put("target_print", it) }
                antihalation?.let { put("antihalation", it) }
            })
            put("spektralab_x", JSONObject().put("recipe", SpkLook.engineJson(engine)))
        }

        /** This recipe as a one-tap look on its own film. */
        fun toLook() = SpkLook(name = name, category = "胶片方案",
            engine = engine.copy(film = id, paper = engine.paper ?: targetPrint),
            source = SpkSource(kind = "film-recipe", note = "基于 $base 的胶片方案"))
    }

    /** `user_` plus [name] cut down to what the engine's valid_stock_name takes; never empty. */
    fun stockId(name: String): String {
        val body = name.lowercase().map { c -> if (c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-') c else '_' }
            .joinToString("").trim('_').replace(Regex("_+"), "_").ifEmpty { "film" }
        return (PREFIX + body.removePrefix(PREFIX)).take(96)
    }

    /**
     * Reads a recipe file. [isBase] says whether an id is a bundled stock that is
     * not itself an alias (the engine refuses an alias of an alias); [isPaper]
     * whether an id is a bundled paper.
     */
    fun parseRecipe(o: JSONObject, fileName: String, isBase: (String) -> Boolean, isPaper: (String) -> Boolean,
                    report: ImportReport): Recipe {
        val f = Fields(report)
        f.unknown(o, "", setOf("derived_from", "metadata", "info", "spektralab_x"))
        val base = o.optString("derived_from")
        if (!STOCK.matches(base)) throw ImportException("derived_from 不是有效的胶片 id")
        if (!isBase(base)) throw ImportException("胶片方案基于的“$base”不在当前版本里，或者它自己就是别名")
        val info = f.obj(o, "info") ?: JSONObject()
        f.unknown(info, "info", setOf("name", "target_print", "antihalation", "stock"))
        val name = info.optString("name").trim().take(60).ifEmpty { PresetImport.stem(fileName).ifEmpty { base } }
        val target = info.optString("target_print").takeIf { it.isNotEmpty() }?.let {
            if (STOCK.matches(it) && isPaper(it)) it else { report.ignored("info.target_print", "“${it.take(40)}”不是当前版本的相纸"); null }
        }
        val anti = info.optString("antihalation").takeIf { it.isNotEmpty() }?.let {
            if (it in ANTIHALATION) it else { report.ignored("info.antihalation", "应为 strong / weak / no"); null }
        }
        val x = f.obj(o, "spektralab_x")
        x?.let { f.unknown(it, "spektralab_x", setOf("recipe")) }
        val engine = x?.let { f.obj(it, "recipe", "spektralab_x") }?.let { SpkLook.readEngine(it, f) } ?: SpkEngine()
        val paper = engine.paper?.takeIf { isPaper(it).also { ok -> if (!ok) report.ignored("recipe.paper", "不是当前版本的相纸") } }
        return Recipe(stockId(name), name, base, target, anti, engine.copy(film = null, paper = paper))
    }

    /**
     * Checks full film or paper data against profile.cpp and returns the stage
     * ("filming" or "printing"). Throws [ImportException] with the first problem.
     * The bundled profiles all pass (nulls in the spectral arrays included).
     */
    fun validateData(o: JSONObject, report: ImportReport): String {
        val info = o.optJSONObject("info") ?: throw ImportException("没有 info")
        val type = info.optString("type")
        if (type != "negative" && type != "positive") throw ImportException("info.type 应为 negative 或 positive")
        val stage = info.optString("stage")
        if (stage != "filming" && stage != "printing") throw ImportException("info.stage 应为 filming 或 printing")
        val model = info.optString("channel_model", "color")
        if (model != "color" && model != "bw") throw ImportException("info.channel_model 应为 color 或 bw")
        val d = o.optJSONObject("data") ?: throw ImportException("没有 data")

        val wl = numbers(d, "wavelengths", nullable = false)
        if (wl.size != WAVELENGTHS) throw ImportException("wavelengths 有 ${wl.size} 点，应为 81（380–780 nm，步长 5）")
        for (i in wl.indices) if (kotlin.math.abs(wl[i] - (380.0 + 5 * i)) > 1e-6)
            throw ImportException("wavelengths 第 ${i + 1} 点是 ${wl[i]}，应为 ${380 + 5 * i}")
        matrix(d, "log_sensitivity", WAVELENGTHS, 3, nullable = true)
        matrix(d, "channel_density", WAVELENGTHS, 3, nullable = true)
        if (numbers(d, "base_density", nullable = true).size != WAVELENGTHS) throw ImportException("base_density 应为 81 点")
        if (d.has("midscale_neutral_density") && numbers(d, "midscale_neutral_density", nullable = true).size != WAVELENGTHS)
            throw ImportException("midscale_neutral_density 应为 81 点")

        val le = numbers(d, "log_exposure", nullable = false)
        if (le.size < 2) throw ImportException("log_exposure 至少要 2 点")
        for (i in 1 until le.size) if (le[i] <= le[i - 1]) throw ImportException("log_exposure 不是严格递增（第 ${i + 1} 点）")
        val dc = matrix(d, "density_curves", le.size, 3, nullable = false)
        val sign = if (type == "positive") -1.0 else 1.0
        for (c in 0 until 3) for (i in 1 until le.size) {
            val step = sign * (dc[i][c] - dc[i - 1][c])
            if (step < -MONOTONE_TOLERANCE)
                throw ImportException("density_curves 第 ${c + 1} 通道在第 ${i + 1} 点${if (sign > 0) "下降" else "回升"}（${"%.4f".format(java.util.Locale.ROOT, -step)}）")
        }
        if (d.has("density_curves_layers")) {
            val layers = d.optJSONArray("density_curves_layers") ?: throw ImportException("density_curves_layers 不是列表")
            if (layers.length() != le.size) throw ImportException("density_curves_layers 应有 ${le.size} 行")
            for (i in 0 until layers.length()) {
                val row = layers.optJSONArray(i) ?: throw ImportException("density_curves_layers 第 ${i + 1} 行不是列表")
                if (row.length() != 3) throw ImportException("density_curves_layers 应为 n×3×3")
                for (k in 0 until 3) row(row, k, 3, "density_curves_layers", nullable = false)
            }
        }
        if (o.optJSONObject("metadata")?.optString("license").isNullOrEmpty())
            report.approximated("metadata.license", "文件没有写许可，分享前请确认数据来源")
        report.mapped("胶片数据", "81 点光谱、${le.size} 点特性曲线，校验通过")
        return stage
    }

    /** [o] as it is stored under [id]: info.stock set to the file's own name, so the engine and catalog agree. */
    fun storedData(o: JSONObject, id: String): JSONObject {
        val copy = JSONObject(o.toString())
        copy.optJSONObject("info")?.put("stock", id)
        return copy
    }

    private fun numbers(d: JSONObject, key: String, nullable: Boolean): DoubleArray {
        val a = d.optJSONArray(key) ?: throw ImportException("data.$key 缺失或不是列表")
        return row(a, -1, a.length(), key, nullable)
    }

    private fun row(a: JSONArray, index: Int, n: Int, key: String, nullable: Boolean): DoubleArray {
        val arr = if (index < 0) a else a.optJSONArray(index) ?: throw ImportException("data.$key 的第 ${index + 1} 项不是列表")
        if (arr.length() != n) throw ImportException("data.$key 的形状不对（应为 $n 列）")
        return DoubleArray(n) { i ->
            if (arr.isNull(i)) {
                if (!nullable) throw ImportException("data.$key 里有空值")
                Double.NaN
            } else {
                val v = arr.optDouble(i, Double.NaN)
                if (!v.isFinite()) throw ImportException("data.$key 里有不是数的值")
                v
            }
        }
    }

    private fun matrix(d: JSONObject, key: String, rows: Int, cols: Int, nullable: Boolean): Array<DoubleArray> {
        val a = d.optJSONArray(key) ?: throw ImportException("data.$key 缺失或不是列表")
        if (a.length() != rows) throw ImportException("data.$key 应为 $rows×$cols，实际 ${a.length()} 行")
        return Array(rows) { row(a, it, cols, key, nullable) }
    }

    private val STOCK = Regex("[A-Za-z0-9_-]{1,96}")
}

/**
 * The user's stocks on disk, outside the engine directory (EngineAssets.install
 * deletes and rewrites that whole tree), copied into it after each install.
 */
class UserProfiles(private val dir: File) {
    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.name.startsWith(FilmRecipe.PREFIX) && f.extension == "json" }
        .orEmpty().sortedBy { it.name }

    /** Writes [json] as `<id>.json`, through a temporary file so a half-written profile never exists. */
    fun store(id: String, json: JSONObject): File {
        require(id.startsWith(FilmRecipe.PREFIX) && Regex("[a-z0-9_-]{1,96}").matches(id)) { "bad user stock id $id" }
        dir.mkdirs()
        val target = File(dir, "$id.json")
        writeAtomicText(target, json.toString())
        return target
    }

    fun delete(id: String, engineDir: File?) {
        require(id.startsWith(FilmRecipe.PREFIX) && Regex("[a-z0-9_-]{1,96}").matches(id)) { "bad user stock id $id" }
        java.nio.file.Files.deleteIfExists(File(dir, "$id.json").toPath())
        engineDir?.let { java.nio.file.Files.deleteIfExists(File(it, "profiles/$id.json").toPath()) }
    }

    /** A free id for [wanted]: itself, or with _2, _3 … when [taken] says it is in use. */
    fun freeId(wanted: String, taken: (String) -> Boolean): String {
        if (!taken(wanted) && !File(dir, "$wanted.json").exists()) return wanted
        for (n in 2..999) {
            val id = "${wanted.take(92)}_$n"
            if (!taken(id) && !File(dir, "$id.json").exists()) return id
        }
        error("no free id for $wanted")
    }

    /** Copies every user stock into [engineDir]/profiles; returns how many. */
    fun syncInto(engineDir: File): Int {
        val profiles = File(engineDir, "profiles")
        if (!profiles.isDirectory) return 0
        var n = 0
        for (f in list()) {
            val dest = File(profiles, f.name)
            if (dest.exists() && dest.length() == f.length() && dest.lastModified() >= f.lastModified()) { n++; continue }
            writeAtomicBytes(dest, f.readBytes())
            n++
        }
        return n
    }
}
