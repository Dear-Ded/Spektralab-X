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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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

/** Synthetic curves only; no native engine, bitmaps or personal files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-normal-long-notround-port-night-560dpi-keyshidden-nonav")
class CurveEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var curves by mutableStateOf(CurveSet(rgb = Curve(listOf(
        CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.5), CurvePoint(1.0, 1.0),
    ))))
    private var enabled by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private var includeLuma by mutableStateOf(true)
    private var hostWidth by mutableStateOf(380.dp)
    private lateinit var scroll: ScrollState
    private val drags = mutableListOf<Boolean>()
    private val recedes = mutableListOf<Boolean>()
    private val changes = mutableListOf<CurveSet>()
    private val events = mutableListOf<String>()
    private var previousLanguage = UiLanguage.CHINESE
    private val density get() = compose.activity.resources.displayMetrics.density

    @Before fun useEnglish() {
        previousLanguage = AppLocale.language
        AppLocale.select(compose.activity, UiLanguage.ENGLISH)
    }

    @After fun restoreLanguage() { AppLocale.select(compose.activity, previousLanguage) }

    private fun plot() = compose.onNodeWithTag("curve-editor-plot")
    private fun channel(label: String) = compose.onNodeWithText(label, substring = true)

    private fun show(scrollable: Boolean = false) {
        compose.setContent {
            SpektraTheme {
                CompositionLocalProvider(LocalSliderDrag provides { recedes += it }) {
                    scroll = rememberScrollState()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.width(hostWidth).fillMaxHeight().then(
                            if (scrollable) Modifier.verticalScroll(scroll) else Modifier,
                        )) {
                            if (visible) CurveEditor(curves, image = null, enabled = enabled,
                                includeLuma = includeLuma,
                                onDrag = { drags += it; events += "drag:$it" },
                                onChange = { curves = it; changes += it; events += "change" })
                            if (scrollable) Spacer(Modifier.height(1600.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun startDrag() {
        plot().performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.12f, -height * 0.12f))
        }
        compose.runOnIdle { assertEquals(listOf(true), drags) }
    }

    private fun assertReleased() {
        compose.runOnIdle {
            assertEquals(listOf(true, false), drags)
            assertEquals(drags, recedes)
            assertEquals("drag:true", events.first())
            assertEquals("drag:false", events.last())
            assertTrue(changes.isNotEmpty())
        }
    }

    @Test fun cancellationReleasesBothCallbacksAndKeepsTheLastVisibleCurve() {
        show()
        startDrag()
        val last = curves
        plot().performTouchInput { cancel() }
        assertReleased()
        compose.runOnIdle { assertEquals(last, curves) }
        // A new drag must start normally after cancellation.
        val p = curves.rgb.points[1]
        val inset = 24f * density
        plot().performTouchInput {
            val point = Offset(inset + p.x.toFloat() * (width - 2f * inset),
                inset + (1f - p.y.toFloat()) * (height - 2f * inset))
            down(point)
            moveTo(point + Offset(30f * density, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false, true, false), drags)
            assertEquals(drags, recedes)
        }
    }

    @Test fun cancellationBeforeSlopDoesNotInsertAPoint() {
        show()
        val before = curves
        plot().performTouchInput {
            down(center + Offset(width * 0.25f, 0f))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(before, curves)
            assertTrue(changes.isEmpty())
            assertTrue(drags.isEmpty())
            assertTrue(recedes.isEmpty())
        }
    }

    @Test fun switchingChannelDuringDragReleasesTheOldChannel() {
        show()
        startDrag()
        val editedRgb = curves.rgb
        channel("Red").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertReleased()
        compose.onRoot().performTouchInput { up() }
        channel("Red").assertIsSelected()
        compose.runOnIdle {
            assertEquals(editedRgb, curves.rgb)
            assertTrue(curves.red.isIdentity)
        }
        val inset = 24f * density
        plot().performTouchInput {
            down(Offset(inset, height - inset))
            moveTo(Offset(inset, height - inset - 40f * density))
            up()
        }
        compose.runOnIdle {
            assertEquals(editedRgb, curves.rgb)
            assertFalse(curves.red.isIdentity)
            assertEquals(listOf(true, false, true, false), drags)
            assertEquals(drags, recedes)
        }
    }

    @Test fun removingTheEditorDuringDragAlwaysReleasesInteraction() {
        show()
        startDrag()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertReleased()
        compose.onRoot().performTouchInput { up() }
    }

    @Test fun disablingDuringDragAlwaysReleasesInteraction() {
        show()
        startDrag()
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        assertReleased()
        compose.onRoot().performTouchInput { up() }
    }

    @Test fun resizingDuringDragReleasesTheOldGeometry() {
        show()
        startDrag()
        compose.runOnIdle { hostWidth = 304.dp }
        compose.waitForIdle()
        assertReleased()
        compose.onRoot().performTouchInput { up() }
    }

    @Test fun changingTheChannelListPreservesTheSelectedModelRow() {
        show()
        channel("Blue").performScrollTo().performClick()
        compose.runOnIdle { includeLuma = false }
        channel("Blue").assertIsSelected()
        compose.runOnIdle { includeLuma = true }
        channel("Blue").assertIsSelected()
    }

    @Test fun endpointHitAreasKeepTheFingerOffsetAndEndpointsKeepTheirX() {
        show()
        val inset = 24f * density
        val grabOffset = 12f * density
        val verticalOffset = 8f * density
        plot().performTouchInput {
            val h = height - 2f * inset
            down(Offset(inset - grabOffset, height - inset - verticalOffset))
            moveTo(Offset(inset + 70f * density, height - inset - h * 0.35f - verticalOffset))
            up()
        }
        compose.runOnIdle {
            assertEquals(0.0, curves.rgb.points.first().x, 0.0)
            assertEquals(0.35, curves.rgb.points.first().y, 0.001)
            assertEquals(3, curves.rgb.points.size)
        }
        plot().performTouchInput {
            val h = height - 2f * inset
            down(Offset(width - inset + grabOffset, inset + verticalOffset))
            moveTo(Offset(width - inset - 70f * density, inset + h * 0.3f + verticalOffset))
            up()
        }
        compose.runOnIdle {
            assertEquals(1.0, curves.rgb.points.last().x, 0.0)
            assertEquals(0.7, curves.rgb.points.last().y, 0.001)
            assertEquals(3, curves.rgb.points.size)
            assertEquals(listOf(true, false, true, false), drags)
            assertEquals(drags, recedes)
        }
    }

    @Test fun verticalBackgroundSwipeScrollsTheParentWithoutEditing() {
        show(scrollable = true)
        val before = curves
        plot().performTouchInput {
            val start = center + Offset(width * 0.25f, 0f)
            down(start)
            repeat(8) { moveTo(start + Offset(0f, -height * (it + 1) / 10f)) }
            up()
        }
        compose.runOnIdle {
            assertEquals(before, curves)
            assertTrue(changes.isEmpty())
            assertTrue(drags.isEmpty())
            assertTrue(scroll.value > 0)
        }
    }

    @Test fun regularPlotHeightIs192Dp() {
        show()
        assertEquals(192f, plot().fetchSemanticsNode().boundsInRoot.height / density, 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-normal-notround-port-night-mdpi-keyshidden-nonav")
    fun allFiveEnglishChannelsAreReachableAndResetStaysFixed() {
        hostWidth = 320.dp
        show()
        val reset = compose.onNodeWithText("Reset").fetchSemanticsNode().boundsInRoot
        assertEquals(48f * density, reset.width, 1f)
        assertEquals(48f * density, reset.height, 1f)
        assertEquals(176f, plot().fetchSemanticsNode().boundsInRoot.height / density, 1f)
        for (label in listOf("RGB", "Luma", "Red", "Green", "Blue", "RGB")) {
            channel(label).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            val fixed = compose.onNodeWithText("Reset").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(reset.left, fixed.left, 1f)
            assertEquals(reset.top, fixed.top, 1f)
            assertEquals(reset.width, fixed.width, 1f)
        }
        compose.runOnIdle {
            assertTrue(drags.isEmpty())
            assertTrue(changes.isEmpty())
        }
    }
}
