package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual projection/persistence/native-array fixtures; native runner consumes the output separately. */
class AdvisorWireTest {
    @Test fun multiRoundDraftPreservesTheFullLookThroughTheNativeWire() {
        val rows = mutableListOf<String>()
        for (palette in listOf(25.0, 60.0, 100.0)) {
            val base = Catalog.EMPTY.normalize(Edit.NONE.copy(look = Look(palette = palette, tone = 15.0,
                colour = -10.0, contrast = 4.0, fade = 3.0, master = Zone(hue = 18.0, sat = 2.0),
                curves = CurveSet(rgb = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(.5, .55), CurvePoint(1.0, 1.0)))))))
            val edits = mutableListOf(base)
            val ticket = RequestTicket(1, 1, 0, 1, 1, 0)
            for ((id, value) in listOf("layer2.exposure" to .4, "layer2.saturation" to -12.0, "layer2.temperature" to 12.0)) {
                val snapshot = EditSnapshot(edits.last(), ticket)
                val candidate = AdviceProjection.candidate(snapshot, listOf(ParameterChange(id, value)),
                    AdviceConstraints.all(), Catalog.EMPTY, ticket)
                val restored = Look.fromJson(candidate.edit.look.toJson())
                assertEquals(candidate.edit.look, restored)
                assertEquals(base.look.curves, restored.curves)
                assertEquals(base.look.master, restored.master)
                assertEquals(base.look.palette, restored.palette, 0.0)
                edits += candidate.edit.copy(look = restored)
            }
            assertEquals(.4, edits.last().look.exposure, 0.0)
            rows += edits.joinToString(" ") { edit -> edit.look.values().let { "${it.size} ${it.joinToString(" ")}" } }
        }
        System.getenv("SPK_ADVISOR_WIRE_FILE")?.let {
            File(it).writeText("SPK_ADVISOR_WIRE_V1 ${rows.size}\n" + rows.joinToString("\n", postfix = "\n"))
        }
    }
}
