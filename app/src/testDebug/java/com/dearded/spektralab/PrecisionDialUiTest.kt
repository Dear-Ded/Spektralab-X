package com.dearded.spektralab

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Gesture contracts for the ruler shared by photo and engine parameters. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-port-night-mdpi")
class PrecisionDialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var value by mutableStateOf(0.0)
    private val drag = mutableListOf<Boolean>()

    private fun settleDialog() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
        compose.waitForIdle()
    }

    private fun show() = compose.setContent {
        // Cursor blinking uses coroutine delays, independently of the Compose test clock.
        CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
            SpektraTheme {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    LabeledSlider("曝光", value, -3.0, 3.0, 0.0, true, "%+.2f EV",
                        onDrag = { drag += it }) { value = it }
                }
            }
        }
    }

    @Test fun movingTheRulerLeftIncreasesTheValueAndDoubleTapResets() {
        show()
        compose.onNodeWithContentDescription("曝光").performTouchInput {
            swipe(center + Offset(60f, 0f), center - Offset(80f, 0f), 400)
        }
        compose.runOnIdle {
            assertTrue("Dragging the scale left increases exposure", value > 0.0)
            assertEquals(listOf(true, false), drag)
        }
        compose.onNodeWithContentDescription("曝光").performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(0.0, value, 0.0001) }
    }

    @Test fun cancellationAlwaysReleasesTheEngineInteraction() {
        show()
        compose.onNodeWithContentDescription("曝光").performTouchInput {
            down(center)
            moveTo(center - Offset(60f, 0f))
            moveTo(center - Offset(100f, 0f))
            cancel()
        }
        compose.runOnIdle { assertEquals(listOf(true, false), drag) }
    }

    @Test fun aTapCanReachOneHundredthEv() {
        show()
        compose.onNodeWithContentDescription("曝光").performTouchInput { click(center + Offset(60f, 0f)) }
        compose.runOnIdle { assertEquals(0.01, value, 0.00001) }
    }

    @Test fun accessibilityPreservesTheRange() {
        show()
        compose.onNodeWithContentDescription("曝光").performSemanticsAction(SemanticsActions.SetProgress) { it(99f) }
        compose.runOnIdle { assertEquals(3.0, value, 0.0001) }
    }

    private fun acceptedEntry(entry: String) {
        // Advance dialog transitions explicitly on the Compose and Android clocks.
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("输入曝光").performClick()
        settleDialog()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(entry)) }
        settleDialog()
        compose.onNodeWithText("确定").performClick()
        settleDialog()
        compose.runOnIdle {
            assertEquals(-1.25, value, 0.0001)
            assertTrue("Discrete changes do not claim a drag", drag.isEmpty())
        }
    }

    @Test fun asciiMinusIsAcceptedWithoutClaimingADrag() = acceptedEntry("-1.25")

    @Test fun unicodeMinusIsAcceptedWithoutClaimingADrag() = acceptedEntry("\u22121.25")

    private fun rejectedEntry(entry: String) {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("输入曝光").performClick()
        settleDialog()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(entry)) }
        settleDialog()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0.0, value, 0.0) }
        compose.onNodeWithText("取消").performClick()
        settleDialog()
        compose.runOnIdle { assertEquals(0.0, value, 0.0) }
    }

    @Test fun decimalCommaIsRejectedWithoutChangingTheNumber() = rejectedEntry("1,5")

    @Test fun outOfRangeEntryIsRejectedWithoutChangingTheNumber() = rejectedEntry("9")

    @Test fun invalidEntryCanBeCorrectedWithoutReopeningTheDialog() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("输入曝光").performClick()
        settleDialog()
        for (entry in listOf("1,5", "9")) {
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(entry)) }
            settleDialog()
            compose.onNodeWithText("确定").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0.0, value, 0.0) }
        }
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("\u22121.25")) }
        settleDialog()
        compose.onNodeWithText("确定").performClick()
        settleDialog()
        compose.runOnIdle {
            assertEquals(-1.25, value, 0.0001)
            assertTrue(drag.isEmpty())
        }
    }
}
