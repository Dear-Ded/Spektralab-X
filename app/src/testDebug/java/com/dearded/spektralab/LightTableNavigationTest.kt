package com.dearded.spektralab

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the real editor/table navigation and both nested transition layouts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-440dpi-keyshidden-nonav")
class LightTableNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restore() { AppLocale.select(compose.activity, UiLanguage.CHINESE) }

    @Suppress("UNCHECKED_CAST")
    private fun enter(language: UiLanguage): LightTableViewModel {
        AppLocale.select(compose.activity, language)
        val store = FrameStore.of(compose.activity)
        FrameStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(store, true)
        val frames = (0 until 12).map { i ->
            Frame("nav-$i", "file:///nav-$i.jpg", "nav-$i.jpg", 100, 0, "image/jpeg", false, 160, 100)
        }
        (FrameStore::class.java.getDeclaredField("_frames").apply { isAccessible = true }.get(store)
            as MutableStateFlow<List<Frame>>).value = frames
        val editor = ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (editor.ui.value.error == null && !editor.ui.value.ready) Thread.sleep(10)
        }
        (EditorViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(editor)
            as MutableStateFlow<UiState>).value = UiState(ready = true, busy = null)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppLocaleHost { SpektraTheme { AppScreens(editor) } } }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText(tr("观片台")).performClick()
        // Navigation awaits the asynchronous sidecar flush before composing the grid.
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription("nav-2.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        return ViewModelProvider(compose.activity)[LightTableViewModel::class.java]
    }

    private fun selectWhileEntering(language: UiLanguage) {
        val table = enter(language)
        val start = compose.onNodeWithContentDescription("nav-2.jpg").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            advanceEventTime(800)
            moveTo(start)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(setOf("nav-2"), table.state.value.selection)
        val end = compose.onNodeWithContentDescription("nav-3.jpg").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(end); up() }
        compose.waitForIdle()
        assertEquals(setOf("nav-2", "nav-3"), table.state.value.selection)
        assertEquals(null, table.loupe.value)
    }

    @Test fun firstChineseCrossRowSelectionSurvivesNavigation() = selectWhileEntering(UiLanguage.CHINESE)

    @Test
    @Config(qualifiers = "w320dp-h640dp-normal-notround-port-night-mdpi-keyshidden-nonav")
    fun firstEnglishCrossRowSelectionSurvivesNarrowNavigation() = selectWhileEntering(UiLanguage.ENGLISH)
}
