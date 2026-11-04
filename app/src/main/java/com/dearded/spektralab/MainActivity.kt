package com.dearded.spektralab

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
        super.onCreate(savedInstanceState)
        // The UI is always dark: without this, a phone in light mode draws dark
        // status-bar icons on the dark top bar, where they vanish.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        DisplayColor.attach(this)
        if (savedInstanceState == null) { handleIntent(intent); Updater.onLaunch(this) }
        setContent {
            // Always dark: judging a print against a white UI skews it.
            SpektraTheme { CoachHost { AppScreens(viewModel) }; ImportHost(viewModel) }
        }
    }

    // MIUI/HyperOS kills background apps hard; giving the GPU pool back when
    // asked keeps this process off the low-memory killer's list.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        viewModel.trimMemory(level)
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
        if (type?.startsWith("image/") == true && !isHaldName(uris.first())) viewModel.open(uris.first())
        else ImportRequests.add(uris)
    }

    /** A HaldCLUT comes as a PNG; one whose name says so is imported, not opened. */
    private fun isHaldName(uri: Uri): Boolean =
        (uri.lastPathSegment ?: "").lowercase().let { it.contains("hald") && it.endsWith(".png") }
}
