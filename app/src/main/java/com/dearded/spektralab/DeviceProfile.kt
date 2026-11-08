package com.dearded.spektralab

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** The first Vulkan device the JNI probe reported. */
data class GpuInfo(
    val name: String,
    val api: String,
    val driver: Long,
    val maxStorageBufferRange: Long,
    /** What StorageProbe measured to land (0 = not measured, or even 128 MiB failed). */
    val verifiedStorageRange: Long = 0,
) {
    /**
     * The single-buffer size frames are sized against: the measured one,
     * never above what the driver advertises (the engine refuses more), and
     * the spec minimum when nothing was measured. A driver's own number is
     * not trusted alone: Adreno 825 advertised 2.25 GiB and dropped writes
     * past 2 GiB (SAINTTaiYi/SpektraLab-Android fa199b6).
     */
    val storageRange: Long get() {
        val advertised = maxStorageBufferRange.takeIf { it > 0 } ?: DeviceProfile.VULKAN_MIN_STORAGE_RANGE
        return if (verifiedStorageRange > 0) minOf(verifiedStorageRange, advertised)
               else minOf(advertised, DeviceProfile.VULKAN_MIN_STORAGE_RANGE)
    }

    /** "Adreno (TM) 740" -> 740; 0 when the GPU is not an Adreno. */
    val adreno: Int get() = ADRENO.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private companion object {
        val ADRENO = Regex("""Adreno\D*(\d{3})""")
    }
}

/**
 * What this phone can render, from measured limits rather than model names.
 * See docs/DEVICES.md for the Xiaomi numbers behind the constants.
 */
object DeviceProfile {
    /** The Vulkan spec minimum, and what Adreno 7xx (Xiaomi 12-14) reports. */
    const val VULKAN_MIN_STORAGE_RANGE = 128L shl 20

    /** 42.7 MP rendered and 44.8 MP lost the device on Adreno in another port. */
    const val MAX_CANVAS_PIXELS = 42_000_000L

    /** One storage buffer holds a whole plane; the widest is four float channels. */
    const val BYTES_PER_PLANE_PIXEL = 16L

    /** Measured on other ports: ~70-100 B/px live across the pipeline, plus host copies. */
    private const val BYTES_PER_WORKING_PIXEL = 100L

    /** Left to the system so lmkd does not start killing apps. */
    private const val SYSTEM_RESERVE = 3L shl 29

    /**
     * The largest buffer the storage probe tests: a [MAX_CANVAS_PIXELS] plane.
     * Nothing larger is ever rendered, and probing more would only allocate
     * GPU memory a phone may not have to spare.
     */
    const val PROBE_CEILING = MAX_CANVAS_PIXELS * BYTES_PER_PLANE_PIXEL

    /** The working edges Settings offers (0 = original size is offered separately). */
    val EDGES = listOf(2048, 3072, 4096, 6144)

    /** Past this a phone render gets slow for little visible gain. */
    private const val RECOMMENDED_CEILING = 4096

    fun gpu(deviceJson: String): GpuInfo? = runCatching {
        val d = JSONObject(deviceJson).optJSONArray("devices")?.optJSONObject(0) ?: return null
        GpuInfo(d.optString("name"), d.optString("api"), d.optLong("driver"), d.optLong("maxStorageBufferRange"),
            d.optLong("verifiedStorageRange"))
    }.getOrNull()

    /**
     * The largest frame this device can hold: the GPU's single-buffer cap, the
     * RAM left over, and the canvas ceiling, whichever is smallest. An
     * unreadable probe counts as the Vulkan minimum, never as "no limit".
     */
    fun maxPixels(gpu: GpuInfo?, availMem: Long): Long {
        val range = gpu?.storageRange ?: VULKAN_MIN_STORAGE_RANGE
        val byGpu = range / BYTES_PER_PLANE_PIXEL
        val byRam = max(4_000_000L, (availMem - SYSTEM_RESERVE) / BYTES_PER_WORKING_PIXEL)
        return minOf(byGpu, byRam, MAX_CANVAS_PIXELS)
    }

    /**
     * The largest offered edge whose 4:3 frame fits [maxPixels], capped at
     * 4096. Adreno 7xx (128 MiB) gets 3072; Adreno 6xx and 8xx get 4096.
     */
    fun recommendedEdge(maxPixels: Long): Int =
        EDGES.filter { it <= RECOMMENDED_CEILING && it.toLong() * it * 3 / 4 <= maxPixels }.maxOrNull() ?: EDGES.first()

    fun summary(gpu: GpuInfo?): String {
        gpu ?: return "未检测到 Vulkan 设备"
        val range = gpu.storageRange
        val how = if (gpu.verifiedStorageRange > 0) "实测" else "未实测，按保守值"
        return "${gpu.name} · Vulkan ${gpu.api} · 单缓冲上限 ${range shr 20} MB（$how，驱动报告 ${gpu.maxStorageBufferRange shr 20} MB）" +
            " · 约 ${(range / BYTES_PER_PLANE_PIXEL / 1_000_000.0 * 10).roundToInt() / 10.0} MP"
    }

    fun summarizeCapabilities(caps: String): String = runCatching {
        val backend = JSONObject(caps).optJSONObject("backend") ?: return@runCatching caps
        "${backend.optString("render_core")} · ${backend.optString("math_mode")}"
    }.getOrDefault(caps)

    fun availableMemory(context: Context): Long {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return 0L
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }
}

/**
 * PowerManager's forecast 10 s ahead; 1.0 is the severe-throttling threshold.
 * Android recommends one querying thread and at least 10 s between samples:
 * https://developer.android.com/games/optimize/adpf/thermal
 * Calls return immediately. NaN denotes unavailable or expired headroom.
 */
class ThermalMonitor(context: Context) {
    private val app = context.applicationContext
    private val sampler = ThermalHeadroomSampler(
        clock = SystemClock::elapsedRealtime,
        executor = queryThread,
        query = {
            if (Build.VERSION.SDK_INT >= 30) {
                app.getSystemService(PowerManager::class.java)?.getThermalHeadroom(10) ?: Float.NaN
            } else Float.NaN
        },
    )

    init {
        if (Build.VERSION.SDK_INT >= 30) sampler.headroom()
    }

    fun headroom(): Float {
        if (Build.VERSION.SDK_INT < 30) return Float.NaN
        return sampler.headroom()
    }

    private companion object {
        val queryThread = Executors.newSingleThreadExecutor { task ->
            Thread(task, "spektralab-thermal").apply { isDaemon = true }
        }
    }
}
