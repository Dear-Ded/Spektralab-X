package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

/** Complete candidate identities, co-created with GPT (OpenAI). */
class SelectionPreviewRecipeTest {
    private val catalog = Catalog(listOf(Stock("kodak_portra_400", "Portra", false, false, "kodak_supra_endura")),
        listOf(Stock("kodak_supra_endura", "Supra", false, false, null)))

    @Test fun sameFilmDifferentFinishAndStrengthHaveDifferentCacheKeys() {
        val a = Style.ALL.first { it.id == "cam_skin" }
        val b = Style.ALL.first { it.id == "lab_gold" }
        assertEquals(a.film, b.film)
        val base = Edit.NONE.copy(quarterTurns = 1, cropX = .2, cropW = .6)
        fun key(style: Style) = SelectionPreviewKey(1, style.applyTo(base, catalog)!!.forSelectionPreview(), "sRGB")
        assertNotEquals(key(a), key(b))
        assertNotEquals(key(a), key(a.atStrength(.4)))
        assertEquals(1, key(a).edit.quarterTurns)
        assertEquals(.2, key(a).edit.cropX, 0.0)
        assertEquals(a.look.masks, key(a).edit.look.masks)
    }

    @Test fun keyTracksLutMasksPaperSourceDecodeAndOutputSpace() {
        val edit = Edit(look = Look(lut = "lut_test", lutStrength = .7))
        val key = SelectionPreviewKey(7, edit, "Display P3")
        assertNotEquals(key, key.copy(source = 8))
        assertNotEquals(key, key.copy(output = "sRGB"))
        assertNotEquals(key, key.copy(edit = edit.copy(paper = "other")))
        assertNotEquals(key, key.copy(edit = edit.copy(look = edit.look.copy(lutStrength = .4))))
        assertNotEquals(key, key.copy(edit = edit.copy(look = edit.look.copy(
            masks = listOf(EditMask(tone = MaskTone(exposure = .5)))))))
    }

    @Test fun cardsKeepThePhotographicRecipeAndExcludeCanvasDecorations() {
        val base = Edit(filmEdge = true, dateImprint = true, frame = FrameSpec(top = .1f), borderId = "gallery",
            digitalIntermediate = true, cropY = .2, cropH = .6, rawExposureEv = 1.0,
            look = Look(contrast = 12.0, lut = "lut_test"))
        val card = base.forSelectionPreview()
        assertEquals(base.copy(filmEdge = false, dateImprint = false, frame = FrameSpec(), borderId = "none"), card)
        assertEquals(card, card.forSelectionPreview())
        assertEquals(base.look, card.look)
        assertTrue(card.digitalIntermediate)
    }
}
