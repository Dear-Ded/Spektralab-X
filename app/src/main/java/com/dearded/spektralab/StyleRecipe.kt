package com.dearded.spektralab

/** Reusable global effects, co-created with GPT (OpenAI). Geometry and RAW development belong to the photo. */
enum class StyleScope(val key: String, val label: String) {
    COMBINATION("combination", "完整组合"), FILTER("filter", "仅滤镜"), FILM("film", "仅胶卷");

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

/** A shared snapshot for the local library and .spklook export. No per-photo masks or RAW white balance. */
internal fun Edit.savedStyle(name: String, scope: StyleScope = StyleScope.COMBINATION,
                             lutSpace: String = SpkLut.SPACE_SRGB): SpkLook {
    require(scope != StyleScope.FILM || !noFilm) { "请先选择胶卷，再保存胶卷效果" }
    val r = look.resolved()
    return SpkLook(
        name = name.trim().take(60).ifEmpty { "我的风格" }, scope = scope,
        engine = if (scope == StyleScope.FILTER) SpkEngine() else SpkEngine(
            film = film.takeUnless { noFilm }, paper = paper.takeUnless { noFilm },
            exposureEv = exposureEv, sceneCurve = sceneCurve, printExposure = printExposure,
            yCc = yShift * Edit.FILTER_SHIFT_CC, mCc = mShift * Edit.FILTER_SHIFT_CC,
            formatMm = filmFormatMm,
            grain = grainAmount, halation = halationAmount, glare = glareAmount,
            grainActive = grainActive, halationActive = halationActive, glareActive = glareActive,
            noFilm = noFilm, scanFilm = scanFilm, digitalIntermediate = digitalIntermediate,
            autoExposure = autoExposure, aeMethod = aeMethod,
            scatter = scatterAmount, couplers = couplersAmount,
            grainLayered = grainLayered, grainArea = grainArea,
            unsharpSigma = unsharpSigma, unsharpAmount = unsharpAmount,
            preflash = preflash, negativeGamma = negativeGamma, highlightBoostEv = highlightBoostEv,
            lensBlurUm = lensBlurUm, noAntihalation = noAntihalation, cameraFilter = cameraFilter, labLevels = labLevels,
        ),
        layer2 = if (scope == StyleScope.FILM) Look.NEUTRAL else r.copy(lut = "", lutStrength = 1.0, masks = emptyList()),
        lut = if (scope != StyleScope.FILM && r.lut.isNotEmpty()) SpkLut(r.lutStrength, lutSpace) else null,
        source = SpkSource("spektralab-x", "", "Spektralab-X 自定义效果"),
    )
}

/** Optional v2 fields overlay the legacy style defaults; absent fields keep the old application behaviour. */
internal fun SpkEngine.applyOptions(edit: Edit) = edit.copy(
    noFilm = noFilm ?: edit.noFilm,
    scanFilm = scanFilm ?: edit.scanFilm,
    digitalIntermediate = digitalIntermediate ?: edit.digitalIntermediate,
    autoExposure = autoExposure ?: edit.autoExposure, aeMethod = aeMethod ?: edit.aeMethod,
    scatterAmount = scatter ?: edit.scatterAmount, couplersAmount = couplers ?: edit.couplersAmount,
    grainLayered = grainLayered ?: edit.grainLayered, grainArea = grainArea ?: edit.grainArea,
    unsharpSigma = unsharpSigma ?: edit.unsharpSigma, unsharpAmount = unsharpAmount ?: edit.unsharpAmount,
    preflash = preflash ?: edit.preflash, negativeGamma = negativeGamma ?: edit.negativeGamma,
    highlightBoostEv = highlightBoostEv ?: edit.highlightBoostEv, lensBlurUm = lensBlurUm ?: edit.lensBlurUm,
    noAntihalation = noAntihalation ?: edit.noAntihalation, cameraFilter = cameraFilter ?: edit.cameraFilter,
    labLevels = labLevels ?: edit.labLevels,
    grainActive = grainActive ?: edit.grainActive,
    halationActive = halationActive ?: edit.halationActive,
    glareActive = glareActive ?: edit.glareActive,
)
