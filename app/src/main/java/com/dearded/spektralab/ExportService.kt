package com.dearded.spektralab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
 * Two rules keep it from crashing the app:
 *  - it never stops itself before startForeground: a service started with
 *    startForegroundService and stopped first is fatal on Android 8+;
 *  - it stops only by start id, so a start queued behind a stop survives it.
 * Exports are counted, so one finishing does not unprotect another.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "导出", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SpektraLab 正在导出")
            .setContentText("全尺寸渲染中，可以切到其他应用")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        // A failure is left alone: the export's own stop cleans up.
        runCatching {
            when {
                // Local image processing is what mediaProcessing is for (Android 15).
                Build.VERSION.SDK_INT >= 35 ->
                    startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 caps both types at about 6 h; an export never comes near it.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    companion object {
        private const val CHANNEL = "export"
        private const val ID = 1
        private const val ACTION_STOP = "stop"
        private val active = AtomicInteger()

        fun start(context: Context) {
            if (active.getAndIncrement() != 0) return
            runCatching { context.startForegroundService(Intent(context, ExportService::class.java)) }
        }

        fun stop(context: Context) {
            if (active.decrementAndGet() > 0) return
            active.set(0)
            runCatching { context.startService(Intent(context, ExportService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
