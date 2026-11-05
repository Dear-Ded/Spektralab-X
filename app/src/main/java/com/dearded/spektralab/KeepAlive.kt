package com.dearded.spektralab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The one-time "keep exports alive" hint. HyperOS freezes and kills
 * background apps early unless 省电策略 is 无限制 and 自启动 is on; there is no
 * API to read either (dontkillmyapp.com/xiaomi), so the hint is shown once,
 * and only after an export was actually cut short by the system:
 *  - the previous process died while ExportService was foreground, for a
 *    reason that is not memory, a crash or the user ([flagFromExit]);
 *  - the system refused the export's foreground service ([flag], from
 *    ProcessingQueue.pause).
 * Never at launch. Memory kills are left out: battery settings do not help
 * there, the queue already retries a size smaller.
 *
 * The queue already survives a kill (it is persisted, resumed on the next
 * start, and a refused start waits for the app to be visible), so this only
 * makes the kill less likely.
 */
object KeepAlive {
    private const val PREFS = "keep_alive"

    /** Any thread. Marks the hint due unless it was already shown. */
    fun flag(context: Context, why: String, events: EventLog? = null) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (prefs.getBoolean("shown", false) || prefs.getBoolean("due", false)) return
        prefs.edit().putBoolean("due", true).apply()
        events?.add("keep-alive hint due: $why")
    }

    /** IO, at start-up after ExitDiagnostics. */
    fun flagFromExit(context: Context, kind: ExitKind, duringExport: Boolean, events: EventLog) {
        if (kind == ExitKind.SYSTEM && duringExport) flag(context, "export ended by the system", events)
    }

    fun due(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return prefs.getBoolean("due", false) && !prefs.getBoolean("shown", false)
    }

    /** The user acted on the hint or dismissed it: never again. */
    fun shown(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("shown", true).putBoolean("due", false).apply()
    }

    val xiaomi: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /**
     * 省电策略. MIUI's power-keeper page first (exported up to Android 12;
     * SecurityException on 13+, which [startFirst] skips), then the app's
     * details page, which on HyperOS carries 省电策略 itself.
     */
    fun openBattery(context: Context): Boolean {
        val pkg = context.packageName
        val tries = buildList {
            if (xiaomi) add(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", pkg)
                .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString()))
            add(appDetails(context))
        }
        return startFirst(context, tries)
    }

    /** 自启动: the security centre's list, its intent action, then the app's details page. */
    fun openAutostart(context: Context): Boolean {
        val tries = buildList {
            if (xiaomi) {
                add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
            }
            add(appDetails(context))
        }
        return startFirst(context, tries)
    }
}
