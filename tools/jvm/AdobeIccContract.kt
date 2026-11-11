// Host-only check of the production Adobe profile through Java's independent CMM.
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
    val data = IccProfile.generateAdobeRgb()
    check(data.contentEquals(IccProfile.generateAdobeRgb()))
    if (args.isNotEmpty()) File(args[0]).writeBytes(data)
    val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    check(b.getInt(0) == data.size && data.size % 4 == 0)
    val tags = mutableMapOf<String, Pair<Int, Int>>()
    repeat(b.getInt(128)) { i ->
        val entry = 132 + i * 12
        val name = String(data, entry, 4, Charsets.US_ASCII)
        val offset = b.getInt(entry + 4)
        val size = b.getInt(entry + 8)
        check(offset % 4 == 0 && offset >= 132 + b.getInt(128) * 12 && size >= 8 && offset <= data.size - size)
        check(tags.put(name, offset to size) == null)
    }
    val matrix = arrayOf(
        doubleArrayOf(.60974, .20528, .14919),
        doubleArrayOf(.31111, .62567, .06322),
        doubleArrayOf(.01947, .06087, .74457),
    )
    for ((col, name) in listOf("rXYZ", "gXYZ", "bXYZ").withIndex()) {
        val offset = tags.getValue(name).first
        check(String(data, offset, 4, Charsets.US_ASCII) == "XYZ ")
        repeat(3) { row -> check(abs(b.getInt(offset + 8 + row * 4) / 65536.0 - matrix[row][col]) < 1.0 / 65536) }
    }
    for (name in listOf("rTRC", "gTRC", "bTRC")) {
        val (offset, size) = tags.getValue(name)
        check(size == 16 && String(data, offset, 4, Charsets.US_ASCII) == "para")
        check(b.getShort(offset + 8).toInt() == 0)
        check(b.getInt(offset + 12) == 563 * 256) { "Gamma must be exactly 563/256, not 2.2" }
    }
    check(tags.getValue("chad").second == 44)
    val cmm = ICC_ColorSpace(ICC_Profile.getInstance(data))
    var maxError = 0.0
    var samples = 0
    // Gray and each independent channel: includes near-black and endpoints.
    for (i in 0..1024) for (channel in -1..2) {
        val encoded = FloatArray(3) { if (channel == -1 || channel == it) i / 1024f else 0f }
        val actual = cmm.toCIEXYZ(encoded)
        repeat(3) { row ->
            val expected = (0..2).sumOf { matrix[row][it] * encoded[it].toDouble().pow(563.0 / 256) }
            val error = abs(actual[row] - expected)
            maxError = maxOf(maxError, error)
            check(error < .0005) { "Adobe CMM mismatch: $i/$channel/$row $error" }
        }
        samples++
    }
    println("PASS actual Adobe ICC: structure, three exact gamma TRCs, D50 columns, $samples independent CMM cases; max XYZ error=$maxError")
}
