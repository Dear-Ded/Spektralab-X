package com.dearded.spektralab

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * Update check, download and install, from this repository's GitHub Releases
 * and nowhere else. The only network the app does: one GET of the release
 * list (at most every few hours, off in 设置), then the APK and its
 * SHA256SUMS when the user (or Wi-Fi auto-download) asks. Nothing is sent
 * beyond the request itself.
 *
 * A downloaded APK is installed only if its SHA-256 matches the release's
 * SHA256SUMS and it is signed by the same certificate as the running app;
 * Android checks the signature again on install.
 */
object Updater {
    private const val REPO = "Dear-Ded/Spektralab-X"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=10"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val CHECK_EVERY_MS = 6 * 3600_000L

    private const val KEY_AUTO = "update_auto_check"
    private const val KEY_WIFI = "update_auto_download"
    private const val KEY_LAST = "update_last_check"
    private const val KEY_SKIP = "update_skip"

    data class Release(
        val version: String, val notes: String, val page: String,
        val apkName: String, val apkUrl: String, val apkSize: Long, val sumsUrl: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        /** [background]: Wi-Fi auto-download, which shows nothing until it is done. */
        data class Downloading(val release: Release, val progress: Float, val background: Boolean) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    /** Whether the update dialog should be up. */
    val prompt = MutableStateFlow(false)
    /** The user asked (settings, menu): show the dialog even with a photo open. */
    @Volatile var explicit = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(c: Context) = c.getSharedPreferences("settings", 0)
    fun autoCheck(c: Context) = prefs(c).getBoolean(KEY_AUTO, true)
    fun setAutoCheck(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_AUTO, on).apply()
    fun wifiDownload(c: Context) = prefs(c).getBoolean(KEY_WIFI, true)
    fun setWifiDownload(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_WIFI, on).apply()

    /** At launch: drop installed APKs, then check if it is on and due. */
    fun onLaunch(context: Context) {
        val app = context.applicationContext
        scope.launch {
            File(app.cacheDir, "update").listFiles()?.forEach { f ->
                val v = f.name.substringAfter("Spektralab-X-", "").substringBefore("-arm64")
                if (v.isEmpty() || compareVersions(v, BuildConfig.VERSION_NAME) <= 0) f.delete()
            }
        }
        val last = prefs(app).getLong(KEY_LAST, 0L)
        if (autoCheck(app) && System.currentTimeMillis() - last >= CHECK_EVERY_MS) check(app, manual = false)
    }

    fun check(context: Context, manual: Boolean) {
        val app = context.applicationContext
        val now = state.value
        if (now is State.Checking || now is State.Downloading) { if (manual) show(); return }
        if (manual) explicit = true
        scope.launch {
            state.value = State.Checking
            val r = try { latest() } catch (e: Exception) {
                state.value = if (manual) State.Failed(tr("检查更新失败：{0}", reason(e)), null) else State.Idle
                prompt.value = manual
                return@launch
            }
            prefs(app).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            if (r == null || compareVersions(r.version, BuildConfig.VERSION_NAME) <= 0) {
                state.value = if (manual) State.UpToDate else State.Idle
                prompt.value = manual
                return@launch
            }
            if (!manual && prefs(app).getString(KEY_SKIP, null) == r.version) { state.value = State.Idle; return@launch }
            state.value = State.Available(r)
            if (!manual && wifiDownload(app) && unmetered(app)) download(app, r, background = true)
            else prompt.value = true
        }
    }

    /** Bring the dialog back for whatever state the update is in. */
    fun show() { explicit = true; prompt.value = true }

    fun later() { prompt.value = false }

    fun skip(context: Context, r: Release) {
        prefs(context).edit().putString(KEY_SKIP, r.version).apply()
        state.value = State.Idle
        prompt.value = false
    }

    fun download(context: Context, r: Release, background: Boolean = false) {
        val app = context.applicationContext
        if (state.value is State.Downloading) return
        if (!background) explicit = true
        scope.launch {
            state.value = State.Downloading(r, 0f, background)
            try {
                val sums = r.sumsUrl ?: throw IOException(tr("这个版本没有附带校验文件"))
                val want = sha256From(sums, r.apkName) ?: throw IOException(tr("校验文件里没有这个安装包"))
                val dir = File(app.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { if (!it.name.startsWith(r.apkName)) it.delete() }
                val dest = File(dir, r.apkName)
                if (!(dest.exists() && sha256(dest) == want)) {
                    dest.delete()
                    val part = File(dir, r.apkName + ".part")
                    fetch(r.apkUrl, part) { p -> state.value = State.Downloading(r, p, background) }
                    if (sha256(part) != want) { part.delete(); throw IOException(tr("校验和不符，安装包不完整或被改动")) }
                    if (!part.renameTo(dest)) throw IOException(tr("无法保存安装包"))
                }
                checkSigner(app, dest)
                state.value = State.Ready(r, dest)
                prompt.value = true
            } catch (e: Exception) {
                state.value = State.Failed(tr("下载失败：{0}", reason(e)), r)
                prompt.value = true
            }
        }
    }

    fun install(context: Context, file: File) {
        val app = context.applicationContext
        scope.launch {
            val installer = app.packageManager.packageInstaller
            var pendingSession: Int? = null
            try {
                checkSigner(app, file)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    // Silent only once this app is the installer of record; the first
                    // self-update still asks, as Android requires.
                    if (Build.VERSION.SDK_INT >= 31)
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                pendingSession = id
                installer.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 1 shl 16) }
                        s.fsync(out)
                    }
                    val done = PendingIntent.getBroadcast(
                        app, id, Intent(app, UpdateReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    s.commit(done.intentSender)
                    pendingSession = null
                }
            } catch (e: Exception) {
                pendingSession?.let { id -> runCatching { installer.abandonSession(id) } }
                failed(tr("安装失败：{0}", reason(e)))
            }
        }
    }

    internal fun failed(message: String) {
        val r = when (val s = state.value) { is State.Ready -> s.release; is State.Available -> s.release; else -> null }
        state.value = State.Failed(message, r)
        prompt.value = true
    }

    // --- network -------------------------------------------------------------

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Spektralab-X/${BuildConfig.VERSION_NAME}")
        }

    /** The highest-versioned published release with an arm64 APK, or null. */
    private fun latest(): Release? {
        val c = open(API)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val list = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            var best: Release? = null
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                if (o.optBoolean("draft")) continue
                val assets = o.optJSONArray("assets") ?: continue
                var apk: org.json.JSONObject? = null
                var sums: String? = null
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val n = a.optString("name")
                    if (n.endsWith(".apk") && n.contains("arm64-v8a")) apk = a
                    if (n == "SHA256SUMS") sums = a.optString("browser_download_url")
                }
                val a = apk ?: continue
                val r = Release(
                    o.optString("tag_name").removePrefix("v"), o.optString("body").trim(), o.optString("html_url"),
                    a.optString("name"), a.optString("browser_download_url"), a.optLong("size"), sums,
                )
                if (best == null || compareVersions(r.version, best.version) > 0) best = r
            }
            return best
        } finally { c.disconnect() }
    }

    private fun sha256From(url: String, name: String): String? {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readLines().firstNotNullOfOrNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[1].removePrefix("*") == name) parts[0].lowercase() else null
            }
        } finally { c.disconnect() }
    }

    /** Download to [part], resuming what an earlier attempt left there. */
    private fun fetch(url: String, part: File, progress: (Float) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val c = open(url)
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            val append = code == 206
            if (code != 200 && !append) throw IOException("HTTP $code")
            val start = if (append) have else 0L
            val total = start + c.contentLengthLong.coerceAtLeast(0L)
            var done = start
            var shown = -1
            c.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; progress(done.toFloat() / total) }
                        }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkSigner(context: Context, apk: File) {
        val pm = context.packageManager
        val theirs = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException(tr("安装包无法解析"))
        if (theirs.packageName != context.packageName) throw IOException(tr("安装包不是 Spektralab-X"))
        val ours = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (theirs.longVersionCode <= ours.longVersionCode) throw IOException(tr("安装包版本不高于当前应用"))
        val info = theirs.applicationInfo ?: throw IOException(tr("安装包缺少应用信息"))
        if ((info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            throw IOException(tr("安装包为调试版本，请下载正式签名的发布版本"))
        val a = theirs.signingInfo?.apkContentsSigners?.toSet()
        val b = ours.signingInfo?.apkContentsSigners?.toSet()
        if (a.isNullOrEmpty() || a != b) throw IOException(tr("安装包签名和当前应用不同（当前是自行编译的版本？）"))
    }

    private fun unmetered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    private fun reason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> tr("没有网络")
        is java.net.SocketTimeoutException -> tr("连接超时")
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** PackageInstaller's answer: hand the confirmation screen to the user, or report the failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.failed(tr("已取消安装"))
            else -> Updater.failed(
                tr("安装失败（{0}）：", status) + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: tr("未知原因")),
            )
        }
    }
}

/**
 * Orders release versions: "0.1.19-alpha" > "0.1.18-alpha"; for the same
 * numbers, alpha < beta < rc < no suffix, then compare the stage's numeric
 * components (beta < beta2 == beta.2 < beta10). Ignore leading "v" and build metadata.
 */
internal fun compareVersions(a: String, b: String): Int {
    fun split(v: String): Pair<List<Int>, String> {
        val s = v.trim().removePrefix("v").substringBefore('+')
        val dash = s.indexOf('-')
        val num = if (dash < 0) s else s.substring(0, dash)
        val suffix = if (dash < 0) "" else s.substring(dash + 1)
        return num.split('.').map { it.toIntOrNull() ?: 0 } to suffix.lowercase()
    }
    fun rank(s: String) = when {
        s.isEmpty() -> 3
        s.startsWith("rc") -> 2
        s.startsWith("beta") -> 1
        else -> 0
    }
    val (na, sa) = split(a)
    val (nb, sb) = split(b)
    for (i in 0 until maxOf(na.size, nb.size)) {
        val d = na.getOrElse(i) { 0 }.compareTo(nb.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    val stageOrder = rank(sa).compareTo(rank(sb))
    if (stageOrder != 0) return stageOrder
    fun stageNumbers(s: String): List<Int> {
        val match = Regex("^(?:alpha|beta|rc)(?:[.-]?(\\d+(?:\\.\\d+)*))?$").matchEntire(s)
        val numbers = match?.groupValues?.get(1).orEmpty()
        return if (numbers.isEmpty()) emptyList() else numbers.split('.').map { it.toIntOrNull() ?: 0 }
    }
    val qa = stageNumbers(sa)
    val qb = stageNumbers(sb)
    for (i in 0 until maxOf(qa.size, qb.size)) {
        val d = qa.getOrElse(i) { 0 }.compareTo(qb.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}
