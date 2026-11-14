package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pointer events must select the touched cell without opening the loupe. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-440dpi-keyshidden-nonav")
class LightTableGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun show(): Pair<LightTableViewModel, () -> Int> {
        AppLocale.select(compose.activity, UiLanguage.CHINESE)
        val store = FrameStore.of(compose.activity)
        FrameStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(store, true)
        val frames = (0..8).map { i ->
            Frame("gesture-$i", "file:///gesture-$i.jpg", "gesture-$i.jpg", 100, 0,
                "image/jpeg", false, 160, 100)
        }
        val flow = FrameStore::class.java.getDeclaredField("_frames").apply { isAccessible = true }.get(store)
        (flow as MutableStateFlow<List<Frame>>).value = frames
        val table = ViewModelProvider(compose.activity)[LightTableViewModel::class.java]
        var opened = 0
        compose.setContent {
            AppLocaleHost { SpektraTheme {
                LightTableScreen(table, onBack = {}, onOpen = { _, _ -> opened++ }, onRolls = {})
            } }
        }
        compose.waitForIdle()
        return table to { opened }
    }

    @Test fun longPressFirstRowSelectsTouchedFrame() {
        val (table, opened) = show()
        compose.onNodeWithContentDescription("gesture-2.jpg").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(setOf("gesture-2"), table.state.value.selection)
        assertEquals(0, opened())
    }

    @Test fun longPressSecondRowSelectsTouchedFrame() {
        val (table, opened) = show()
        compose.onNodeWithContentDescription("gesture-3.jpg").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(setOf("gesture-3"), table.state.value.selection)
        assertEquals(0, opened())
    }

    @Test fun longPressNearBottomKeepsTheTouchedRow() {
        val (table, opened) = show()
        compose.onNodeWithContentDescription("gesture-2.jpg").performTouchInput {
            longClick(Offset(centerX, height * 0.9f))
        }
        compose.waitForIdle()
        assertEquals(setOf("gesture-2"), table.state.value.selection)
        assertEquals(0, opened())
    }

    @Test fun longPressDragSelectsAcrossRows() {
        val (table, opened) = show()
        val start = compose.onNodeWithContentDescription("gesture-2.jpg").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithContentDescription("gesture-3.jpg").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            advanceEventTime(800)
            repeat(12) { i -> moveTo(start + (end - start) * ((i + 1) / 12f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(setOf("gesture-2", "gesture-3"), table.state.value.selection)
        assertEquals(0, opened())
    }

    @Test fun shortTapStillOpensThePhoto() {
        val (table, opened) = show()
        compose.onNodeWithContentDescription("gesture-2.jpg").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(emptySet<String>(), table.state.value.selection)
        assertEquals(1, opened())
    }

    @Test fun nextTapCanClearSelectionThenOpenThePhoto() {
        val (table, opened) = show()
        compose.onNodeWithContentDescription("gesture-2.jpg").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(setOf("gesture-2"), table.state.value.selection)
        compose.onNodeWithContentDescription("gesture-2.jpg").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(emptySet<String>(), table.state.value.selection)
        assertEquals(0, opened())
        compose.onNodeWithContentDescription("gesture-2.jpg").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, opened())
    }
}
