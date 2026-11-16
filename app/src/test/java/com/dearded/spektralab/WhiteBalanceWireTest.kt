package com.dearded.spektralab

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cross-language fixtures use the real sample, persistence and native-value paths. */
class WhiteBalanceWireTest {
    @Test fun sampledCorrectionSurvivesPersistenceWithoutChangingTheOtherControls() {
        val rows = mutableListOf<String>()
        var unlimited = 0
        for (strength in listOf(25.0, 50.0, 100.0)) {
            for (red in listOf(23000, 24500, 26000, 27500, 29000)) {
                for (green in listOf(23000, 24500, 26000, 27500, 29000)) {
                    for (blue in listOf(23000, 24500, 26000, 27500, 29000)) {
                        val rgb = floatArrayOf(red / 65535f, green / 65535f, blue / 65535f)
                        val payload = FloatArray(2 + 9 * 9 * 6).also { values ->
                            values[0] = 9f; values[1] = 9f
                            repeat(9 * 9) { i -> repeat(6) { k -> values[2 + i * 6 + k] = rgb[k % 3] } }
                        }
                        val before = Look(temperature = -25.0, tint = 12.0, palette = strength,
                            exposure = .35, contrast = 7.0)
                        val pick = WhiteBalanceReference.fromNative(payload)!!.sample(.5f, .5f, before).pick!!
                        val after = before.copy(temperature = pick.temperature, tint = pick.tint)
                        val restored = Look.fromJson(after.toJson())
                        assertEquals(after, restored)
                        val beforeWire = before.values()
                        val combined = restored.values()
                        assertEquals(beforeWire.size, combined.size)
                        for (i in 2 until combined.size) assertEquals("unchanged native control $i", beforeWire[i], combined[i], 0f)
                        val wb = Look(temperature = pick.temperature, tint = pick.tint, palette = strength).values()
                        val finish = before.copy(temperature = 0.0, tint = 0.0).values()
                        assertEquals(wb[0], combined[0], 0f)
                        assertEquals(wb[1], combined[1], 0f)
                        if (!pick.limited) unlimited++
                        rows += buildString {
                            append(rgb.joinToString(" ")); append(if (pick.limited) " 1" else " 0")
                            for (wire in listOf(wb, finish, combined)) {
                                append(' '); append(wire.size); append(' '); append(wire.joinToString(" "))
                            }
                        }
                    }
                }
            }
        }
        assertEquals(375, rows.size)
        assertTrue("fixtures must exercise both ordinary and limited corrections", unlimited in 1 until rows.size)
        System.getenv("SPK_WB_EVIDENCE_FILE")?.let { path ->
            File(path).writeText("SPK_WB_WIRE_V1 ${rows.size}\n" + rows.joinToString("\n", postfix = "\n"))
        }
    }
}
