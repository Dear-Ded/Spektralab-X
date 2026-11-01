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
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            // Always dark: judging a print against a white UI skews it.
            MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(viewModel) }
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
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let(viewModel::open)
    }
}
