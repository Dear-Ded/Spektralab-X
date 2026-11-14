package com.dearded.spektralab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Imported colour tables, in filesDir/user/luts (docs/STYLES.md 7.2). Each id
 * keeps the baked Layer 2 table and the original .cube in one atomic `<id>.lut`
 * record. Legacy SPKL/SPK2 caches still read their separate `<id>.cube` files.
 * Exporting a style carries the original rather than the baked table.
 * Plain Kotlin.
 */
class LutStore(private val dir: File) {
    private var cached: Pair<String, Lut3>? = null

    fun newId(): String = "lut_" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
        "_" + (1000..9999).random()

    /** Stores [raw] (made in [space], SpkLut.SPACE_*) under [id]; returns the baked table. */
    fun save(id: String, raw: Lut3, space: String): Lut3 {
        require(ID.matches(id)) { "bad lut id $id" }
        val baked = LutBake.forLayer2(raw, space)
        val original = raw.toCube(id)
        require(original.size.toLong() <= MAX_ORIGINAL_BYTES) { "original LUT is too large" }
        dir.mkdirs()
        val buf = ByteBuffer.allocate(HEADER + 4 + baked.data.size * 4 + original.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC).putInt(baked.size).putInt(if (space == SpkLut.SPACE_PROPHOTO) 1 else 0)
        buf.putInt(original.size)
        for (x in baked.data) buf.putFloat(x)
        buf.put(original)
        writeAtomicBytes(File(dir, "$id.lut"), buf.array())
        synchronized(this) { cached = id to baked }
        return baked
    }

    /** The baked table, or null when [id] is unknown or its file is damaged. Keeps the last one in memory. */
    fun load(id: String): Lut3? {
        if (!ID.matches(id)) return null
        synchronized(this) { cached?.let { if (it.first == id) return it.second } }
        val buf = record(id) ?: return null
        // Stored tables are finished pixel transforms. Keep legacy looks
        // unchanged; only a fresh import uses the revised baking algorithm.
        val magic = buf.getInt()
        val side = buf.getInt()
        buf.getInt()
        if (magic == MAGIC) buf.getInt()
        val data = FloatArray(side * side * side * 3) { buf.getFloat() }
        val lut = runCatching { Lut3(side, data) }.getOrNull() ?: return null
        synchronized(this) { cached = id to lut }
        return lut
    }

    /** The table as imported, as .cube text, and the space it was made for; null when unknown. */
    fun original(id: String): Pair<ByteArray, String>? {
        if (!ID.matches(id)) return null
        val buf = record(id) ?: return null
        val magic = buf.getInt()
        val side = buf.getInt()
        val space = if (buf.getInt() == 1) SpkLut.SPACE_PROPHOTO else SpkLut.SPACE_SRGB
        if (magic == MAGIC) {
            val size = buf.getInt()
            buf.position(buf.position() + side * side * side * 3 * 4)
            return ByteArray(size).also { buf.get(it) } to space
        }
        val cube = File(dir, "$id.cube").takeIf { it.isFile } ?: return null
        if (cube.length() > MAX_ORIGINAL_BYTES) return null
        return cube.readBytes() to space
    }

    private fun record(id: String): ByteBuffer? {
        val file = File(dir, "$id.lut")
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < HEADER) return null
        val magic = buf.getInt()
        if (magic != MAGIC && magic != BAKED_MAGIC && magic != LEGACY_MAGIC) return null
        val side = buf.getInt()
        val space = buf.getInt()
        if (side !in 2..Lut3.NATIVE_SIDE || space !in 0..1) return null
        val pixels = side * side * side * 3 * 4
        if (magic == MAGIC) {
            if (buf.remaining() < 4) return null
            val original = buf.getInt()
            if (original <= 0 || original > MAX_ORIGINAL_BYTES || buf.remaining() != pixels + original) return null
        } else if (buf.remaining() != pixels) return null
        buf.rewind()
        return buf
    }

    fun delete(id: String) {
        if (!ID.matches(id)) return
        File(dir, "$id.lut").delete()
        File(dir, "$id.cube").delete()
        synchronized(this) { if (cached?.first == id) cached = null }
    }

    fun ids(): List<String> = dir.listFiles { f -> f.isFile && f.extension == "lut" }.orEmpty()
        .map { it.nameWithoutExtension }.filter(ID::matches).sorted()

    companion object {
        private const val MAGIC = 0x334B5053   // "SPK3": baked table and original committed together
        private const val BAKED_MAGIC = 0x324B5053
        private const val LEGACY_MAGIC = 0x4C4B5053   // "SPKL", little-endian
        private const val HEADER = 12
        private const val MAX_ORIGINAL_BYTES = 32L * 1024 * 1024
        private val MAX_BYTES = HEADER + 4 + Lut3.NATIVE_SIDE.toLong() * Lut3.NATIVE_SIDE * Lut3.NATIVE_SIDE * 12 + MAX_ORIGINAL_BYTES
        val ID = Regex("lut_[a-z0-9_]{1,48}")
    }
}
