package com.dearded.spektralab

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cloud-only visual sidecar. Uses synthetic parameter state and no photos or native engine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-440dpi-keyshidden-nonav")
class PrecisionInputVisualProbe {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var value by mutableStateOf(0.0)
    private val drags = mutableListOf<Boolean>()
    private var previousLanguage = UiLanguage.CHINESE

    @Before fun prepare() {
        previousLanguage = AppLocale.language
        assertTrue(AppLocale.select(compose.activity, UiLanguage.CHINESE))
        File(OUTPUT).mkdirs()
    }

    @After fun restoreLanguage() { AppLocale.select(compose.activity, previousLanguage) }

    private fun settleDialog() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        compose.waitForIdle()
    }

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
            SpektraTheme {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    LabeledSlider(tr("曝光"), value, -3.0, 3.0, 0.0, true, "%+.2f EV",
                        onDrag = { drags += it }) { value = it }
                }
            }
        }
    }

    private fun openAndEnter(entry: String) {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(tr("输入{0}", tr("曝光"))).performClick()
        settleDialog()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
            assertTrue("The numeric field must accept the text replacement", it(AnnotatedString(entry)))
        }
        settleDialog()
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertTextEquals(entry)
        compose.runOnIdle {
            assertEquals("Typing must not commit the parameter", 0.0, value, 0.0)
            assertTrue("Typing must not claim a drag", drags.isEmpty())
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) {
        settleDialog()
        captureScreenRoboImage("$OUTPUT/$name.png")
    }

    private fun confirm() {
        compose.onNodeWithText(tr("确定")).performClick()
        settleDialog()
        compose.runOnIdle {
            assertEquals(-1.25, value, 0.0001)
            assertTrue(drags.isEmpty())
        }
    }

    @Test fun validUnicodeNegative() {
        openAndEnter("\u22121.25")
        compose.onNodeWithText(tr("确定")).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(tr("请输入范围内的有效数值")).assertDoesNotExist()
        shot("101_precision_valid")
        confirm()
    }

    @Test fun invalidDecimalComma() {
        openAndEnter("1,5")
        compose.onNodeWithText(tr("请输入范围内的有效数值")).assertIsDisplayed()
        compose.onNodeWithText(tr("确定")).assertIsDisplayed().assertIsNotEnabled()
        shot("102_precision_invalid")
        compose.onNodeWithText(tr("取消")).performClick()
        settleDialog()
        compose.runOnIdle { assertEquals(0.0, value, 0.0) }
    }

    @Test fun englishUnicodeNegative() {
        assertTrue(AppLocale.select(compose.activity, UiLanguage.ENGLISH))
        openAndEnter("\u22121.25")
        compose.onNode(hasText("Exposure") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        shot("103_precision_en")
        confirm()
    }

    companion object { private const val OUTPUT = "build/outputs/roborazzi" }
}
