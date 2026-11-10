package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

/** Co-created with GPT (OpenAI). Fixed stock choices must reach the physical print path. */
class NewPhotoStocksTest {
    private val negative = Stock("kodak_portra_400", "Portra", false, false, "kodak_supra_endura")
    private val slide = Stock("fujifilm_velvia_100", "Velvia", true, false, null)
    private val mono = Stock("ilford_hp5_plus_400", "HP5", false, true, "ilford_multigrade_iv_rc")
    private val colorPaper = Stock("kodak_supra_endura", "Supra", false, false, null)
    private val otherPaper = Stock("kodak_portra_endura", "Portra paper", false, false, null)
    private val monoPaper = Stock("ilford_multigrade_iv_rc", "Multigrade", false, true, null)
    private val catalog = Catalog(listOf(negative, slide, mono), listOf(colorPaper, otherPaper, monoPaper))

    @Test fun fixedNegativeAndPaperPrintFromANewNeutralPhoto() {
        val input = Edit.NONE.copy(cropW = .75, rawExposureEv = -1.0, look = Look(exposure = .5))
        val actual = fixed(input, negative, otherPaper)
        assertPrinted(actual, negative, otherPaper)
        assertEquals(catalog.normalize(input.copy(film = negative.id, paper = otherPaper.id, noFilm = false,
            scanFilm = false, digitalIntermediate = false)), actual)
        assertEquals(RawDevelopmentMode.NEUTRAL, actual.rawDevelopment)
    }

    @Test fun fixedSlideWithPaperClearsDirectScan() {
        val actual = fixed(Edit.NONE.copy(scanFilm = true), slide, colorPaper)
        assertPrinted(actual, slide, colorPaper)
    }

    @Test fun fixedPairClearsAPreviousIntermediateOrScan() {
        for (input in listOf(Edit(digitalIntermediate = true), Edit(scanFilm = true))) {
            assertPrinted(fixed(input, negative, colorPaper), negative, colorPaper)
        }
    }

    @Test fun incompatibleOrMissingPaperUsesTheFilmsPrintableFallback() {
        val actual = fixed(Edit.NONE, mono, colorPaper)
        assertPrinted(actual, mono, monoPaper)
        assertTrue(actual.monoPrint)
        assertPrinted(EditorViewModel.withFixedStocks(Edit.NONE, catalog, negative.id, "removed")!!,
            negative, colorPaper)
    }

    @Test fun removedFilmDoesNotInventAReplacement() {
        assertNull(EditorViewModel.withFixedStocks(Edit.NONE, catalog, "removed", colorPaper.id))
    }

    @Test fun slideWithoutAPrintableFallbackKeepsItsSafeDirectScan() {
        val input = Edit(film = mono.id, paper = monoPaper.id, monoPrint = true)
        for (paperId in listOf(monoPaper.id, "removed")) {
            val actual = EditorViewModel.withFixedStocks(input, catalog, slide.id, paperId)!!
            assertEquals(slide.id, actual.film)
            assertTrue(actual.scanFilm)
            assertFalse(actual.digitalIntermediate)
            assertFalse(actual.noFilm)
            assertFalse(actual.monoPrint)
        }
    }

    @Test fun ordinaryFilmSelectionStillRetainsTheChosenPaperNoneIntent() {
        val negativeOnly = EditorViewModel.withFilm(Edit.NONE, catalog, negative.id)!!
        assertTrue(negativeOnly.digitalIntermediate)
        assertFalse(negativeOnly.scanFilm)
        val slideOnly = EditorViewModel.withFilm(Edit.NONE, catalog, slide.id)!!
        assertTrue(slideOnly.scanFilm)
        assertFalse(slideOnly.digitalIntermediate)
        assertTrue(Edit.NONE.noFilm)
        assertEquals(RawDevelopmentMode.NEUTRAL, Edit.NONE.rawDevelopment)
    }

    @Test fun rememberedSlideRestoresPrintingAndDirectScanSeparately() {
        val printed = EditorViewModel.withRememberedStocks(Edit.NONE, catalog, slide.id,
            colorPaper.id, noFilm = false, di = false, scan = false)!!
        assertPrinted(printed, slide, colorPaper)
        val scanned = EditorViewModel.withRememberedStocks(Edit.NONE, catalog, slide.id,
            colorPaper.id, noFilm = false, di = false, scan = true)!!
        assertTrue(scanned.scanFilm)
        assertTrue(scanned.paperNone(positive = true))
        val legacy = EditorViewModel.withRememberedStocks(Edit.NONE, catalog, slide.id,
            colorPaper.id, noFilm = false, di = false, scan = null)!!
        assertTrue(legacy.scanFilm)
    }

    @Test fun rememberedNegativeRestoresIntermediateAndNoFilmChoices() {
        val intermediate = EditorViewModel.withRememberedStocks(Edit.NONE, catalog, negative.id,
            colorPaper.id, noFilm = false, di = true, scan = false)!!
        assertFalse(intermediate.noFilm)
        assertTrue(intermediate.paperNone(positive = false))
        val neutral = EditorViewModel.withRememberedStocks(Edit(), catalog, negative.id,
            colorPaper.id, noFilm = true, di = false, scan = false)!!
        assertTrue(neutral.noFilm)
        assertEquals(RawDevelopmentMode.NEUTRAL, neutral.rawDevelopment)
    }

    private fun fixed(input: Edit, film: Stock, paper: Stock) =
        EditorViewModel.withFixedStocks(input, catalog, film.id, paper.id)!!

    private fun assertPrinted(actual: Edit, film: Stock, paper: Stock) {
        assertEquals(film.id, actual.film)
        assertEquals(paper.id, actual.paper)
        assertFalse(actual.noFilm)
        assertFalse(actual.scanFilm)
        assertFalse(actual.digitalIntermediate)
        val wire = actual.toJson()
        assertEquals(paper.id, wire.getString("print_stock"))
        assertFalse(wire.getBoolean("x_no_film"))
        assertFalse(wire.getBoolean("scan_film"))
        assertFalse(wire.getBoolean("digital_intermediate"))
    }
}
