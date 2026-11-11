// Host-only independent CMM check of the production Kotlin profile generator.
// Co-created with GPT (OpenAI), 2026-11-11.
import com.dearded.spektralab.IccProfile
import java.awt.color.ICC_ColorSpace
import java.awt.color.ICC_Profile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

fun main(args: Array<String>) {
    val data = IccProfile.generateProPhotoRgb()
    if (args.isNotEmpty()) File(args[0]).writeBytes(data)
    val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    println("profile bytes=${data.size}, declared=${b.getInt(0)}")
    check(b.getInt(0) == data.size) { "ICC header length differs from serialized profile" }
    check(data.size % 4 == 0)
    val tags = mutableMapOf<String, Pair<Int, Int>>()
    repeat(b.getInt(128)) { index ->
        val entry = 132 + index * 12
        val signature = String(data, entry, 4, Charsets.US_ASCII)
        val offset = b.getInt(entry + 4)
        val size = b.getInt(entry + 8)
        check(offset % 4 == 0 && offset >= 132 + b.getInt(128) * 12 && size >= 8 && offset <= data.size - size) {
            "invalid ICC tag $signature offset=$offset size=$size"
        }
        tags[signature] = offset to size
    }
    val matrix = arrayOf(
        doubleArrayOf(.7976749, .1351917, .0313534),
        doubleArrayOf(.2880402, .7118741, .0000857),
        doubleArrayOf(0.0, 0.0, .8252100),
    )
    listOf("rXYZ", "gXYZ", "bXYZ").forEachIndexed { col, tag ->
        val start = tags.getValue(tag).first
        check(String(data, start, 4, Charsets.US_ASCII) == "XYZ ")
        repeat(3) { row -> check(abs(b.getInt(start + 8 + row * 4) / 65536.0 - matrix[row][col]) <= 1.0 / 65536) }
    }
    val profile = ICC_Profile.getInstance(data)
    check(profile.colorSpaceType == java.awt.color.ColorSpace.TYPE_RGB)
    val cmm = ICC_ColorSpace(profile)
    val levels = listOf(0.0, 1.0 / 1024, 1.0 / 32 - 1.0 / 65535, 1.0 / 32, 1.0 / 32 + 1.0 / 65535, .18, .5, 1.0)
    var maxError = 0.0
    var samples = 0
    for (level in levels) for (channel in -1..2) {
        val encoded = FloatArray(3) { if (channel < 0 || it == channel) level.toFloat() else 0f }
        val linear = encoded.map { if (it < 1.0 / 32) it.toDouble() / 16 else it.toDouble().pow(1.8) }
        val actual = cmm.toCIEXYZ(encoded)
        repeat(3) { row ->
            val expected = (0..2).sumOf { matrix[row][it] * linear[it] }
            maxError = maxOf(maxError, abs(actual[row] - expected))
            check(abs(actual[row] - expected) < 0.0005) { "CMM response differs at $level/$channel/$row: ${actual[row]} vs $expected" }
        }
        samples++
    }
    println("PASS ICC structure, engine D50 colorants, independent Java CMM: $samples samples, max XYZ error=$maxError")
}
