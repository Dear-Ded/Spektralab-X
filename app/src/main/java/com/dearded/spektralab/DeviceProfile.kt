package com.dearded.spektralab

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/** The first Vulkan device the JNI probe reported. */
data class GpuInfo(
    val name: String,
    val api: String,
    val driver: Long,
    val maxStorageBufferRange: Long,
) {
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
    private const val BYTES_PER_PLANE_PIXEL = 16L

    /** Measured on other ports: ~70-100 B/px live across the pipeline, plus host copies. */
    private const val BYTES_PER_WORKING_PIXEL = 100L

    /** Left to the system so lmkd does not start killing apps. */
    private const val SYSTEM_RESERVE = 3L shl 29

    /** The working edges Settings offers (0 = original size is offered separately). */
    val EDGES = listOf(2048, 3072, 4096, 6144)

    /** Past this a phone render gets slow for little visible gain. */
    private const val RECOMMENDED_CEILING = 4096

    fun gpu(deviceJson: String): GpuInfo? = runCatching {
        val d = JSONObject(deviceJson).optJSONArray("devices")?.optJSONObject(0) ?: return null
        GpuInfo(d.optString("name"), d.optString("api"), d.optLong("driver"), d.optLong("maxStorageBufferRange"))
    }.getOrNull()

    /**
     * The largest frame this device can hold: the GPU's single-buffer cap, the
     * RAM left over, and the canvas ceiling, whichever is smallest. An
     * unreadable probe counts as the Vulkan minimum, never as "no limit".
     */
    fun maxPixels(gpu: GpuInfo?, availMem: Long): Long {
        val range = gpu?.maxStorageBufferRange?.takeIf { it > 0 } ?: VULKAN_MIN_STORAGE_RANGE
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
        val range = gpu.maxStorageBufferRange
        return "${gpu.name} · Vulkan ${gpu.api} · 单缓冲上限 ${range shr 20} MB" +
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
 * PowerManager's forecast of how close the SoC is to throttling, 10 s ahead:
 * 1.0 means throttling now, 0 means unknown (API < 30, or a HAL that does not
 * report it). The API answers NaN when asked more than once a second, so the
 * last real reading is kept for 1.5 s.
 */
class ThermalMonitor(private val context: Context) {
    @Volatile private var value = 0f
    @Volatile private var at = 0L

    fun headroom(): Float {
        if (Build.VERSION.SDK_INT < 30) return 0f
        val now = SystemClock.elapsedRealtime()
        if (at != 0L && now - at < 1500) return value
        val pm = context.getSystemService(PowerManager::class.java) ?: return 0f
        val h = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        at = now
        if (!h.isNaN()) value = h
        return value
    }
}
