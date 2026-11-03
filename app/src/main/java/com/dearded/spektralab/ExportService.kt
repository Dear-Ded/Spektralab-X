package com.dearded.spektralab

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicInteger

/**
 * A foreground service that does no work: it only raises the process's
 * priority while an export runs, so switching away does not get it killed
 * (another port measured oom_score_adj 50 instead of 700 with one). The export
 * itself runs in EngineHost's scope.
 *
 * Rules that keep it from crashing the app:
 *  - it never stops itself before startForeground: a service started with
 *    startForegroundService and stopped first is fatal on Android 8+;
 *  - it stops only by start id, so a start queued behind a stop survives it;
 *  - a refused start (Android 12+ from the background) and the Android 15+
 *    time limit pause the queue instead of letting the system kill us
 *    (onTimeout must stop the service within seconds or the app crashes).
 * Exports are counted, so one finishing does not unprotect another.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // The queue's own notice, under the queue's id: the service only makes it
        // the foreground one, and the queue keeps updating it in place.
        val queue = (application as SpektraLabApp).host.queue
        val notification = JobNotifier.build(this, queue.jobs.value)
        try {
            when {
                // Local image processing is what mediaProcessing is for (Android 15).
                Build.VERSION.SDK_INT >= 35 ->
                    startForeground(JobNotifier.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(JobNotifier.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(JobNotifier.ID, notification)
            }
        } catch (e: Exception) {
            // Refused (ForegroundServiceStartNotAllowedException from the
            // background on 12+, or the type's daily time used up on 15+):
            // stop rather than run unprotected, and hold the queue until the
            // app is visible again.
            active.set(0)
            queue.pause("foreground start refused: ${e.javaClass.simpleName}", serviceStopped = true)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // Android 15+: mediaProcessing and dataSync get about 6 h per 24 h. The
    // system calls this when the budget is gone and crashes the app if the
    // service is still foreground a few seconds later, so: checkpoint the
    // queue (the running job goes back in line, written synchronously) and stop.
    override fun onTimeout(startId: Int, fgsType: Int) {
        active.set(0)
        runCatching { (application as SpektraLabApp).host.queue.pause("foreground service time limit", serviceStopped = true) }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private val active = AtomicInteger()

        /** False when the system refused it (the app is in the background on Android 12+). */
        fun start(context: Context): Boolean {
            if (active.getAndIncrement() != 0) return true
            return try {
                context.startForegroundService(Intent(context, ExportService::class.java))
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) or an
                // IllegalStateException from a background start.
                active.set(0)
                false
            }
        }

        fun stop(context: Context) {
            if (active.decrementAndGet() > 0) return
            active.set(0)
            runCatching { context.startService(Intent(context, ExportService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
