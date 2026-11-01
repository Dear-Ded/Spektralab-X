package com.dearded.spektralab

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The process. Owns the one [EngineHost]. */
class SpektraLabApp : Application() {
    val host: EngineHost by lazy { EngineHost(this) }
}

/**
 * Process-lifetime owner of the native engine: its resources, setup caches and
 * compiled Vulkan pipelines. It outlives any one editor, so leaving and
 * re-entering the app while the process lives reuses a warm engine, and an
 * export started in an editor finishes after that editor is gone.
 *
 * Sessions (one open photo each) still belong to the editor that opened them.
 */
class EngineHost(private val app: Application) {
    /** For work that must outlive an editor, such as an export. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val events = EventLog()
    val thermal = ThermalMonitor(app)

    class Ready(
        val engine: Long,
        val dir: File,
        val catalog: Catalog,
        val deviceJson: String,
        val capabilitiesJson: String,
        /** True when an earlier editor already started it. */
        val reused: Boolean,
    )

    private val mutex = Mutex()
    private var ready: Ready? = null

    /** Starts the engine once per process; later calls return the same one. */
    suspend fun start(film: String, paper: String): Ready = mutex.withLock {
        ready?.let { r -> return@withLock Ready(r.engine, r.dir, r.catalog, r.deviceJson, r.capabilitiesJson, reused = true) }
        val started = withContext(EngineThread.dispatcher) {
            val dir = EngineAssets.install(app)
            val device = NativeEngine.deviceInfo()
            val catalog = Catalog.load(dir)
            val tmp = File(app.cacheDir, "engine-tmp").apply { mkdirs() }
            val handle = NativeEngine.create(dir.absolutePath, tmp.absolutePath)
            if (handle == 0L) {
                throw EngineException(
                    "引擎启动失败：${NativeEngine.lastError()}\n\nGPU：${DeviceProfile.summary(DeviceProfile.gpu(device))}"
                )
            }
            // The default stock pair's tables and every compute pipeline now, so
            // the first photo's first preview does not pay for them. Not fatal:
            // open would do the same work.
            if (!NativeEngine.warmUp(handle, film, paper)) events.add("warm up failed: ${NativeEngine.lastError()}")
            Ready(handle, dir, catalog, device, NativeEngine.capabilities(handle), reused = false)
        }
        ready = started
        started
    }
}
