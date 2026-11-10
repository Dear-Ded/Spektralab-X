package com.dearded.spektralab

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * ICC v2 配置文件生成器，用于 TIFF 导出的色彩空间标记。
 * 生成符合 ICC.1:2001-12 规范的最小可用配置文件。
 */
object IccProfile {
    /**
     * 生成 ProPhoto RGB (ROMM RGB) ICC v2 配置文件。
     *
     * 色彩空间参数：
     * - 色度坐标（xy）：R[0.7347, 0.2653] G[0.1596, 0.8404] B[0.0366, 0.0001]
     * - 白点：D50 [0.9642, 1.0, 0.8249]
     * - TRC：Gamma 1.8（ProPhoto 标准）
     *
     * 参考：ISO 22028-2 (ROMM RGB)
     */
    fun generateProPhotoRgb(): ByteArray {
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN)

        // Header (128 bytes)
        buffer.putInt(1024)  // Profile size
        buffer.putInt(0)     // Preferred CMM type (none)
        buffer.put(byteArrayOf(2, 0x10, 0, 0))  // Version 2.1.0
        buffer.putInt(0x73_63_6e_72)  // 'scnr' = scanner (input profile)
        buffer.putInt(0x52_47_42_20)  // 'RGB '
        buffer.putInt(0x58_59_5a_20)  // 'XYZ ' PCS

        // Date/Time (12 bytes) - placeholder
        buffer.position(buffer.position() + 12)

        buffer.putInt(0x61_63_73_70)  // 'acsp' signature
        buffer.putInt(0)     // Platform (generic)
        buffer.putInt(0)     // Flags
        buffer.putInt(0)     // Device manufacturer
        buffer.putInt(0)     // Device model
        buffer.putLong(0)    // Device attributes
        buffer.putInt(0)     // Rendering intent (perceptual)

        // PCS illuminant D50
        buffer.putInt(0x0000_f6d6)  // X = 0.9642
        buffer.putInt(0x0001_0000)  // Y = 1.0
        buffer.putInt(0x0000_d32d)  // Z = 0.8249

        buffer.putInt(0)     // Creator signature
        buffer.position(128)  // End of header

        // Tag Table
        val tagCount = 9
        buffer.putInt(tagCount)

        var offset = 128 + 4 + tagCount * 12  // After tag table

        // Required tags
        writeTagEntry(buffer, "desc", offset, 90); offset += 90
        writeTagEntry(buffer, "cprt", offset, 80); offset += 80
        writeTagEntry(buffer, "wtpt", offset, 20); offset += 20
        writeTagEntry(buffer, "rXYZ", offset, 20); offset += 20
        writeTagEntry(buffer, "gXYZ", offset, 20); offset += 20
        writeTagEntry(buffer, "bXYZ", offset, 20); offset += 20
        writeTagEntry(buffer, "rTRC", offset, 14); offset += 14
        writeTagEntry(buffer, "gTRC", offset, 14); offset += 14
        writeTagEntry(buffer, "bTRC", offset, 14)

        // Tag data
        buffer.position(128 + 4 + tagCount * 12)

        // 1. Description
        writeTextDesc(buffer, "ProPhoto RGB")

        // 2. Copyright
        writeTextType(buffer, "Public Domain")

        // 3. White point D50
        writeXYZ(buffer, 0.9642, 1.0, 0.8249)

        // 4-6. Primaries in XYZ (calculated from xy chromaticities)
        // R: xy[0.7347, 0.2653] -> XYZ[1.3459, 0.7019, 0.0003]
        writeXYZ(buffer, 1.3459, 0.7019, 0.0003)
        // G: xy[0.1596, 0.8404] -> XYZ[0.1689, 0.9505, 0.0062]
        writeXYZ(buffer, 0.1689, 0.9505, 0.0062)
        // B: xy[0.0366, 0.0001] -> XYZ[0.0366, 0.0002, 0.2115]
        writeXYZ(buffer, 0.0366, 0.0002, 0.2115)

        // 7-9. TRC (Tone Reproduction Curve) - gamma 1.8
        writeGammaCurve(buffer, 1.8)
        writeGammaCurve(buffer, 1.8)
        writeGammaCurve(buffer, 1.8)

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    private fun writeTagEntry(buffer: ByteBuffer, tagName: String, offset: Int, size: Int) {
        buffer.putInt(tagName.toInt())
        buffer.putInt(offset)
        buffer.putInt(size)
    }

    private fun String.toInt(): Int {
        require(length == 4) { "Tag must be 4 chars" }
        return (this[0].code shl 24) or (this[1].code shl 16) or (this[2].code shl 8) or this[3].code
    }

    private fun writeTextDesc(buffer: ByteBuffer, text: String) {
        buffer.putInt("desc".toInt())  // Type signature
        buffer.putInt(0)  // Reserved
        buffer.putInt(text.length + 1)  // ASCII count
        buffer.put(text.toByteArray(Charsets.US_ASCII))
        buffer.put(0)  // Null terminator
        // Pad to align
        while (buffer.position() % 4 != 0) buffer.put(0)
        // Unicode and ScriptCode counts (both 0)
        buffer.putInt(0)
        buffer.putInt(0)
    }

    private fun writeTextType(buffer: ByteBuffer, text: String) {
        buffer.putInt("text".toInt())
        buffer.putInt(0)  // Reserved
        buffer.put(text.toByteArray(Charsets.US_ASCII))
        buffer.put(0)
        while (buffer.position() % 4 != 0) buffer.put(0)
    }

    private fun writeXYZ(buffer: ByteBuffer, x: Double, y: Double, z: Double) {
        buffer.putInt("XYZ ".toInt())
        buffer.putInt(0)  // Reserved
        buffer.putInt((x * 65536).toInt())
        buffer.putInt((y * 65536).toInt())
        buffer.putInt((z * 65536).toInt())
    }

    private fun writeGammaCurve(buffer: ByteBuffer, gamma: Double) {
        buffer.putInt("curv".toInt())
        buffer.putInt(0)  // Reserved
        buffer.putInt(1)  // Count = 1 means gamma curve
        buffer.putShort((gamma * 256).toInt().toShort())
    }
}
