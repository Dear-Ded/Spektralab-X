package com.dearded.spektralab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurveWireTest {
    private val dense = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.3),
        CurvePoint(Math.nextUp(0.5), 0.8), CurvePoint(1.0, 1.0)))

    private fun legacy(curves: CurveSet): FloatArray = curves.rows.flatMap { curve ->
        listOf(curve.points.size.toFloat()) + curve.points.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }
    }.toFloatArray()

    private fun assertBits(expected: FloatArray, actual: FloatArray) {
        assertArrayEquals(expected.map(Float::toRawBits).toIntArray(), actual.map(Float::toRawBits).toIntArray())
    }

    @Test fun ordinaryRowsKeepTheExistingWireBytes() {
        val ordinary = CurveSet(rgb = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.3),
            CurvePoint(0.50001, 0.8), CurvePoint(1.0, 1.0))))
        assertBits(legacy(ordinary), ordinary.tail())
        assertEquals(0, CurveSet.IDENTITY.tail().size)
    }

    @Test fun everyCollidingRowUsesTheFinal256SampleTable() {
        assertEquals(dense.points[1].x.toFloat(), dense.points[2].x.toFloat())
        for (row in 0 until 5) {
            val curves = CurveSet.IDENTITY.with(row, dense)
            val actual = curves.tail()
            var at = 0
            curves.rows.forEachIndexed { i, curve ->
                if (i == row) {
                    assertEquals(-256f, actual[at++])
                    assertBits(FloatArray(256) { dense.evaluate(it / 255.0).toFloat() },
                        actual.copyOfRange(at, at + 256))
                    at += 256
                } else {
                    val expected = legacy(CurveSet(rgb = curve)).copyOfRange(0, 1 + 2 * curve.points.size)
                    assertBits(expected, actual.copyOfRange(at, at + expected.size))
                    at += expected.size
                }
            }
            assertEquals(actual.size, at)
        }
    }

    @Test fun adjacentDoublePointsRemainEditableAfterJsonRoundTrip() {
        val original = CurveSet(rgb = dense)
        val restored = CurveSet.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, restored)
        assertTrue(restored.rgb.points[1].x < restored.rgb.points[2].x)
        assertBits(original.tail(), restored.tail())
    }

    @Test fun actualLookWireKeepsMasksAfterTheSampledRows() {
        val curves = CurveSet(rgb = dense, blue = dense)
        val mask = EditMask(tone = MaskTone(exposure = 1.0), ax = 0.0, ay = 0.5, bx = 1.0, by = 0.5)
        val tail = LocalEdits.tail(curves, listOf(mask), Dust())!!
        val rows = curves.tail()
        assertBits(rows, tail.copyOfRange(0, rows.size))
        assertEquals(9157f, tail[rows.size])
        assertEquals(1f, tail[rows.size + 1])
        assertEquals(1f, tail[rows.size + 8])
        assertEquals(1f, tail[rows.size + 12]) // First mask's exposure.
        val values = Look(curves = curves, masks = listOf(mask)).values()
        assertBits(tail, values.copyOfRange(50, values.size))
    }

    @Test fun localOrdinaryRowsAndDustKeepTheirOffsets() {
        val curves = CurveSet(red = Curve(listOf(CurvePoint(0.0, 0.2), CurvePoint(1.0, 1.0))))
        val rows = legacy(curves)
        val tail = LocalEdits.tail(curves, emptyList(), Dust(strength = 50.0))!!
        assertBits(rows, tail.copyOfRange(0, rows.size))
        assertEquals(9157f, tail[rows.size])
        assertEquals(50f, tail[rows.size + 2])
        assertEquals(0f, tail[rows.size + 8])
    }
}
