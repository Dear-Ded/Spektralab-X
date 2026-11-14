package com.dearded.spektralab

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Real Settings composition, persisted toggle and retained editor state on a narrow display. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-normal-notround-port-night-mdpi-keyshidden-nonav")
class LocalizationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restore() { AppLocale.select(compose.activity, UiLanguage.CHINESE) }

    private fun settle() {
        repeat(8) {
            compose.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        compose.waitForIdle()
    }

    private fun tap(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction()).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun show(): EditorViewModel {
        compose.mainClock.autoAdvance = false
        AppLocale.select(compose.activity, UiLanguage.CHINESE)
        val vm = ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.ui.value.error == null && !vm.ui.value.ready) Thread.sleep(25)
        }
        val field = EditorViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }
        (field.get(vm) as MutableStateFlow<UiState>).value = UiState(ready = true, busy = null,
            edit = Edit(exposureEv = 0.5, quarterTurns = 1),
            source = SourceInfo("设置 {0}.dng", 160, 120, 160, 120, "raw", "synthetic"))
        compose.setContent {
            AppLocaleHost { SpektraTheme {
                val ui by vm.ui.collectAsState()
                androidx.compose.material3.Surface { SettingsScreen(ui, vm, {}) }
            } }
        }
        settle()
        File("build/outputs/roborazzi").mkdirs()
        return vm
    }

    @Test fun settingSwitchesBothWaysAndRetainsTheOpenEdit() {
        val vm = show()
        val before = vm.ui.value
        compose.onNodeWithText("设置").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/90_language_zh_320.png")
        tap("语言 / Language")
        tap("English")
        compose.onNodeWithText("Settings").assertExists()
        compose.onAllNodes(hasText("Language")).onFirst().assertExists()
        assertEquals("en", compose.activity.getSharedPreferences("settings", 0).getString(AppLocale.KEY, null))
        assertEquals(before.edit, vm.ui.value.edit)
        assertEquals(before.source, vm.ui.value.source)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/91_language_en_320.png")
        tap("Language")
        tap("中文")
        compose.onNodeWithText("设置").assertExists()
        assertEquals(before.edit, vm.ui.value.edit)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun benchmarkResultsAreVisibleWithoutCopyingDiagnostics() {
        val vm = show()
        val result = BenchmarkResult(3072, 120, 65, 900, 80, 0.2f, 0.3f,
            BenchmarkAdvice(LivePreviewAdvice.ON, 4096, emptyList()), 1L)
        compose.runOnIdle {
            val field = EditorViewModel::class.java.getDeclaredField("benchmarkResult\$delegate").apply { isAccessible = true }
            (field.get(vm) as MutableState<BenchmarkResult?>).value = result
            AppLocale.select(compose.activity, UiLanguage.ENGLISH)
        }
        settle()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("First preview 120 ms").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Style switch 65 ms").assertExists()
        compose.onNodeWithText(tr(BenchmarkPolicy.DISCLAIMER)).performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/92_benchmark_results_en_320.png")
        assertTrue(vm.benchmarkLines().any { it.contains("not saved to the gallery") })
    }
}
