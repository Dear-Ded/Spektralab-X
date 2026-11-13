package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Compose request/lifecycle contract. Synthetic renderer, no device/GPU acceptance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-440dpi-keyshidden-nonav")
class SelectionPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val catalog = Catalog(listOf(Stock("kodak_portra_400", "Portra", false, false, "kodak_supra_endura")),
        listOf(Stock("kodak_supra_endura", "Supra", false, false, null)))
    private var state by mutableStateOf(UiState())
    private val requests = mutableListOf<Edit>()

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(STYLE_STRENGTH_PREFS, 0).edit().clear().commit()
        UserLibrary.get(context).styles.all.value.toList().forEach { UserLibrary.get(context).deleteStyle(it.id) }
        state = UiState(ready = true, busy = null, catalog = catalog, edit = Edit.NONE, previewSource = 1,
            source = SourceInfo("synthetic.png", 160, 120, 160, 120, "image", "synthetic"))
    }

    @Test fun changingRecipeClearsTheOldImageAndCancelledWorkCannotPublish() {
        val first = CompletableDeferred<Bitmap?>()
        val second = CompletableDeferred<Bitmap?>()
        val third = CompletableDeferred<Bitmap?>()
        var edit by mutableStateOf(Edit.NONE)
        compose.setContent {
            val image = rememberSelectionPreview(state, edit) {
                requests += it
                when (it.look.contrast) { 0.0 -> first.await(); 12.0 -> second.await(); else -> third.await() }
            }
            Text(image?.width?.toString() ?: "waiting")
        }
        compose.waitForIdle()
        assertEquals(1, requests.size)
        compose.runOnIdle { first.complete(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
        compose.onNodeWithText("1").assertExists()
        compose.runOnIdle { edit = edit.copy(look = Look(contrast = 12.0)) }
        compose.waitForIdle()
        assertEquals(2, requests.size)
        compose.onNodeWithText("waiting").assertExists()
        compose.runOnIdle { edit = edit.copy(look = Look(contrast = 24.0)) }
        compose.waitForIdle()
        assertEquals(3, requests.size)
        compose.runOnIdle { second.complete(Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)) }
        compose.onNodeWithText("waiting").assertExists()
        compose.runOnIdle { third.complete(Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)) }
        compose.onNodeWithText("3").assertExists()
    }

    @Test fun reopeningIdenticalSourceMetadataStillRequestsNewPixels() {
        compose.setContent {
            val image = rememberSelectionPreview(state, state.edit) {
                requests += it
                Bitmap.createBitmap(state.previewSource.toInt(), 1, Bitmap.Config.ARGB_8888)
            }
            Text(image?.width?.toString() ?: "waiting")
        }
        compose.onNodeWithText("1").assertExists()
        compose.runOnIdle { state = state.copy(previewSource = 2) }
        compose.onNodeWithText("2").assertExists()
        assertEquals(2, requests.size)
    }

    @Test fun styleCardRequestsTheSameFullRecipeAsItsTapIncludingStoredStrength() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(STYLE_STRENGTH_PREFS, 0).edit().putFloat("cam_skin", .4f).commit()
        var picked: Edit? = null
        compose.setContent {
            SpektraTheme { Column(Modifier.padding(20.dp)) {
                StyleStrip(state, true, preview = { requests += it; null }) { picked = it.applyTo(state.edit, catalog) }
            } }
        }
        compose.onNodeWithText("相机").performClick()
        compose.waitForIdle()
        // A LazyRow only composes the first visible camera style in this small catalogue.
        compose.onNodeWithText("暖肤人像").performClick()
        compose.runOnIdle {
            val expected = Style.ALL.first { it.id == "cam_skin" }.atStrength(.4f.toDouble()).applyTo(state.edit, catalog)!!
            assertEquals(expected, picked)
            assertTrue(requests.contains(expected.forSelectionPreview()))
        }
    }

    @Test fun filmNonePreviewKeepsTheCurrentFinishAndUsesTheTapRecipe() {
        state = state.copy(edit = Edit(look = Look(temperature = 18.0, lut = "lut_test", lutStrength = .7)))
        compose.setContent { SpektraTheme { Column {
            FilmStrip(state, true, preview = { requests += it; null }) {}
        } } }
        compose.waitForIdle()
        assertTrue(requests.contains(state.edit.copy(noFilm = true).forSelectionPreview()))
        assertTrue(requests.contains(EditorViewModel.withFilm(state.edit, catalog, "kodak_portra_400")!!.forSelectionPreview()))
        assertTrue(requests.all { it.look == state.edit.look })
    }

    @Test fun paperNonePreviewShowsTheIntermediateWithCurrentFinish() {
        state = state.copy(edit = Edit(look = Look(temperature = -12.0)))
        compose.setContent { SpektraTheme { PaperStrip(state, true, preview = { requests += it; null }) {} } }
        compose.waitForIdle()
        assertEquals(listOf(state.edit.copy(scanFilm = false, digitalIntermediate = true).forSelectionPreview()), requests)
    }

    @Test fun existingCardLayoutDisplaysDistinctPreviewImages() {
        compose.setContent { SpektraTheme { androidx.compose.material3.Surface(color = Palette.Background) {
            Column(Modifier.padding(20.dp)) {
            StyleStrip(state, true, preview = { edit ->
                requests += edit
                // Deliberately distinct synthetic fixtures test presentation, not colour science.
                Bitmap.createBitmap(IntArray(160 * 120) { i -> android.graphics.Color.rgb(
                    (i % 160) * 255 / 159, (i / 160) * 255 / 119,
                    if (edit.noFilm) 128 else (edit.look.temperature + 100).toInt().coerceIn(0, 255)) },
                    160, 120, Bitmap.Config.ARGB_8888)
            }) {}
        } } } }
        compose.waitForIdle()
        File("build/outputs/roborazzi").mkdirs()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/selection-preview-393.png")
        assertTrue(requests.isNotEmpty())
    }
}
