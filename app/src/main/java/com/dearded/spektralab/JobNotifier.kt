package com.dearded.spektralab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * The processing notification, built so every HyperOS generation shows it in
 * the best place it can:
 *
 *  - Android 16 (HyperOS 3): a standard Live Update. ProgressStyle, ongoing,
 *    requestPromotedOngoing, a short critical text. HyperOS 3.1 wraps promoted
 *    notifications into the Super Island for third-party apps.
 *  - HyperOS 2 and approved apps: the `miui.focus.param` extras Xiaomi's
 *    focus-notification template reads (status bar, island, lock screen, AOD).
 *    Without Xiaomi's focus permission, filterWhenNoPermission=false makes the
 *    system show it as an ordinary notification, so nothing is lost.
 *  - Everything else: an ordinary progress notification.
 *
 * Nothing here fails the job: a refused permission only hides the notice.
 */
object JobNotifier {
    const val ID = 1
    const val DONE_ID = 2
    /** The queue is held: the foreground-service limit, or a start the system refused. */
    const val PAUSED_ID = 3
    private const val CHANNEL = "processing"
    private const val CHANNEL_DONE = "processing_done"
    private const val ACCENT = 0xFFE6E6E6.toInt()   // UI2.md 1.1: neutral, no amber
    private var sequence = 0

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "正在处理", NotificationManager.IMPORTANCE_LOW).apply {
            description = "后台冲洗与导出的进度"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "处理完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "后台任务完成或失败时提醒"
        })
    }

    fun post(context: Context, id: Int, notification: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_QUEUE)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The ongoing notice for the whole queue; the running job leads. */
    fun build(context: Context, jobs: List<ProcessingJob>): Notification {
        ensureChannels(context)
        val active = jobs.filter { it.active }
        val running = active.firstOrNull { it.state == JobState.RUNNING }
        val pct = ((running?.progress ?: 0f) * 100).toInt().coerceIn(0, 100)
        val eta = ProcessingQueue.remainingSeconds(active)
        val etaText = ProcessingQueue.formatEta(eta)
        val position = if (running == null) 0 else jobs.filter { it.active || it.state == JobState.DONE }
            .sortedBy { it.id }.indexOfFirst { it.id == running.id } + 1
        val total = active.size + jobs.count { it.state == JobState.DONE && it.finishedAt > (active.minOfOrNull { a -> a.createdAt } ?: Long.MAX_VALUE) }
        val title = if (running == null) "等待处理 · ${active.size} 张" else "正在冲洗 ${running.name}"
        val text = buildString {
            if (running != null) append(running.phase.ifEmpty { "处理中" }).append(" · ")
            append("剩余约 ").append(etaText)
            if (active.size > 1) append(" · 还有 ").append(active.size - 1).append(" 张")
        }
        val shortText = if (eta < 60) "${eta}秒" else "${eta / 60}分"

        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_film)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (total > 1 && position > 0) "$position / $total" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(ACCENT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(shortText)

        if (Build.VERSION.SDK_INT >= 36) {
            b.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(pct)
                    .setProgressIndeterminate(running == null)
                    .setStyledByProgress(true)
                    .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100).setColor(ACCENT))
            )
        } else {
            b.setProgress(100, pct, running == null)
        }
        b.addExtras(focusExtras(context, title, text, pct, shortText))
        return b.build()
    }

    /**
     * The queue stopped because the system ended or refused its foreground
     * service (Android 15+ time limit, background start). Opening the app
     * continues it; nothing is lost, the jobs are still in line.
     */
    fun paused(context: Context, waiting: Int): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_film)
            .setContentTitle("后台处理已暂停 · $waiting 张待处理")
            .setContentText("系统限制了后台运行时间；打开应用即可继续")
            .setColor(ACCENT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun finished(context: Context, done: Int, failed: Int): Notification {
        ensureChannels(context)
        val title = when {
            failed == 0 -> "已冲洗完成 $done 张"
            done == 0 -> "$failed 张处理失败"
            else -> "完成 $done 张，失败 $failed 张"
        }
        return NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_film)
            .setContentTitle(title)
            .setContentText(if (failed == 0) "已保存到 相册 · Pictures/Spektralab-X" else "打开“正在处理”查看原因")
            .setColor(ACCENT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
    }

    /**
     * Xiaomi's focus-notification template (HyperOS 2/3). Keys and shape from
     * Xiaomi's integration guide as mirrored by Aliyun, and the Apache-2.0
     * HyperIsland-ToolKit. Ignored entirely on other ROMs.
     */
    private fun focusExtras(context: Context, title: String, text: String, pct: Int, short: String): Bundle {
        val extras = Bundle()
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return extras
        val pic = "miui.focus.pic_app"
        val param = JSONObject().put("param_v2", JSONObject()
            .put("protocol", 1)
            .put("business", "spektralab_processing")
            .put("updatable", true)
            .put("enableFloat", false)
            .put("ticker", "$title · $pct%")
            .put("tickerPic", pic)
            .put("aodTitle", "冲洗 $pct%")
            .put("baseInfo", JSONObject().put("type", 1).put("title", title).put("content", text))
            .put("progressInfo", JSONObject().put("progress", pct).put("colorProgress", "#F2F2F2"))
            .put("param_island", JSONObject()
                .put("islandProperty", 1)
                .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", pic))
                    .put("textInfo", JSONObject().put("title", "$pct%").put("content", short))))
                .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", pic))))
            .put("filterWhenNoPermission", false)
            .put("sequence", ++sequence))
        extras.putString("miui.focus.param", param.toString())
        val pics = Bundle()
        runCatching { pics.putParcelable(pic, Icon.createWithResource(context, R.drawable.ic_stat_film)) }
        extras.putBundle("miui.focus.pics", pics)
        return extras
    }

    /**
     * Where the processing notice can show on this phone, as the system says
     * right now. [focusProtocol]: Xiaomi's focus-template version
     * (Settings.System "notification_focus_protocol": 1 = OS1, 2 = OS2,
     * 3 = OS3 island; 0 = not a HyperOS that has it). [canShowFocus]: Xiaomi
     * granted this app focus notifications and the user has not turned them
     * off. Both from Xiaomi's HyperOS developer guide; the protocol value is
     * only read and logged, never used to pick a template.
     */
    data class Status(
        val enabled: Boolean,
        /** Android 16 Live Updates allowed (canPostPromotedNotifications); true before Android 16. */
        val promoted: Boolean,
        val focusProtocol: Int,
        val canShowFocus: Boolean,
    ) {
        fun describe() = "notifications ${if (enabled) "on" else "off"} · live updates " +
            (if (promoted) "on" else "off") + " · focus protocol $focusProtocol · canShowFocus $canShowFocus"
    }

    /**
     * Blocking (the focus check is a provider call Xiaomi marks as slow):
     * call off the main thread. Never throws; anything unreadable is "off".
     */
    fun status(context: Context): Status {
        val nm = context.getSystemService(NotificationManager::class.java)
        val enabled = runCatching { nm?.areNotificationsEnabled() ?: false }.getOrDefault(false)
        val promoted = if (Build.VERSION.SDK_INT >= 36) runCatching { nm?.canPostPromotedNotifications() ?: false }.getOrDefault(false) else true
        val xiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        val protocol = if (!xiaomi) 0 else runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        val focus = xiaomi && protocol > 0 && runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver.call(android.net.Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus", null, extras)?.getBoolean("canShowFocus", false) ?: false
        }.getOrDefault(false)
        return Status(enabled, promoted, protocol, focus)
    }

    /** IO: [status] into the diagnostics' pinned lines. */
    fun logStatus(context: Context, events: EventLog): Status =
        status(context).also { events.pin("notifications", it.describe()) }
}
