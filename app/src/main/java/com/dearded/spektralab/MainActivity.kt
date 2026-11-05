package com.dearded.spektralab

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

class MainActivity : ComponentActivity() {
    companion object {
        /** From the processing notification: open the 正在处理 list. */
        const val ACTION_SHOW_QUEUE = "com.dearded.spektralab.SHOW_QUEUE"
    }

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // ui-motion_splash.md 6: the system splash, then LaunchIntro from the same strip.
        // No keep-on-screen condition: the intro, not the splash, waits for the engine.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val intro = savedInstanceState == null && launchIntroWanted(this, intent)
        splash.setOnExitAnimationListener { p ->
            if (intro) {
                val r = android.graphics.Rect()
                if (p.iconView.getGlobalVisibleRect(r)) LaunchIcon.rect = r
                // The AVD's wipe runs 450 ms from iconAnimationStartMillis (0 below API 31: still icon).
                val start = p.iconAnimationStartMillis
                LaunchIcon.wipe = if (start <= 0L) 1f
                    else ((System.currentTimeMillis() - start) / 450f).coerceIn(0f, 1f)
            }
            p.view.animate().alpha(0f).setDuration(120).withEndAction { p.remove() }.start()
        }
        // The UI is always dark: without this, a phone in light mode draws dark
        // status-bar icons on the dark top bar, where they vanish.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // UI2.md 5: HyperOS paints a scrim behind a 3-button nav bar unless told not to;
        // the floating toolbar needs the bar clear behind it.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        DisplayColor.attach(this)
        if (savedInstanceState == null) { handleIntent(intent); Updater.onLaunch(this) }
        setContent {
            // Always dark: judging a print against a white UI skews it.
            SpektraTheme {
                CoachHost { AppScreens(viewModel) }; ImportHost(viewModel)
                // Over everything, until the engine is ready (600 ms to 1.6 s) or a tap skips it.
                var showIntro by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(intro) }
                if (showIntro) {
                    val ui by viewModel.ui.collectAsState()
                    LaunchIntro(ready = ui.ready, onDone = { showIntro = false })
                }
            }
        }
    }

    // MIUI/HyperOS kills background apps hard; giving the GPU pool back when
    // asked keeps this process off the low-memory killer's list.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        viewModel.trimMemory(level)
    }

    // 拖动时请求高刷 (FrameRateBoost.kt): read only, the event goes on to super unchanged.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        FrameRateBoost.observe(this, ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_QUEUE) { viewModel.showQueue(true); return }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                 else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        // A photo opens in the editor; anything else is a style or film to import (StyleImport.kt).
        val type = intent?.type ?: uris.firstOrNull()?.let(contentResolver::getType)
        if (type?.startsWith("image/") == true && !isHaldName(uris.first())) { viewModel.open(uris.first()); viewModel.externalOpens.tryEmit(Unit) }
        else ImportRequests.add(uris)
    }

    /** A HaldCLUT comes as a PNG; one whose name says so is imported, not opened. */
    private fun isHaldName(uri: Uri): Boolean =
        (uri.lastPathSegment ?: "").lowercase().let { it.contains("hald") && it.endsWith(".png") }
}
