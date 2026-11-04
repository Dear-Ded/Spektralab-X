package com.dearded.spektralab

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Filling a RAW's shooting data from a same-shot JPEG only ever fills gaps. */
class ExifFillTest {
    private val raw = mapOf(
        ExifInterface.TAG_MAKE to "Xiaomi",
        ExifInterface.TAG_MODEL to "Mi 9 Lite",
        ExifInterface.TAG_F_NUMBER to "179/100",
        ExifInterface.TAG_FOCAL_LENGTH to "473/100",
        ExifInterface.TAG_LENS_MODEL to " ",
    )
    private val jpeg = mapOf(
        ExifInterface.TAG_MAKE to "Other",
        ExifInterface.TAG_F_NUMBER to "18/10",
        ExifInterface.TAG_LENS_MODEL to "Mi 9 Lite back camera 4.73mm f/1.79",
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "26",
        "UnknownTag" to "x",
    )

    @Test fun fillsOnlyGaps() {
        val out = ExifCopy.fillMissing(raw, jpeg)
        assertEquals("Xiaomi", out[ExifInterface.TAG_MAKE])
        assertEquals("179/100", out[ExifInterface.TAG_F_NUMBER])
        assertEquals("Mi 9 Lite back camera 4.73mm f/1.79", out[ExifInterface.TAG_LENS_MODEL])
        assertEquals("26", out[ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM])
        assertNull(out["UnknownTag"])
    }

    @Test fun nothingToAdd() {
        assertEquals(raw, ExifCopy.fillMissing(raw, emptyMap()))
    }

    @Test fun secondsApart() {
        assertEquals(2L, ExifCopy.secondsApart("2020:03:24 07:36:11", "2020:03:24 07:36:13"))
        assertEquals(86400L, ExifCopy.secondsApart("2020:03:24 07:36:11", "2020:03:25 07:36:11"))
        assertNull(ExifCopy.secondsApart(null, "2020:03:24 07:36:11"))
        assertNull(ExifCopy.secondsApart("2020:13:40 07:36:11", "2020:03:24 07:36:11"))
        assertNull(ExifCopy.secondsApart("    :  :     :  :  ", "2020:03:24 07:36:11"))
    }
}
