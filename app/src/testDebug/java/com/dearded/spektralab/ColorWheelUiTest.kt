package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Synthetic Zone state only. No editor, native engine, files or private images. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-normal-long-notround-port-night-560dpi-keyshidden-nonav")
class ColorWheelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var zone by mutableStateOf(Zone(hue = 210.0, sat = 40.0, lum = 17.0))
    private var enabled by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private var hostWidth by mutableStateOf(320.dp)
    private lateinit var scroll: ScrollState
    private val drags = mutableListOf<Boolean>()
    private val recedes = mutableListOf<Boolean>()
    private val changes = mutableListOf<Zone>()
    private val events = mutableListOf<String>()
    private var previousLanguage = UiLanguage.CHINESE

    @Before fun language() {
        previousLanguage = AppLocale.language
        AppLocale.select(compose.activity, UiLanguage.ENGLISH)
    }

    @After fun restoreLanguage() { AppLocale.select(compose.activity, previousLanguage) }

    private val density get() = compose.activity.resources.displayMetrics.density
    private fun wheel() = compose.onNodeWithContentDescription("$LABEL colour wheel")

    private fun show(scrollable: Boolean = false) {
        compose.setContent {
            SpektraTheme {
                CompositionLocalProvider(LocalSliderDrag provides { recedes += it }) {
                    scroll = rememberScrollState()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.width(hostWidth).fillMaxHeight().then(
                            if (scrollable) Modifier.verticalScroll(scroll) else Modifier,
                        )) {
                            if (visible) ColorWheel(LABEL, zone, enabled,
                                onDrag = { drags += it; events += "drag:$it" },
                                onChange = { zone = it; changes += it; events += "change" })
                            if (scrollable) Spacer(Modifier.height(1600.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun coordinatesUseClockwiseScreenHueAndClampSaturation() {
        val center = Offset(100f, 100f)
        val previous = Zone(213.0, 35.0, -27.0)
        listOf(Offset(150f, 100f) to 0.0, Offset(100f, 150f) to 90.0,
            Offset(50f, 100f) to 180.0, Offset(100f, 50f) to 270.0).forEach { (position, hue) ->
            val actual = colorWheelZoneAt(position, center, 50f, previous)
            assertEquals(hue, actual.hue, 0.0001)
            assertEquals(100.0, actual.sat, 0.0001)
            assertEquals(previous.lum, actual.lum, 0.0)
            val marker = colorWheelThumbAt(actual, center, 50f)
            assertEquals(position.x, marker.x, 0.001f)
            assertEquals(position.y, marker.y, 0.001f)
        }
        assertEquals(100.0, colorWheelZoneAt(Offset(300f, 100f), center, 50f, previous).sat, 0.0)
        assertEquals(previous.copy(sat = 0.0), colorWheelZoneAt(center, center, 50f, previous))
        assertEquals(previous, colorWheelZoneAt(center, center, 0f, previous))
    }

    @Test fun drawnSpectrumMatchesTheCoordinateDirections() {
        show()
        val output = File(compose.activity.cacheDir, "colour-wheel-spectrum.png")
        wheel().captureRoboImage(output.absolutePath)
        val pixels = BitmapFactory.decodeFile(output.absolutePath).asImageBitmap().toPixelMap()
        val cx = pixels.width / 2f
        val cy = pixels.height / 2f
        val r = (minOf(pixels.width, pixels.height) / 2f - 12f * density) * 0.78f
        val right = pixels[(cx + r).roundToInt(), cy.roundToInt()]
        val down = pixels[cx.roundToInt(), (cy + r).roundToInt()]
        val left = pixels[(cx - r).roundToInt(), cy.roundToInt()]
        val up = pixels[cx.roundToInt(), (cy - r).roundToInt()]
        assertTrue("Right must be red", right.red > right.green && right.red > right.blue)
        assertTrue("Down must be yellow-green", down.red > down.blue && down.green > down.blue)
        assertTrue("Left must be cyan", left.green > left.red && left.blue > left.red)
        assertTrue("Up must be purple", up.red > up.green && up.blue > up.green)
    }

    @Test fun draggingOutsideTheCircleClampsAndBalancesBothCallbacks() {
        show()
        val inset = 12f * density
        wheel().performTouchInput {
            val r = width / 2f - inset
            down(center + Offset(r * 0.5f, 0f))
            moveTo(center + Offset(r * 1.25f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), drags)
            assertEquals(listOf(true, false), recedes)
            assertEquals("drag:true", events.first())
            assertEquals("drag:false", events.last())
            assertTrue(changes.isNotEmpty())
            assertEquals(0.0, zone.hue, 0.001)
            assertEquals(100.0, zone.sat, 0.001)
            assertEquals(17.0, zone.lum, 0.0)
        }
    }

    @Test fun verticalBackgroundSwipeScrollsWithoutChangingTheZone() {
        show(scrollable = true)
        val before = zone
        val inset = 12f * density
        wheel().performTouchInput {
            val r = width / 2f - inset
            val start = center + Offset(r * 0.7f, 0f)
            down(start)
            repeat(8) { moveTo(start + Offset(0f, -r * (it + 1) / 5f)) }
            up()
        }
        compose.runOnIdle {
            assertEquals(before, zone)
            assertTrue(changes.isEmpty())
            assertTrue(drags.isEmpty())
            assertTrue("Parent should receive the vertical swipe", scroll.value > 0)
        }
    }

    @Test fun verticalDragOnTheHandleEditsInsteadOfScrolling() {
        zone = Zone(0.0, 50.0, 17.0)
        show(scrollable = true)
        val inset = 12f * density
        wheel().performTouchInput {
            val r = width / 2f - inset
            val handle = center + Offset(r * 0.5f, 0f)
            down(handle)
            moveTo(handle + Offset(0f, r * 0.5f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), drags)
            assertEquals(0, scroll.value)
            assertEquals(45.0, zone.hue, 0.01)
            assertEquals(17.0, zone.lum, 0.0)
        }
    }

    @Test fun gestureStartingOutsideTheCircleDoesNotEditWhenItEnters() {
        show()
        val before = zone
        wheel().performTouchInput {
            down(Offset(1f, 1f))
            moveTo(center + Offset(width * 0.25f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(before, zone)
            assertTrue(changes.isEmpty())
            assertTrue(drags.isEmpty())
        }
    }

    @Test fun doubleTapOnlyResetsSaturationAndSingleTapDoesNotJump() {
        show()
        val before = zone
        wheel().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(before, zone) }
        wheel().performTouchInput { advanceEventTime(80); click(center) }
        compose.runOnIdle {
            assertEquals(before.copy(sat = 0.0), zone)
            assertEquals(listOf(true, false), drags)
            assertEquals(listOf(true, false), recedes)
            assertEquals(1, changes.size)
        }
    }

    @Test fun touchResetAndAccessibilityResetPreserveHueAndLuminance() {
        show()
        val before = zone
        compose.onNodeWithContentDescription("Reset $LABEL saturation").performClick()
        compose.runOnIdle { assertEquals(before.copy(sat = 0.0), zone); zone = before }
        val actions = wheel().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == "Reset" }.action()) }
        compose.runOnIdle {
            assertEquals(before.copy(sat = 0.0), zone)
            assertEquals(listOf(true, false, true, false), drags)
            assertEquals(drags, recedes)
        }
    }

    @Test fun accessibilityCanAdjustSaturationAndHueWithoutChangingLuminance() {
        zone = Zone(359.0, 40.0, -12.0)
        show()
        wheel().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(75f)) }
        val actions = wheel().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == "Increase hue" }.action()) }
        compose.runOnIdle {
            assertEquals(Zone(0.0, 75.0, -12.0), zone)
            assertEquals(listOf(true, false, true, false), drags)
            assertEquals(drags, recedes)
        }
    }

    private fun startDrag() {
        val inset = 12f * density
        wheel().performTouchInput {
            val r = width / 2f - inset
            down(center + Offset(r * 0.4f, 0f))
            moveTo(center + Offset(r * 0.8f, 0f))
        }
        compose.runOnIdle { assertEquals(listOf(true), drags) }
    }

    @Test fun pointerCancellationAlwaysReleasesInteraction() {
        show()
        startDrag()
        wheel().performTouchInput { cancel() }
        compose.runOnIdle {
            assertEquals(listOf(true, false), drags)
            assertEquals(drags, recedes)
        }
    }

    @Test fun resizingDuringDragReleasesTheOldGeometry() {
        show()
        startDrag()
        compose.runOnIdle { hostWidth = 160.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(true, false), drags)
            assertEquals(drags, recedes)
        }
        compose.onRoot().performTouchInput { up() }
    }

    @Test fun disablingDuringDragAlwaysReleasesInteraction() {
        show()
        startDrag()
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(true, false), drags)
            assertEquals(drags, recedes)
        }
        compose.onRoot().performTouchInput { up() }
        wheel().assertIsNotEnabled()
        assertFalse(wheel().fetchSemanticsNode().config.contains(SemanticsActions.SetProgress))
    }

    @Test fun removingDuringDragAlwaysReleasesInteraction() {
        show()
        startDrag()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(true, false), drags)
            assertEquals(drags, recedes)
        }
        compose.onRoot().performTouchInput { up() }
    }

    @Test fun disabledWheelDoesNotChangeOrReportInteraction() {
        enabled = false
        show()
        val before = zone
        wheel().assertIsNotEnabled().performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.25f, 0f))
            up()
            advanceEventTime(80)
            click(center)
            advanceEventTime(80)
            click(center)
        }
        compose.runOnIdle {
            assertEquals(before, zone)
            assertTrue(changes.isEmpty())
            assertTrue(drags.isEmpty())
            assertTrue(recedes.isEmpty())
        }
    }

    @Test fun defaultDiameterIs192DpAndNarrowContainerDoesNotOverflow() {
        show()
        val bounds = wheel().fetchSemanticsNode().boundsInRoot
        assertEquals(192f, bounds.width / density - 24f, 1f)
        compose.runOnIdle { hostWidth = 160.dp }
        val narrow = wheel().fetchSemanticsNode().boundsInRoot
        assertTrue(narrow.width <= 160f * density + 1f)
        assertEquals(narrow.width, narrow.height, 1f)
        compose.onNodeWithText(LABEL).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-normal-notround-port-night-mdpi-keyshidden-nonav")
    fun fitsThe320DpViewport() {
        show()
        wheel().assertIsDisplayed()
        compose.onNodeWithContentDescription("Reset $LABEL saturation").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w873dp-h393dp-normal-long-notround-land-night-mdpi-keyshidden-nonav")
    fun fitsTheLandscapePanelWithoutShrinkingTouchControls() {
        hostWidth = 304.dp
        show()
        wheel().assertIsDisplayed()
        val reset = compose.onNodeWithContentDescription("Reset $LABEL saturation").fetchSemanticsNode().boundsInRoot
        assertTrue(reset.height >= 48f * density)
    }

    companion object { private const val LABEL = "Shadows" }
}
