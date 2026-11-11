package android.os

object Build { object VERSION { const val SDK_INT = 34 } }
class Bundle {
    fun putInt(key: String, value: Int) {}
    fun putString(key: String, value: String) {}
    fun putStringArray(key: String, value: Array<String>) {}
}
class ParcelFileDescriptor(private val file: java.io.RandomAccessFile) : java.io.Closeable {
    val statSize get() = file.length()
    val fileDescriptor get() = file.fd
    override fun close() = file.close()
}
