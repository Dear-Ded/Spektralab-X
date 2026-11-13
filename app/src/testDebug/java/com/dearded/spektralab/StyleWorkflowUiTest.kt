package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual style UI with synthetic pixels, co-created with GPT (OpenAI). Does not load JNI or private photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-440dpi-keyshidden-nonav")
class StyleWorkflowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var library: StyleImports
    private var state by mutableStateOf(UiState())
    private val catalog = Catalog(listOf(
        Stock("kodak_portra_400", "Portra", false, false, "kodak_supra_endura"),
        Stock("fujifilm_velvia_100", "Velvia", true, false, null)),
        listOf(Stock("kodak_supra_endura", "Supra", false, false, null)))
    private val customFilter = Look(temperature = 12.0, saturation = -20.0, dust = Dust(strength = 20.0))

    @Before fun prepare() {
        // Follow the repository's screenshot harness: step animations and cursor blinking explicitly.
        compose.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(STYLE_STRENGTH_PREFS, 0).edit().clear().commit()
        library = UserLibrary.get(context)
        library.styles.all.value.toList().forEach { library.deleteStyle(it.id) }
        val photo = Bitmap.createBitmap(IntArray(160 * 120) { i ->
            android.graphics.Color.rgb((i % 160) * 255 / 159, (i / 160) * 255 / 119, 128)
        }, 160, 120, Bitmap.Config.ARGB_8888)
        state = UiState(ready = true, catalog = catalog, edit = catalog.normalize(Edit()),
            image = photo, before = photo,
            source = SourceInfo("synthetic.png", 160, 120, 160, 120, "image", "synthetic"))
        saveAsStyle(library, Edit(look = customFilter), "滤镜 B", null, StyleScope.FILTER)
        saveAsStyle(library, Edit(film = "fujifilm_velvia_100", scanFilm = true, glareAmount = 20.0), "胶卷 A", null, StyleScope.FILM)
        File("build/outputs/roborazzi").mkdirs()
    }

    private fun show() = compose.setContent {
        SpektraTheme {
            androidx.compose.material3.Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    StyleStrip(state, true,
                        onEdit = { state = state.copy(edit = catalog.normalize(it)) },
                        onNone = { state = state.copy(edit = state.edit.withoutStyle()) },
                        onPick = { state = state.copy(edit = it.applyTo(state.edit, catalog)!!) })
                }
            }
        }
    }

    private fun tap(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun settle() {
        val main = shadowOf(android.os.Looper.getMainLooper())
        repeat(8) {
            compose.mainClock.advanceTimeBy(200)
            main.idleFor(java.time.Duration.ofMillis(200))
        }
        compose.waitForIdle()
    }
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String, dialog: Boolean = false) {
        settle()
        if (dialog) captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        else compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun filterOnlyThenFilmCombinationAndSavedRecipe() {
        show()
        tap("我的")
        tap("仅滤镜")
        tap("滤镜 B")
        compose.runOnIdle {
            assertFalse(state.edit.noFilm)
            assertEquals("kodak_portra_400", state.edit.film)
            assertEquals(customFilter, state.edit.look)
        }
        tap("移除胶卷")
        compose.runOnIdle { assertTrue(state.edit.noFilm); assertEquals(customFilter, state.edit.look) }
        shot("80_filter_only_393")
        tap("仅胶卷")
        tap("胶卷 A")
        compose.runOnIdle {
            assertFalse(state.edit.noFilm)
            assertTrue(state.edit.scanFilm)
            assertEquals("fujifilm_velvia_100", state.edit.film)
            assertEquals(customFilter, state.edit.look)
            assertEquals(20.0, state.edit.glareAmount, 0.0)
        }
        shot("81_custom_combination_393")
        tap("完整组合")
        tap("+ 存为风格")
        compose.onNodeWithText("例如：傍晚暖调").performTextInput("混搭 C")
        tap("保存")
        compose.waitUntil(15_000) { library.styles.all.value.any { it.name == "混搭 C" } }
        compose.runOnIdle {
            val saved = library.styles.all.value.single { it.name == "混搭 C" }
            assertEquals(StyleScope.COMBINATION, saved.look.scope)
            assertEquals(true, saved.look.engine.scanFilm)
            assertEquals(customFilter, saved.look.layer2)
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-normal-notround-port-night-mdpi-keyshidden-nonav")
    fun narrowFilterAndSaveDialog() {
        state = state.copy(edit = Edit.NONE.copy(look = customFilter))
        show()
        tap("我的")
        tap("仅滤镜")
        shot("82_filter_only_320")
        tap("+ 存为风格")
        compose.onNodeWithText("例如：傍晚暖调").performTextInput("纯滤镜 D")
        // The open edit has no film, so the save choices contain no film-only option.
        shot("83_save_filter_320", dialog = true)
        tap("保存")
        compose.waitUntil(15_000) { library.styles.all.value.any { it.name == "纯滤镜 D" } }
        compose.runOnIdle {
            val saved = library.styles.all.value.single { it.name == "纯滤镜 D" }
            assertEquals(StyleScope.FILTER, saved.look.scope)
            assertTrue(saved.look.engine.empty)
        }
    }

    @Test fun sharedFilesRestoreScopeAndBypassWithTheirOriginalLut() {
        val table = library.luts.newId()
        library.luts.save(table, Lut3.identity(2), SpkLut.SPACE_PROPHOTO)
        val edit = Edit.NONE.copy(look = customFilter.copy(lut = table, lutStrength = .6))
        for (scope in listOf(StyleScope.FILTER, StyleScope.COMBINATION)) {
            val file = library.exportLook(edit, "shared", null, scope)
            val pending = library.read(android.net.Uri.fromFile(file), catalog)
            assertEquals(scope, pending.look.scope)
            assertEquals(SpkLut.SPACE_PROPHOTO, pending.look.lut!!.space)
            assertFalse(pending.lutId.isEmpty())
            val actual = pending.look.toStyle("us_shared", state.edit, pending.lutId).applyTo(state.edit, catalog)!!
            assertEquals(scope == StyleScope.COMBINATION, actual.noFilm)
            assertEquals(.6, actual.look.lutStrength, 0.0)
            assertEquals(customFilter, actual.look.copy(lut = "", lutStrength = 1.0))
            library.discard(pending)
        }
    }
}
