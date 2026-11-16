package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-normal-long-notround-port-night-mdpi-keyshidden-nonav")
class WhiteBalancePickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var oldLanguage = UiLanguage.CHINESE
    private val applied = mutableListOf<WhiteBalancePick>()
    private var dismissals = 0

    @Before fun useEnglish() {
        oldLanguage = AppLocale.language
        AppLocale.select(compose.activity, UiLanguage.ENGLISH)
    }
    @After fun restoreLanguage() { AppLocale.select(compose.activity, oldLanguage) }

    private fun show(level: Float = .4f) {
        val values = FloatArray(2 + 8 * 4 * 6) { level }.also { it[0] = 8f; it[1] = 4f }
        val ref = WhiteBalanceReference.fromNative(values)!!
        compose.setContent { SpektraTheme {
            WhiteBalancePickerDialog(ref, false, Look.NEUTRAL, { dismissals++ }, { applied += it })
        } }
        compose.waitForIdle()
    }

    @Test fun aSampleMustBeConfirmedAndCancelDoesNotApplyIt() {
        show()
        compose.onNodeWithText("Apply white balance").assertIsNotEnabled()
        compose.onNodeWithTag("white-balance-photo").performTouchInput { down(center); up() }
        compose.onNodeWithText("Apply white balance").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, dismissals); assertTrue(applied.isEmpty()) }
    }

    @Test fun confirmCommitsExactlyOneSample() {
        show()
        compose.onNodeWithTag("white-balance-photo").performTouchInput { down(center); up() }
        compose.onNodeWithText("Apply white balance").performClick()
        compose.runOnIdle {
            assertEquals(1, applied.size)
            assertEquals(0.0, applied.single().temperature, 0.0)
            assertEquals(0.0, applied.single().tint, 0.0)
        }
    }

    @Test fun darkSamplesCannotBeApplied() {
        show(.001f)
        compose.onNodeWithTag("white-balance-photo").performTouchInput { down(center); up() }
        compose.onNodeWithText("Apply white balance").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
    }

    @Test fun letterboxTapsDoNotSelectAnImagePixel() {
        show()
        compose.onNodeWithTag("white-balance-photo").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(center.x, 2f)); up()
        }
        compose.onNodeWithText("Apply white balance").assertIsNotEnabled()
    }
}
