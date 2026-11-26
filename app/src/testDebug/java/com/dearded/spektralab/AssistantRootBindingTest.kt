package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantRootBindingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val editor = EditorViewModel(ApplicationProvider.getApplicationContext<SpektraLabApp>(), false)
        .also { store.put("editor", it) }
    private var session: AssistantPort? = null
    private var settings: AssistantSettingsPort? = null

    private fun mount(allowed: Boolean) {
        assistantBuildAllowed = allowed
        compose.setContent {
            SpektraTheme {
                ProvideDarkroomAssistant(editor) {
                    val liveSession = LocalAssistant.current
                    val liveSettings = LocalAssistantSettings.current
                    SideEffect { session = liveSession; settings = liveSettings }
                }
            }
        }
    }

    @After fun close() {
        assistantBuildAllowed = BuildConfig.DEBUG
        compose.runOnIdle { store.clear() }
    }

    @Test fun disabledGateSuppliesNeitherPort() {
        mount(allowed = false)
        compose.runOnIdle {
            assertNull(session)
            assertNull(settings)
        }
    }

    @Test fun debugRootSuppliesLivePortsAndOpensTheExistingSetupSheet() {
        mount(allowed = true)
        compose.runOnIdle {
            assertTrue(session is AssistantSessionPort)
            assertTrue(settings is AssistantRuntimeSettingsPort)
            assertSame(editor.assistantPreferences.preferences, checkNotNull(settings).preferences)
            checkNotNull(session).openSettings()
        }
        compose.onNodeWithText(tr("暗房助手")).assertExists()
    }
}
