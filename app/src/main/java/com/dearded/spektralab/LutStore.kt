package com.dearded.spektralab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Imported colour tables, in filesDir/user/luts (docs/STYLES.md 7.2). Each id
 * keeps two files: `<id>.lut`, the table baked for Layer 2 (encoded ProPhoto,
 * what NativeEngine.setLut takes), and `<id>.cube`, the table as imported (at
 * most 65 a side) with the space it was made for, so a style exported again
 * carries the original rather than the baked one. Look.lut holds the id.
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
        dir.mkdirs()
        val buf = ByteBuffer.allocate(HEADER + baked.data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC).putInt(baked.size).putInt(if (space == SpkLut.SPACE_PROPHOTO) 1 else 0)
        for (x in baked.data) buf.putFloat(x)
        write(File(dir, "$id.lut"), buf.array())
        write(File(dir, "$id.cube"), raw.toCube(id))
        synchronized(this) { cached = id to baked }
        return baked
    }

    /** The baked table, or null when [id] is unknown or its file is damaged. Keeps the last one in memory. */
    fun load(id: String): Lut3? {
        if (!ID.matches(id)) return null
        synchronized(this) { cached?.let { if (it.first == id) return it.second } }
        val f = File(dir, "$id.lut")
        if (!f.isFile || f.length() > MAX_BYTES) return null
        val buf = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < HEADER || buf.getInt() != MAGIC) return null
        val side = buf.getInt()
        buf.getInt()
        if (side !in 2..Lut3.NATIVE_SIDE || buf.remaining() != side * side * side * 3 * 4) return null
        val data = FloatArray(side * side * side * 3) { buf.getFloat() }
        val lut = runCatching { Lut3(side, data) }.getOrNull() ?: return null
        synchronized(this) { cached = id to lut }
        return lut
    }

    /** The table as imported, as .cube text, and the space it was made for; null when unknown. */
    fun original(id: String): Pair<ByteArray, String>? {
        if (!ID.matches(id)) return null
        val cube = File(dir, "$id.cube").takeIf { it.isFile } ?: return null
        val head = File(dir, "$id.lut").takeIf { it.isFile }?.inputStream()?.use { s -> ByteArray(HEADER).also { s.read(it) } }
            ?: return null
        val space = if (ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(8) == 1) SpkLut.SPACE_PROPHOTO else SpkLut.SPACE_SRGB
        return cube.readBytes() to space
    }

    fun delete(id: String) {
        if (!ID.matches(id)) return
        File(dir, "$id.lut").delete()
        File(dir, "$id.cube").delete()
        synchronized(this) { if (cached?.first == id) cached = null }
    }

    fun ids(): List<String> = dir.listFiles { f -> f.isFile && f.extension == "lut" }.orEmpty()
        .map { it.nameWithoutExtension }.filter(ID::matches).sorted()

    private fun write(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) error("cannot store $target") }
    }

    companion object {
        private const val MAGIC = 0x4C4B5053   // "SPKL", little-endian
        private const val HEADER = 12
        private val MAX_BYTES = HEADER + Lut3.NATIVE_SIDE.toLong() * Lut3.NATIVE_SIDE * Lut3.NATIVE_SIDE * 12
        val ID = Regex("lut_[a-z0-9_]{1,48}")
    }
}
