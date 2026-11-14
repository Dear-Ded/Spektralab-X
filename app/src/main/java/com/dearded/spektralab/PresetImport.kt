package com.dearded.spektralab

import org.json.JSONObject
import java.util.Locale

/**
 * Other apps' preset files as [SpkLook]s (docs/STYLES.md sections 7.3-7.5):
 * Lightroom XMP / DNG presets, spektrafilm GUI states and Fuji recipe text.
 * Every value is clamped to its slider's range and every setting that did
 * not carry over exactly goes into the [ImportReport]. Plain Kotlin and
 * org.json only.
 */
object PresetImport {
    /** What a JSON file is, by its keys. */
    enum class JsonKind { SPKLOOK, FILM_RECIPE, FILM_DATA, SPEKTRAFILM, UNKNOWN }

    fun classifyJson(o: JSONObject): JsonKind = when {
        o.optString("format") == SpkLook.FORMAT -> JsonKind.SPKLOOK
        o.has("derived_from") -> JsonKind.FILM_RECIPE
        o.optJSONObject("data")?.has("wavelengths") == true -> JsonKind.FILM_DATA
        o.optJSONObject("simulation")?.has("selection") == true || o.has("film_stock") || o.has("print_paper") ->
            JsonKind.SPEKTRAFILM
        else -> JsonKind.UNKNOWN
    }

    // --- Lightroom ------------------------------------------------------------

    const val MAX_XMP_BYTES = 32 shl 20

    private val DOCTYPE = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""crs:([A-Za-z0-9]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ELEM = Regex("""<crs:([A-Za-z0-9]+)>([^<]*)</crs:\1>""")
    private val OPEN = Regex("""<crs:([A-Za-z0-9]+)[\s>/]""")
    private val NAME = Regex("""<crs:Name>\s*<rdf:Alt>\s*<rdf:li[^>]*>([^<]*)</rdf:li>""")
    private val CURVE = Regex("""<crs:(ToneCurvePV2012(?:Red|Green|Blue)?)>(.*?)</crs:\1>""", RegexOption.DOT_MATCHES_ALL)
    private val LI = Regex("""<rdf:li>([^<]*)</rdf:li>""")

    /** Lightroom's colour bands, in Look.hsl order. */
    private val LR_BANDS = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
    private val LR_IGNORED get() = mapOf(
        "Clarity2012" to tr("清晰度"), "Texture" to tr("纹理"), "Dehaze" to tr("去朦胧"), "Sharpness" to tr("锐化"),
        "LuminanceSmoothing" to tr("明亮度降噪"), "ColorNoiseReduction" to tr("颜色降噪"),
        "ParametricShadows" to tr("参数曲线"), "ParametricDarks" to tr("参数曲线"),
        "ParametricLights" to tr("参数曲线"), "ParametricHighlights" to tr("参数曲线"),
        "PerspectiveUpright" to tr("透视校正"), "LensManualDistortionAmount" to tr("镜头畸变"),
        "DefringePurpleAmount" to tr("去边"), "DefringeGreenAmount" to tr("去边"),
    )
    private val LR_AUTO = listOf("AutoTone", "AutoExposure", "AutoContrast", "AutoBrightness", "AutoShadows",
        "AutoWhiteVersion")

    /**
     * A Lightroom preset: an .xmp file, or a DNG with its xmpmeta packet.
     * Refuses any DOCTYPE or ENTITY declaration before reading a value, and
     * reads values with patterns rather than an XML parser, so no entity is
     * ever expanded or fetched.
     */
    fun fromXmp(bytes: ByteArray, fileName: String, report: ImportReport): SpkLook {
        if (bytes.size > MAX_XMP_BYTES) throw ImportException(tr("预设文件超过 32 MB"))
        // ISO-8859-1 maps byte i to char i, so indices found here are byte offsets.
        val latin = String(bytes, Charsets.ISO_8859_1)
        // The whole file, not only the packet: a declaration before <x:xmpmeta is refused too.
        if (DOCTYPE.containsMatchIn(latin)) throw ImportException(tr("预设里有 DOCTYPE 或实体声明，为安全起见不读"))
        val start = latin.indexOf("<x:xmpmeta")
        val end = if (start >= 0) latin.indexOf("</x:xmpmeta>", start) else -1
        val text = String(if (start >= 0 && end > start) bytes.copyOfRange(start, end + 12) else bytes, Charsets.UTF_8)

        val f = HashMap<String, String>()
        for (m in ATTR.findAll(text)) f.putIfAbsent(m.groupValues[1], unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }))
        for (m in ELEM.findAll(text)) f.putIfAbsent(m.groupValues[1], unescape(m.groupValues[2].trim()))
        val present = OPEN.findAll(text).map { it.groupValues[1] }.toSet() + f.keys
        if (present.isEmpty()) throw ImportException(tr("没有找到 Lightroom 参数（crs:）"))
        f["ProcessVersion"]?.trim()?.toDoubleOrNull()?.let {
            if (it < 6.7) throw ImportException(tr("处理版本 {0} 太旧（低于 6.7），那时的滑杆含义不同", it))
        }

        fun num(key: String) = f[key]?.trim()?.removePrefix("+")?.toDoubleOrNull()?.takeIf(Double::isFinite)
        fun on(key: String) = f[key]?.trim().equals("True", ignoreCase = true)
        fun take(key: String, to: String, lo: Double, hi: Double, k: Double = 1.0, why: String? = null): Double {
            val v = num(key) ?: return 0.0
            if (v == 0.0) return 0.0
            val x = v * k
            val y = x.coerceIn(lo, hi)
            when {
                y != x -> report.approximated(key, tr("→ {0}，{1} 超出范围，已夹到 {2}", to, fmt(x), fmt(y)))
                why != null -> report.approximated(key, "→ $to，$why")
                else -> report.mapped(key, "→ $to")
            }
            return y
        }

        val exposure = take("Exposure2012", tr("曝光"), -3.0, 3.0)
        val contrast = take("Contrast2012", tr("对比"), -50.0, 50.0, 0.5, tr("范围不同，按一半换算"))
        var highlights = take("Highlights2012", tr("高光"), -100.0, 100.0, why = tr("曲线形状不同"))
        val shadows = take("Shadows2012", tr("阴影"), -100.0, 100.0, why = tr("曲线形状不同"))
        var whitePoint = 0.0
        var blackPoint = 0.0
        var fade = 0.0
        num("Whites2012")?.takeIf { it != 0.0 }?.let { w ->
            if (w > 0) whitePoint = (w * 0.25).coerceIn(0.0, 50.0).also { report.approximated("Whites2012", tr("→ 白场")) }
            else highlights = (highlights + w * 0.3).coerceIn(-100.0, 100.0).also { report.approximated("Whites2012", tr("→ 高光")) }
        }
        num("Blacks2012")?.takeIf { it != 0.0 }?.let { b ->
            if (b < 0) blackPoint = (-b * 0.25).coerceIn(0.0, 50.0).also { report.approximated("Blacks2012", tr("→ 黑场")) }
            else fade = (b * 0.3).coerceIn(0.0, 100.0).also { report.approximated("Blacks2012", tr("→ 褪色")) }
        }
        var saturation = take("Saturation", tr("饱和"), -100.0, 100.0)
        val vibrance = take("Vibrance", tr("自然饱和"), -100.0, 100.0)
        val temperature = take("IncrementalTemperature", tr("色温"), -100.0, 100.0, 0.5, tr("刻度不同，按一半换算"))
        val tint = take("IncrementalTint", tr("色调"), -100.0, 100.0, 0.5, tr("刻度不同，按一半换算"))
        if (num("Temperature") != null || num("Tint") != null)
            report.ignored("Temperature / Tint", tr("白平衡数值属于每张照片，不随风格套用"))

        val hsl = List(8) { i ->
            val name = LR_BANDS[i]
            Band(take("HueAdjustment$name", tr("{0} 色相", Look.BANDS[i].first), -100.0, 100.0),
                take("SaturationAdjustment$name", tr("{0} 饱和", Look.BANDS[i].first), -100.0, 100.0),
                take("LuminanceAdjustment$name", tr("{0} 明亮", Look.BANDS[i].first), -100.0, 100.0))
        }

        fun grade(lr: String, label: String, split: String?): Zone {
            val hue = num("ColorGrade${lr}Hue") ?: 0.0
            val sat = num("ColorGrade${lr}Sat") ?: 0.0
            val lum = num("ColorGrade${lr}Lum") ?: 0.0
            if (sat != 0.0 || lum != 0.0) {
                report.mapped("ColorGrade$lr", tr("→ {0} 色轮", label))
                return Zone(((hue % 360) + 360) % 360, sat.coerceIn(0.0, 100.0), lum.coerceIn(-100.0, 100.0))
            }
            if (split == null || f.containsKey("ColorGrade${lr}Sat")) return Zone()
            val sSat = num("SplitToning${split}Saturation") ?: 0.0
            if (sSat == 0.0) return Zone()
            report.mapped("SplitToning$split", tr("→ {0} 色轮（旧版分离色调）", label))
            val sHue = num("SplitToning${split}Hue") ?: 0.0
            return Zone(((sHue % 360) + 360) % 360, sSat.coerceIn(0.0, 100.0))
        }
        val master = grade("Global", tr("整体"), null)
        val shadowZone = grade("Shadow", tr("暗部"), "Shadow")
        val midZone = grade("Midtone", tr("中间调"), null)
        val highlightZone = grade("Highlight", tr("高光"), "Highlight")
        num("ColorGradeBlending")?.let { if (it != 50.0) report.ignored("ColorGradeBlending", tr("没有对应参数")) }
        num("SplitToningBalance")?.let { if (it != 0.0) report.ignored("SplitToningBalance", tr("没有对应参数")) }

        val vignette = take("PostCropVignetteAmount", tr("暗角"), -100.0, 100.0, why = tr("暗角形状不同"))
        val vignetteMid = if (vignette != 0.0) (num("PostCropVignetteMidpoint") ?: 50.0).coerceIn(0.0, 100.0) else 50.0
        val grain = num("GrainAmount")?.takeIf { it > 0 }?.let {
            report.approximated("GrainAmount", tr("→ 胶片颗粒，乳剂颗粒不是数字噪点"))
            (it / 50).coerceIn(0.0, 2.0)
        }
        if (on("ConvertToGrayscale")) {
            saturation = -100.0
            report.approximated("ConvertToGrayscale", tr("彩色片上去色，不是黑白胶片"))
        }

        for ((key, label) in LR_IGNORED) num(key)?.let { if (it != 0.0) report.ignored(key, tr("{0} 没有对应参数", label)) }
        if (on("LensProfileEnable")) report.ignored("LensProfileEnable", tr("镜头校正属于每支镜头"))
        for (key in LR_AUTO) if (on(key)) report.ignored(key, tr("自动值要逐张计算"))
        // Point curves, 0..255 in Lightroom, onto the same rows as upstream's: base -> rgb, then red, green, blue.
        var curves = CurveSet.IDENTITY
        for (m in CURVE.findAll(text)) {
            val key = m.groupValues[1]
            val pts = LI.findAll(m.groupValues[2]).mapNotNull { li ->
                val xy = li.groupValues[1].split(',').map { it.trim().toDoubleOrNull() }
                if (xy.size == 2 && xy[0] != null && xy[1] != null) CurvePoint(xy[0]!! / 255, xy[1]!! / 255) else null
            }.toList()
            if (pts.isEmpty() || pts == Curve.IDENTITY_POINTS) continue
            val arr = org.json.JSONArray().also { a -> pts.forEach { a.put(org.json.JSONArray().put(it.x).put(it.y)) } }
            val curve = Curve.fromJson(arr)
            if (curve.isIdentity) { report.ignored(key, tr("曲线的点不合规（须 2–256 个、x 递增、在 0–255 内）")); continue }
            val row = when (key) { "ToneCurvePV2012Red" -> 2; "ToneCurvePV2012Green" -> 3; "ToneCurvePV2012Blue" -> 4; else -> 0 }
            curves = curves.with(row, curve)
            report.mapped(key, tr("→ 曲线"))
        }
        f["CameraProfile"]?.let { if (!it.startsWith("Adobe Standard")) report.ignored("CameraProfile", tr("相机配置文件“{0}”属于 Adobe", it.take(40))) }
        if ("Look" in present) report.ignored("Look", tr("预设内嵌的配置文件（Look）读不了"))

        val name = (NAME.find(text)?.groupValues?.get(1)?.let(::unescape) ?: f["Name"]).orEmpty().trim()
            .ifEmpty { stem(fileName) }.take(60)
        return SpkLook(
            name = name,
            engine = SpkEngine(grain = grain),
            layer2 = Look(
                temperature = temperature, tint = tint, exposure = exposure, contrast = contrast,
                saturation = saturation, vibrance = vibrance, highlights = highlights, shadows = shadows,
                blackPoint = blackPoint, whitePoint = whitePoint, fade = fade,
                vignette = vignette, vignetteMid = vignetteMid,
                master = master, shadowZone = shadowZone, midZone = midZone, highlightZone = highlightZone, hsl = hsl,
                curves = curves,
            ),
            source = SpkSource("xmp", fileName.take(120), tr("Lightroom 预设（近似）")),
        )
    }

    // --- spektrafilm ----------------------------------------------------------

    /**
     * A spektrafilm GUI state (gui_default_state.json): the grouped layout of
     * current versions, or the flat one of older ones. Stock ids are the
     * same data as this app's, so they carry over as they are.
     */
    fun fromSpektrafilm(o: JSONObject, fileName: String, report: ImportReport): SpkLook {
        val sim = o.optJSONObject("simulation")
        val sel = sim?.optJSONObject("selection")
        val enl = sim?.optJSONObject("enlarger")
        val cam = o.optJSONObject("camera")
        fun str(obj: JSONObject?, key: String) = obj?.optString(key)?.takeIf { it.isNotEmpty() && obj.has(key) }
        fun dbl(obj: JSONObject?, key: String) = obj?.takeIf { it.has(key) }?.optDouble(key)?.takeIf(Double::isFinite)
        fun stock(id: String?, key: String): String? {
            if (id == null) return null
            if (STOCK.matches(id)) { report.mapped(key, id); return id }
            report.ignored(key, tr("不是有效的胶片 id"))
            return null
        }
        fun clamp(key: String, v: Double?, lo: Double, hi: Double): Double? {
            if (v == null) return null
            val c = v.coerceIn(lo, hi)
            if (c != v) report.approximated(key, tr("{0} 超出范围，已夹到 {1}", fmt(v), fmt(c))) else report.mapped(key)
            return c
        }
        val film = stock(str(sel, "film_stock") ?: str(o, "film_stock"), "film_stock")
        val paper = stock(str(sel, "print_paper") ?: str(o, "print_paper"), "print_paper")
        // Which keys are there, not whether they are valid: a bad id is reported, not fatal.
        val anyKey = sim != null || o.has("film_stock") || o.has("print_paper")
        if (!anyKey) throw ImportException(tr("不像 spektrafilm 的预设：没有胶片和相纸"))
        val printExposure = clamp("print_exposure", dbl(enl, "print_exposure") ?: dbl(o, "print_exposure"), 0.2, 3.0)
        // spektrafilm's shifts are in CC (EnlargerParams; its filter_shift_scale is 1).
        val y = clamp("y_filter_shift", dbl(enl, "y_filter_shift") ?: dbl(o, "print_y_filter_shift"),
            -Edit.FILTER_SHIFT_CC, Edit.FILTER_SHIFT_CC)
        val m = clamp("m_filter_shift", dbl(enl, "m_filter_shift") ?: dbl(o, "print_m_filter_shift"),
            -Edit.FILTER_SHIFT_CC, Edit.FILTER_SHIFT_CC)
        val ev = clamp("exposure_compensation_ev", dbl(cam, "exposure_compensation_ev") ?: dbl(o, "exposure_compensation_ev"), -4.0, 4.0)
        val format = clamp("film_format_mm", dbl(cam, "film_format_mm") ?: dbl(o, "film_format_mm"), 4.0, 200.0)
        fun off(section: String) = o.optJSONObject(section)?.let { it.has("active") && !it.optBoolean("active", true) } == true

        if (str(enl, "illuminant")?.let { it != "TH-KG3" } == true) report.ignored("enlarger.illuminant", tr("放大机光源固定为 TH-KG3"))
        if (sim?.optJSONObject("io")?.optBoolean("scan_film") == true) report.ignored("scan_film", tr("按胶片类型决定（反转片直接扫描）"))
        if (dbl(o.optJSONObject("preflashing"), "preflash_exposure")?.let { it > 0 } == true) report.ignored("preflashing", tr("放大机预闪没有开放"))
        if (o.optJSONObject("chemistry")?.optBoolean("active") == true) report.ignored("chemistry", tr("相纸曲线形变没有开放"))
        for (key in listOf("enlarger_diffusion", "camera_diffusion"))
            if (o.optJSONObject(key)?.optBoolean("active") == true) report.ignored(key, tr("扩散滤镜没有开放"))
        o.optJSONObject("couplers")?.let { c ->
            if ((c.has("active") && !c.optBoolean("active", true)) || c.optDouble("amount", 1.0) != 1.0)
                report.ignored("couplers", tr("DIR 耦合按胶片数据，没有开放"))
        }
        if (o.has("grain") && !off("grain")) report.ignored(tr("grain 细节"), tr("颗粒粒径、均匀度等用胶片自带值"))
        if (o.has("halation") && !off("halation")) report.ignored(tr("halation 细节"), tr("光晕散射参数用胶片自带值"))

        return SpkLook(
            name = stem(fileName).take(60),
            engine = SpkEngine(
                film = film, paper = paper, exposureEv = ev, printExposure = printExposure, yCc = y, mCc = m, formatMm = format,
                grain = if (off("grain")) 0.0 else null,
                halation = if (off("halation")) 0.0 else null,
                glare = if (off("glare")) 0.0 else null,
            ),
            source = SpkSource("spektrafilm", fileName.take(120), tr("spektrafilm 预设")),
        )
    }

    // --- Fuji recipes ---------------------------------------------------------

    const val MAX_RECIPE_BYTES = 64 shl 10

    private val SIM_LINE = Regex("(?im)^\\s*film\\s*simulation\\s*[:：]")
    private val LINE = Regex("^\\s*([A-Za-z][A-Za-z0-9 .&/()+-]*?)\\s*[:：]\\s*(.+?)\\s*$")
    private val NUMBER = Regex("[+-]?\\d+(?:\\.\\d+)?")
    private val WB_SHIFT = listOf(
        Regex("([+-]?\\d+)\\s*red.*?([+-]?\\d+)\\s*blue", RegexOption.IGNORE_CASE),
        Regex("\\br\\s*:?\\s*([+-]?\\d+).*?\\bb\\s*:?\\s*([+-]?\\d+)", RegexOption.IGNORE_CASE),
    )
    private val FUJI_IGNORED get() = mapOf(
        "colorchromeeffect" to tr("色彩效果没有对应参数"), "colorchromefxblue" to tr("蓝色效果没有对应参数"),
        "colorchromeeffectblue" to tr("蓝色效果没有对应参数"), "clarity" to tr("清晰度没有对应参数"),
        "sharpness" to tr("锐化没有对应参数"), "highisonr" to tr("降噪没有对应参数"), "noisereduction" to tr("降噪没有对应参数"),
        "iso" to tr("感光度逐张设置"), "exposurecompensation" to tr("曝光补偿逐张设置"),
        "dynamicrangepriority" to tr("动态范围优先没有对应参数"), "dynamicrangepriorityd" to tr("动态范围优先没有对应参数"),
    )

    fun looksLikeFujiRecipe(text: String) = SIM_LINE.containsMatchIn(text)

    /**
     * A Fuji recipe ("Film Simulation: Classic Chrome" and so on, one per line)
     * on top of the library style its film simulation points at. [base] looks
     * a style up by id (Style.ALL on the phone).
     */
    fun fromFujiRecipe(text: String, name: String, base: (String) -> SpkLook?, report: ImportReport): SpkLook {
        if (text.length > MAX_RECIPE_BYTES) throw ImportException(tr("配方文本超过 64 KB"))
        if (!looksLikeFujiRecipe(text)) throw ImportException(tr("不像富士配方：没有“Film Simulation:”一行"))
        var root: SpkLook? = null
        var highlights = 0.0; var shadows = 0.0; var saturation = 0.0
        var temperature = 0.0; var tint = 0.0; var contrast = 0.0
        var grain: Double? = null; var formatMm: Double? = null
        fun number(v: String) = NUMBER.find(v)?.value?.toDoubleOrNull()
        for (raw in text.lineSequence()) {
            val m = LINE.matchEntire(raw) ?: continue
            val keyText = m.groupValues[1].trim()
            val key = keyText.lowercase(Locale.ROOT).filter(Char::isLetter)
            val value = m.groupValues[2]
            when (key) {
                "filmsimulation" -> {
                    val sim = value.lowercase(Locale.ROOT).filter(Char::isLetter)
                    val (id, note) = fujiBase(sim)
                    if (id == null) { report.ignored("Film Simulation", tr("不认识“{0}”，按中性处理", value.take(30))); continue }
                    val b = base(id)
                    if (b == null) { report.ignored("Film Simulation", tr("本库没有对应的风格")); continue }
                    root = b
                    report.approximated("Film Simulation", tr("以本库「{0}」为底", tr(b.name)))
                    note?.let { report.ignored(tr("黑白滤镜"), it) }
                    if (sim.startsWith("pronegh")) contrast = 6.0
                }
                "highlight", "highlighttone" -> number(value)?.let {
                    highlights += it.coerceIn(-2.0, 4.0) * 10; report.approximated(keyText, tr("→ 高光"))
                }
                "shadow", "shadowtone" -> number(value)?.let {
                    shadows -= it.coerceIn(-2.0, 4.0) * 10; report.approximated(keyText, tr("→ 阴影"))
                }
                "color" -> number(value)?.let {
                    saturation += it.coerceIn(-4.0, 4.0) * 6; report.approximated(keyText, tr("→ 饱和"))
                }
                "whitebalance" -> {
                    val shift = WB_SHIFT.firstNotNullOfOrNull { it.find(value) }
                    if (shift != null) {
                        val r = shift.groupValues[1].toDouble().coerceIn(-9.0, 9.0)
                        val b = shift.groupValues[2].toDouble().coerceIn(-9.0, 9.0)
                        temperature += (r - b) * 3; tint += (r + b) * 1.5
                        report.approximated(tr("White Balance 偏移"), tr("→ 色温、色调"))
                    }
                    report.ignored("White Balance", tr("白平衡本身逐张设置，只取偏移"))
                }
                "dynamicrange" -> when {
                    "400" in value -> { highlights -= 20.0; report.approximated(keyText, tr("→ 高光 −20")) }
                    "200" in value -> { highlights -= 10.0; report.approximated(keyText, tr("→ 高光 −10")) }
                    else -> Unit
                }
                "graineffect" -> {
                    val v = value.lowercase(Locale.ROOT)
                    if (!v.startsWith("off")) {
                        grain = if ("strong" in v) 1.5 else 1.0
                        if ("large" in v) formatMm = 24.0
                        report.approximated(keyText, tr("→ 胶片颗粒{0}", if (formatMm != null) tr("，用半格放大颗粒") else ""))
                    }
                }
                else -> report.ignored(keyText.take(40), FUJI_IGNORED[key] ?: tr("不认识的配方项"))
            }
        }
        val b = root ?: SpkLook(name)
        val l = b.layer2
        fun c(v: Double, lo: Double, hi: Double) = v.coerceIn(lo, hi)
        return SpkLook(
            name = name.trim().ifEmpty { tr("富士配方") }.take(60),
            engine = b.engine.copy(grain = grain ?: b.engine.grain, formatMm = formatMm ?: b.engine.formatMm),
            layer2 = l.copy(
                highlights = c(l.highlights + highlights, -100.0, 100.0),
                shadows = c(l.shadows + shadows, -100.0, 100.0),
                saturation = c(l.saturation + saturation, -100.0, 100.0),
                temperature = c(l.temperature + temperature, -100.0, 100.0),
                tint = c(l.tint + tint, -100.0, 100.0),
                contrast = c(l.contrast + contrast, -50.0, 50.0),
            ),
            source = SpkSource("fuji", "", tr("富士配方（近似）")),
        )
    }

    /** A simulation name (letters only, lower case) to a library style id, and a note when a B&W filter is dropped. */
    private fun fujiBase(sim: String): Pair<String?, String?> {
        val filter = tr("黑白滤镜（压暗天空等）做不了")
        return when {
            sim.startsWith("classicchrome") -> "cam_chrome" to null
            sim.startsWith("classicneg") -> "cam_classic_neg" to null
            sim.startsWith("nostalgic") -> "cam_nostalgic" to null
            sim.startsWith("eternableach") || sim.startsWith("bleachbypass") -> "cam_bleach" to null
            sim.startsWith("eterna") -> "cam_eterna" to null
            sim.startsWith("astia") -> "cam_astia" to null
            sim.startsWith("provia") -> "slide_positive" to null
            sim.startsWith("velvia") -> "slide_velvia" to null
            sim.startsWith("proneg") -> "neg_portrait" to null
            sim.startsWith("reala") -> "neg_natural" to null
            sim.startsWith("acros") -> "bw_silver" to (if (sim.length > 5) filter else null)
            sim.startsWith("monochrome") -> "bw_street" to (if (sim.length > 10) filter else null)
            sim.startsWith("sepia") -> "bw_sepia" to null
            else -> null to null
        }
    }

    // --- shared ---------------------------------------------------------------

    private val STOCK = Regex("[A-Za-z0-9_-]{1,96}")

    internal fun stem(fileName: String) = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim()

    private fun unescape(s: String): String = s.replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                code?.takeIf { it in 0x20..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) } ?: ""
            }
        }
    }

    private fun fmt(d: Double) = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e9) d.toLong().toString() else String.format(Locale.ROOT, "%.2f", d)
}
