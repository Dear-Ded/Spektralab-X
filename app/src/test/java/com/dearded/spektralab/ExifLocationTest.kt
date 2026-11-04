package com.dearded.spektralab

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ExifLocation is plain Kotlin: what an export may carry, and what it must not. */
class ExifLocationTest {
    private val shanghai = mapOf(
        ExifInterface.TAG_GPS_LATITUDE_REF to "N",
        ExifInterface.TAG_GPS_LATITUDE to "31/1,13/1,4380/100",
        ExifInterface.TAG_GPS_LONGITUDE_REF to "E",
        ExifInterface.TAG_GPS_LONGITUDE to "121/1,28/1,1200/100",
        ExifInterface.TAG_GPS_ALTITUDE_REF to "0",
        ExifInterface.TAG_GPS_ALTITUDE to "12/1",
    )

    @Test fun degreesFromRationals() {
        assertEquals(31.2288333, ExifLocation.degrees("31/1,13/1,4380/100")!!, 1e-6)
        assertEquals(121.0, ExifLocation.degrees("121,0,0")!!, 1e-9)
    }

    @Test fun degreesRejectsMalformed() {
        assertNull(ExifLocation.degrees("31/1,13/1"))
        assertNull(ExifLocation.degrees("31/0,13/1,0/1"))
        assertNull(ExifLocation.degrees("a,b,c"))
        assertNull(ExifLocation.degrees("-1/1,0/1,0/1"))
        assertNull(ExifLocation.degrees("1/2/3,0/1,0/1"))
    }

    @Test fun keepsARealLocation() {
        assertEquals(shanghai, ExifLocation.keep(shanghai))
    }

    @Test fun dropsEverythingButLocationTags() {
        val kept = ExifLocation.keep(shanghai + (ExifInterface.TAG_MAKE to "Xiaomi"))
        assertEquals(shanghai, kept)
    }

    @Test fun redactedZerosGiveNothing() {
        // What MediaStore hands an app without ACCESS_MEDIA_LOCATION.
        val redacted = shanghai + mapOf(
            ExifInterface.TAG_GPS_LATITUDE to "0/1,0/1,0/1",
            ExifInterface.TAG_GPS_LONGITUDE to "0/1,0/1,0/1",
        )
        assertTrue(ExifLocation.keep(redacted).isEmpty())
    }

    @Test fun missingRefOrCoordinateGivesNothing() {
        assertTrue(ExifLocation.keep(shanghai - ExifInterface.TAG_GPS_LATITUDE_REF).isEmpty())
        assertTrue(ExifLocation.keep(shanghai - ExifInterface.TAG_GPS_LONGITUDE).isEmpty())
        assertTrue(ExifLocation.keep(emptyMap()).isEmpty())
    }

    @Test fun outOfRangeGivesNothing() {
        assertTrue(ExifLocation.keep(shanghai + (ExifInterface.TAG_GPS_LATITUDE to "91/1,0/1,0/1")).isEmpty())
        assertTrue(ExifLocation.keep(shanghai + (ExifInterface.TAG_GPS_LONGITUDE to "181/1,0/1,0/1")).isEmpty())
    }

    @Test fun encodeDecodeRoundTrip() {
        assertEquals(shanghai, ExifLocation.decode(ExifLocation.encode(shanghai)))
        assertTrue(ExifLocation.decode("").isEmpty())
        assertTrue(ExifLocation.decode("garbage\n=x").isEmpty())
    }
}
