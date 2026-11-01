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

/**
 * A foreground service that does no work: it only raises the process's
 * priority while an export runs, so switching away does not get it killed
 * (another port measured oom_score_adj 50 instead of 700 with one). The export
 * itself stays in EditorViewModel. Every failure here is non-fatal: without
 * the service the export still runs, just less protected.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
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
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, notification)
            }
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    // Android 15 caps dataSync time; an export never comes near it, but stop cleanly if it does.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val ID = 1
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ExportService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ExportService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
