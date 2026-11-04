package com.dearded.spektralab

import android.content.Context
import org.json.JSONObject

/**
 * The single-buffer size this GPU really addresses, measured once per driver
 * (NativeEngine.probeStorageRange, storage_probe.cpp) and kept.
 *
 * Drivers have reported ranges they do not honour (Adreno 825: 2.25 GiB
 * advertised, writes past 2 GiB dropped, then VK_ERROR_DEVICE_LOST), so a
 * frame is sized against what the probe saw land, never against the driver's
 * number alone (GpuInfo.storageRange). A probe that takes the process down is
 * not run again for that driver: the next start finds the marker it left and
 * keeps the spec minimum, 128 MiB.
 */
object StorageProbe {
    private const val PREFS = "gpu_probe"

    /**
     * [deviceJson] (NativeEngine.deviceInfo) with `verifiedStorageRange` on its
     * first device: cached for this GPU and driver, else measured now. Engine
     * thread, before NativeEngine.create. Never throws; on any trouble the
     * JSON comes back as it was and the conservative size applies.
     */
    fun annotate(context: Context, deviceJson: String, events: EventLog): String = runCatching {
        val root = JSONObject(deviceJson)
        val dev = root.optJSONArray("devices")?.optJSONObject(0) ?: return deviceJson
        val advertised = dev.optLong("maxStorageBufferRange")
        val key = "${dev.optString("name")}|${dev.optLong("driver")}|$advertised"
        val prefs = context.getSharedPreferences(PREFS, 0)
        val verified = when {
            prefs.getString("key", null) == key && prefs.contains("bytes") -> prefs.getLong("bytes", 0L)
            prefs.getString("probing", null) == key -> {
                // The last probe for this driver never came back.
                events.add("storage probe: crashed last time, keeping ${DeviceProfile.VULKAN_MIN_STORAGE_RANGE shr 20} MiB")
                prefs.edit().putString("key", key).putLong("bytes", 0L).remove("probing").commit()
                0L
            }
            else -> {
                prefs.edit().putString("probing", key).commit()
                val bytes = NativeEngine.probeStorageRange(ceiling(context, advertised))
                events.add("storage probe: ${NativeEngine.lastError()}")
                prefs.edit().putString("key", key).putLong("bytes", bytes).remove("probing").commit()
                bytes
            }
        }
        dev.put("verifiedStorageRange", verified)
        root.toString()
    }.getOrDefault(deviceJson)

    /**
     * Nothing larger than the largest plane the app ever asks for
     * (DeviceProfile.MAX_CANVAS_PIXELS at 16 B/px), and never more than a
     * quarter of the free RAM, so the probe's own allocation stays harmless.
     */
    private fun ceiling(context: Context, advertised: Long): Long {
        val plane = DeviceProfile.MAX_CANVAS_PIXELS * DeviceProfile.BYTES_PER_PLANE_PIXEL
        val ram = DeviceProfile.availableMemory(context) / 4
        val limit = minOf(plane, if (advertised > 0) advertised else plane)
        return if (ram > 0) maxOf(DeviceProfile.VULKAN_MIN_STORAGE_RANGE, minOf(limit, ram)) else limit
    }
}
