package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The editor's update entry (EditorScreen.kt UpdateEntry): it follows Updater.state alone, a background
 * find never opens the sheet by itself, a tap opens it, and closing it leaves the entry and the state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-normal-notround-port-night-mdpi-keyshidden-nonav")
class UpdateEntryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val release = Updater.Release(
        version = "9.9.9-alpha", notes = "- 示例", page = Updater.RELEASES_PAGE,
        apkName = "Spektralab-X-9.9.9-alpha-arm64-v8a.apk", apkUrl = "https://example.invalid/app.apk",
        apkSize = 48L shl 20, sumsUrl = null,
    )

    @Before fun quiet() { Updater.later(); Updater.state.value = Updater.State.Idle }
    @After fun reset() { Updater.later(); Updater.state.value = Updater.State.Idle }

    private fun show(onPhoto: Boolean) {
        compose.setContent { AppLocaleHost { SpektraTheme { Column { UpdateEntry(onPhoto); UpdateDialog() } } } }
        compose.waitForIdle()
    }

    @Test fun nothingWaitingNoEntry() {
        show(onPhoto = true)
        compose.onNodeWithText("有新版本").assertDoesNotExist()
        compose.onNodeWithText("可安装").assertDoesNotExist()
    }

    @Test fun aBackgroundFindShowsTheEntryAndNoSheet() {
        // What a background check does: the state, and the old prompt flag, without a user action.
        Updater.state.value = Updater.State.Available(release)
        Updater.prompt.value = true
        show(onPhoto = false)
        compose.onNodeWithText("有新版本").assertIsDisplayed()
        compose.onNodeWithText("新版本 9.9.9-alpha").assertDoesNotExist()
    }

    @Test fun aTapOpensTheSheetAndClosingKeepsTheEntryAndTheState() {
        val state = Updater.State.Available(release)
        Updater.state.value = state
        show(onPhoto = true)
        compose.onNodeWithText("有新版本").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("新版本 9.9.9-alpha").assertExists()
        compose.runOnIdle { Updater.later() }
        compose.waitForIdle()
        compose.onNodeWithText("新版本 9.9.9-alpha").assertDoesNotExist()
        compose.onNodeWithText("有新版本").assertIsDisplayed()
        assertEquals(state, Updater.state.value)
        // Closed by the user: a later prompt from the background stays quiet again.
        Updater.prompt.value = true
        compose.waitForIdle()
        compose.onNodeWithText("新版本 9.9.9-alpha").assertDoesNotExist()
    }

    @Test fun aDownloadedUpdateReadsInstall() {
        Updater.state.value = Updater.State.Ready(release, File("build/none.apk"))
        show(onPhoto = true)
        compose.onNodeWithText("可安装").assertIsDisplayed()
        compose.onNodeWithText("有新版本").assertDoesNotExist()
    }
}
