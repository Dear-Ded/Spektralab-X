package com.dearded.spektralab

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Matrix/TRC profiles for the encoded TIFF pixels emitted by the engine. */
object IccProfile {
    /**
     * ICC v4 matrix-shaper, using engine/host/image_io.cpp's D50 colorants.
     * Parametric curve type 3 preserves ROMM's linear toe: E/16 below 1/32,
     * E^1.8 otherwise. A gamma-only tag does not describe those output codes.
     */
    fun generateProPhotoRgb(): ByteArray {
        val curve = buffer(32).apply {
            signature("para"); putInt(0); putShort(3); putShort(0)
            listOf(1.8, 1.0, 0.0, 1.0 / 16, 1.0 / 32).forEach { fixed(it) }
        }.array()
        val tags = listOf(
            "desc" to localizedText("ProPhoto RGB (ROMM)"),
            "cprt" to localizedText("Public Domain"),
            "wtpt" to xyz(.9642, 1.0, .8249),
            "rXYZ" to xyz(.7976749, .2880402, 0.0),
            "gXYZ" to xyz(.1351917, .7118741, 0.0),
            "bXYZ" to xyz(.0313534, .0000857, .8252100),
            "rTRC" to curve,
            "gTRC" to curve,
            "bTRC" to curve,
        )
        return matrixProfile(tags)
    }

    /** Adobe RGB (1998), relative D50 PCS, zero black and exact 563/256 gamma. */
    fun generateAdobeRgb(): ByteArray {
        val curve = buffer(16).apply {
            signature("para"); putInt(0); putShort(0); putShort(0)
            fixed(563.0 / 256.0)
        }.array()
        val adaptation = buffer(44).apply {
            signature("sf32"); putInt(0)
            // Bradford D65 -> ICC D50. Colorants below are already adapted.
            listOf(1.0478112, .0228866, -.0501270,
                .0295424, .9904844, -.0170491,
                -.0092345, .0150436, .7521316).forEach { fixed(it) }
        }.array()
        return matrixProfile(listOf(
            "desc" to localizedText("Adobe RGB (1998)"),
            "cprt" to localizedText("Public Domain"),
            "wtpt" to xyz(.9642, 1.0, .8249),
            "chad" to adaptation,
            // Adobe RGB (1998) encoding specification, section 4.3.7.3.
            // Each tag stores one column of the D50 PCS matrix.
            "rXYZ" to xyz(.60974, .31111, .01947),
            "gXYZ" to xyz(.20528, .62567, .06087),
            "bXYZ" to xyz(.14919, .06322, .74457),
            "rTRC" to curve,
            "gTRC" to curve,
            "bTRC" to curve,
        ))
    }

    private fun matrixProfile(tags: List<Pair<String, ByteArray>>): ByteArray {
        val tableEnd = 132 + tags.size * 12
        val size = tableEnd + tags.sumOf { aligned(it.second.size) }
        val profile = buffer(size)
        profile.putInt(size)
        profile.putInt(0)
        profile.putInt(0x04300000) // ICC v4.3
        profile.signature("mntr")
        profile.signature("RGB ")
        profile.signature("XYZ ")
        // A deterministic, valid creation timestamp; no locale-dependent fields.
        listOf(2000, 1, 1, 0, 0, 0).forEach { profile.putShort(it.toShort()) }
        profile.signature("acsp")
        profile.position(64)
        profile.putInt(1) // Relative colorimetric; gamut mapping precedes encoding.
        profile.fixed(.9642); profile.fixed(1.0); profile.fixed(.8249)
        profile.position(128)
        profile.putInt(tags.size)
        var offset = tableEnd
        tags.forEach { (name, data) ->
            profile.signature(name)
            profile.putInt(offset)
            profile.putInt(data.size)
            offset += aligned(data.size)
        }
        tags.forEach { (_, data) ->
            profile.put(data)
            profile.position(aligned(profile.position()))
        }
        check(profile.position() == size)
        return profile.array()
    }

    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    private fun aligned(size: Int) = (size + 3) and -4

    private fun ByteBuffer.signature(value: String) {
        require(value.length == 4)
        put(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteBuffer.fixed(value: Double) { putInt((value * 65536).roundToInt()) }

    private fun xyz(x: Double, y: Double, z: Double) = buffer(20).apply {
        signature("XYZ "); putInt(0)
        fixed(x); fixed(y); fixed(z)
    }.array()

    private fun localizedText(text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_16BE)
        return buffer(28 + data.size).apply {
            signature("mluc"); putInt(0)
            putInt(1); putInt(12)
            signature("enUS")
            putInt(data.size); putInt(28)
            put(data)
        }.array()
    }
}
