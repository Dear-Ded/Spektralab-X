package com.dearded.spektralab

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Paces the export queue by the phone's thermal headroom and ADPF
 * (PerformanceHintManager), so a long export neither starts hot nor takes
 * the big cores while the user edits.
 *
 *  - Before a render ([beforeRender]): waits while the phone is at the
 *    severe thermal line (an ordinary export, at most [WAIT_MS]), or at the
 *    editor's hot line for 极致渲染 (at most [EXTREME_WAIT_MS], as before).
 *    Cool, it tells ADPF the CPU load is about to rise (API 33).
 *  - During a render ([during]): every [TICK_MS], while a finger is on the
 *    editor or the phone is past the moderate line, the export thread's
 *    session prefers power efficiency (API 35), so the scheduler keeps it
 *    off the cores the editor needs; otherwise it does not.
 *
 * The lines come from PowerManager.getThermalHeadroomThresholds (API 35),
 * else the editor's fixed 0.85. No work durations are reported: a render is
 * one JNI call of seconds, not a periodic workload; per-submit reports would
 * need the engine (docs/ROADMAP.md M6). ExportThread already runs at
 * background priority and LibRaw's workers are already limited to the big
 * cores (configure_raw_threads), so nothing here pins threads.
 *
 * Every call is optional: on a phone without ADPF only the thermal wait runs.
 */
class ExportPacer(private val context: Context, private val host: EngineHost) {
    private val lock = Any()
    // Under [lock]. Null until the first render, or when ADPF is not offered.
    private var session: PerformanceHintManager.Session? = null
    private var tried = false
    private var efficient = false

    private val lines: Pair<Float, Float> by lazy {
        // (moderate, severe); the headroom at which each status starts.
        val t = if (Build.VERSION.SDK_INT >= 35) runCatching {
            context.getSystemService(PowerManager::class.java)?.thermalHeadroomThresholds
        }.getOrNull() else null
        val moderate = t?.get(PowerManager.THERMAL_STATUS_MODERATE)?.takeIf { it > 0f } ?: HOT_HEADROOM
        val severe = t?.get(PowerManager.THERMAL_STATUS_SEVERE)?.takeIf { it > 0f } ?: 1f
        moderate to severe
    }

    /** Before an export render starts: the thermal wait, then a load-up hint. */
    suspend fun beforeRender(extreme: Boolean) {
        ensureSession()
        val (_, severe) = lines
        val line = if (extreme) minOf(HOT_HEADROOM, severe) else severe
        val until = SystemClock.uptimeMillis() + if (extreme) EXTREME_WAIT_MS else WAIT_MS
        var waited = false
        while (host.thermal.headroom() >= line && SystemClock.uptimeMillis() < until) {
            waited = true
            delay(1000)
        }
        if (waited) host.events.add("export waited for the phone to cool (headroom ${"%.2f".format(java.util.Locale.ROOT, host.thermal.headroom())})")
        // Session.sendHint(CPU_LOAD_UP) is not public API in SDK 36; the target
        // duration set in ensureSession already tells ADPF what the render needs.
    }

    /** Runs [body] (the render) with the efficiency switch kept current. */
    suspend fun <T> during(body: suspend () -> T): T {
        val ticker = if (Build.VERSION.SDK_INT >= 35 && synchronized(lock) { session } != null) host.scope.launch(Dispatchers.Default) {
            while (isActive) {
                val editing = SystemClock.uptimeMillis() - host.lastInteraction < EDITOR_GRACE_MS
                setEfficient(editing || host.thermal.headroom() >= lines.first)
                delay(TICK_MS)
            }
        } else null
        try {
            return body()
        } finally {
            ticker?.cancel()
            setEfficient(false)
        }
    }

    private fun setEfficient(on: Boolean) {
        if (Build.VERSION.SDK_INT < 35) return
        synchronized(lock) {
            if (on == efficient) return
            efficient = on
            runCatching { session?.setPreferPowerEfficiency(on) }
        }
    }

    // Once per process: the export thread's id comes from the thread itself.
    private suspend fun ensureSession() {
        if (synchronized(lock) { tried }) return
        if (Build.VERSION.SDK_INT < 31) {
            synchronized(lock) { tried = true }
            return
        }
        val tid = withContext(ExportThread.dispatcher) { android.os.Process.myTid() }
        val made = runCatching {
            context.getSystemService(PerformanceHintManager::class.java)
                ?.createHintSession(intArrayOf(tid), TARGET_NANOS)
        }.getOrNull()
        synchronized(lock) {
            if (tried) { runCatching { made?.close() }; return }
            tried = true
            session = made
        }
        val (moderate, severe) = lines
        host.events.add("ADPF: hint session ${if (made != null) "on" else "not offered"}; " +
            "thermal lines moderate ${"%.2f".format(java.util.Locale.ROOT, moderate)}, severe ${"%.2f".format(java.util.Locale.ROOT, severe)}")
    }

    private companion object {
        // The editor's own "hot" line (EditorViewModel.kHotHeadroom).
        const val HOT_HEADROOM = 0.85f
        const val WAIT_MS = 30_000L
        // 极致渲染: at most two minutes of waiting, so a phone that never cools still exports.
        const val EXTREME_WAIT_MS = 120_000L
        const val TICK_MS = 500L
        // ProcessingQueue's quiet period after the last touch.
        const val EDITOR_GRACE_MS = 1200L
        // Required by createHintSession; nothing is measured against it.
        const val TARGET_NANOS = 1_000_000_000L
    }
}
