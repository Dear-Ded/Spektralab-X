// Compiles the actual NativeEngine.kt against a host-only Bitmap type stub.
import com.dearded.spektralab.NativeEngine
import java.io.File

object TiffJniContract {
    @JvmStatic external fun open(engine: Long): Long
    @JvmStatic external fun descriptor(path: String): Int
    @JvmStatic external fun closeDescriptor(fd: Int): Boolean

    @JvmStatic fun main(args: Array<String>) {
        val engine = NativeEngine.create(args[0], args[1])
        check(engine != 0L) { NativeEngine.lastError() }
        val session = open(engine)
        check(session != 0L) { NativeEngine.lastError() }
        val icc = File(args[0], "io/sRGB.icc").absolutePath
        try {
            for (target in listOf("sRGB","Display P3","ProPhoto RGB")) {
                val output = File(args[1], target.replace(' ', '_')+".tif")
                val fd = descriptor(output.absolutePath)
                check(fd>=0)
                try { check(NativeEngine.exportTiffFd(session,fd,icc,target,"full")) { NativeEngine.lastError() } }
                finally { check(closeDescriptor(fd)) }
                check(output.length()>256)
            }
            val legacy = File(args[1],"legacy.tif")
            val fd = descriptor(legacy.absolutePath)
            try { check(NativeEngine.exportTiffFd(session,fd,icc,"full")) { NativeEngine.lastError() } }
            finally { check(closeDescriptor(fd)) }
            check(legacy.readBytes().contentEquals(File(args[1],"sRGB.tif").readBytes()))
            println("PASS actual Kotlin/JVM -> production JNI: versioned three-target and legacy TIFF; descriptor ownership; identical legacy sRGB bytes")
        } finally {
            NativeEngine.release(session)
            NativeEngine.destroy(engine)
        }
    }
}
