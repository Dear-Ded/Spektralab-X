package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-port-night-440dpi")
class FilmPackageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val films = listOf(
        Stock("kodak_portra_400", "Kodak Portra 400", false, false, null),
        Stock("kodak_ektar_100", "Kodak Ektar 100", false, false, null),
        Stock("kodak_tri_x_400", "Kodak Tri-X 400", false, true, null),
        Stock("fujifilm_velvia_100", "Fujifilm Velvia 100", true, false, null),
        Stock("fujifilm_provia_100f", "Fujifilm Provia 100F", true, false, null),
        Stock("cinestill_800t", "CineStill 800T", false, false, null),
        Stock("ilford_hp5_plus_400", "Ilford HP5 Plus 400", false, true, null),
    )

    @Test fun sevenDistinctPackagesAtActualSize() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SpektraTheme {
                Column(Modifier.fillMaxSize().background(Palette.Background).padding(12.dp)) {
                    films.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { film -> FilmPackageCard(film, true, null, onClick = {}) }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        File("build/outputs/roborazzi").mkdirs()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/film_packages_seven_materials.png")
    }

    @Test fun aNewSelectionCanInterruptExtraction() {
        compose.mainClock.autoAdvance = false
        var selected by mutableIntStateOf(0)
        var callbacks = 0
        compose.setContent { SpektraTheme {
            Row { films.take(2).forEachIndexed { i, film ->
                FilmPackageCard(film, selected == i, null, onClick = { selected = i; callbacks++ })
            } }
        } }
        repeat(6) { i ->
            compose.onNodeWithContentDescription("${films[(i + 1) % 2].label}, C-41").performClick()
            compose.mainClock.advanceTimeBy(32)
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithContentDescription("Kodak Portra 400, C-41").assertIsSelected()
        compose.onNodeWithContentDescription("Kodak Ektar 100, C-41").assertIsNotSelected()
        assertEquals(6, callbacks)
    }

    @Test fun releaseBeforeNineHundredMillisecondsDoesNotCommit() {
        compose.mainClock.autoAdvance = false
        var commits = 0
        compose.setContent { SpektraTheme {
            HoldCommitButton("Reset", Modifier.testTag("hold")) { commits++ }
        } }
        compose.onNodeWithTag("hold").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("hold").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(0, commits)
        compose.onNodeWithTag("hold").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hold").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, commits)
    }

    @Test fun packagingDoesNotInventPushSpeedOrCinemaRollLength() {
        val pushed = filmPackageSpec(Stock("kodak_portra_800_push2", "Kodak Portra 800 +2", false, false, null))
        assertEquals("800/30\u00b0", pushed.sensitivity)
        assertEquals("800", pushed.speed)
        val cinema = filmPackageSpec(Stock("kodak_vision3_500t", "Kodak Vision3 500T", false, false, null))
        assertEquals("ECN-2", cinema.process)
        assertFalse(cinema.retail36)
        assertEquals("C-41", filmPackageSpec(films[5]).process)
    }
}
