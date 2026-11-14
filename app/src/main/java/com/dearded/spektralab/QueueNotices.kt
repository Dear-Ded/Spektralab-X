package com.dearded.spektralab

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Notices at the top of 正在处理 for what the system settings are hiding or
 * cutting short. Each is checked again when the app comes back from Settings.
 */
@Composable
fun QueueNotices() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notice by remember { mutableStateOf(NotificationNotice.of(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) notice = NotificationNotice.of(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    notice?.let { n -> NoticeCard(n.text, listOf(n.action to { NotificationNotice.open(context, n) })) }
    // Once, and only after the system cut an export short (KeepAlive).
    var keepAlive by remember { mutableStateOf(KeepAlive.due(context)) }
    if (keepAlive) {
        // A settings button records the hint as seen but keeps the card up, so
        // the other button is still there on return; 知道了 hides it.
        NoticeCard(
            tr("上次导出被系统中断了。把省电策略设为“无限制”") + (if (KeepAlive.xiaomi) tr("、打开自启动") else "") +
                tr("，切到后台时就不容易被打断。未完成的任务会自动继续。"),
            buildList<Pair<String, () -> Unit>> {
                add(tr("省电策略") to { KeepAlive.shown(context); KeepAlive.openBattery(context); Unit })
                if (KeepAlive.xiaomi) add(tr("自启动") to { KeepAlive.shown(context); KeepAlive.openAutostart(context); Unit })
                add(tr("知道了") to { KeepAlive.shown(context); keepAlive = false })
            },
        )
    }
}

@Composable
internal fun NoticeCard(text: String, actions: List<Pair<String, () -> Unit>>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                for ((label, onClick) in actions) TextButton(onClick = onClick) { Text(label) }
            }
        }
    }
}

/**
 * The processing notice is off (all notifications) or kept out of the status
 * bar and lock screen (Android 16 Live Updates turned off for this app).
 */
internal enum class NotificationNotice(private val message: String, private val actionMessage: String) {
    ALL_OFF("通知已关闭：后台处理时看不到进度，完成时也不会提醒。", "打开通知"),
    LIVE_OFF("实时通知已关闭：进度不会出现在状态栏、锁屏和超级岛，只在通知栏里。", "打开实时通知");

    val text get() = tr(message)
    val action get() = tr(actionMessage)

    companion object {
        fun of(context: Context): NotificationNotice? {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return null
            if (!runCatching { nm.areNotificationsEnabled() }.getOrDefault(true)) return ALL_OFF
            if (Build.VERSION.SDK_INT >= 36 && !runCatching { nm.canPostPromotedNotifications() }.getOrDefault(true)) return LIVE_OFF
            return null
        }

        /** The most specific settings page this phone opens, then the app's notification page, then its details. */
        fun open(context: Context, notice: NotificationNotice) {
            val pkg = context.packageName
            val tries = buildList {
                if (notice == LIVE_OFF && Build.VERSION.SDK_INT >= 36) {
                    add(Intent(PROMOTED_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                }
                add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                add(appDetails(context))
            }
            startFirst(context, tries)
        }
    }
}

internal fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/** Starts the first intent the phone has an activity for and lets us open; false when none did. */
internal fun startFirst(context: Context, intents: List<Intent>): Boolean {
    for (intent in intents) {
        val started = try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            // A vendor screen that is not exported (MIUI's power keeper on Android 13+).
            false
        }
        if (started) return true
    }
    return false
}

/** Android 16 Live Updates settings page; no public constant in SDK 36, so the action string (startFirst falls back if absent). */
private const val PROMOTED_SETTINGS = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
