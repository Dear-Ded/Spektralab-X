package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-normal-notround-port-night-mdpi-keyshidden-nonav")
class QueueSheetUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restore() { AppLocale.select(compose.activity, UiLanguage.CHINESE) }

    private fun job(id: Long, state: JobState) = ProcessingJob(
        id = id, uri = "file:///synthetic-$id.jpg", name = "synthetic-$id.jpg", raw = false,
        editJson = "{}", wbK = 0.0, wbTint = 0.0, format = ExportFormat.JPEG,
        size = ExportSize.FULL, workingEdge = 2048, maxPixels = 8_388_608L,
        outPixels = 1_000_000L, state = state,
    )

    private fun show(jobs: List<ProcessingJob>, language: UiLanguage = UiLanguage.ENGLISH) {
        AppLocale.select(compose.activity, language)
        compose.setContent { AppLocaleHost { SpektraTheme {
            QueueSheet(jobs, onCancel = {}, onRetry = {}, onClear = {}, onDismiss = {})
        } } }
        compose.waitForIdle()
    }

    @Test fun idleHistoryExplainsRetentionAndNamesCancellation() {
        show(listOf(job(1, JobState.DONE), job(2, JobState.CANCELLED)))
        compose.onNodeWithText("Processing queue").assertIsDisplayed()
        compose.onNodeWithText("2 photos saved", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 photos cancelled · 1 photos saved").assertIsDisplayed()
        compose.onNodeWithText("The latest 30 finished records are kept after restart. Clearing records does not delete saved photos.")
            .assertIsDisplayed()
        compose.onNodeWithText("You can leave the app. Progress is shown in live notifications.").assertDoesNotExist()
    }

    @Test fun activeQueueKeepsBackgroundProgressHint() {
        show(listOf(job(3, JobState.RUNNING)))
        compose.onNodeWithText("You can leave the app. Progress is shown in live notifications.").assertIsDisplayed()
        compose.onNodeWithText("There are no active tasks.").assertDoesNotExist()
    }

    @Test fun finishedRowShowsTheConfirmedFileDetailsAndSpeaksThem() {
        val saved = "Spektralab_DSC_4120.jpg · 6000×4000 · 8.21 MB · JPEG 4:4:4"
        show(listOf(job(4, JobState.DONE).copy(message = saved)), UiLanguage.CHINESE)
        compose.onNodeWithText(saved).assertIsDisplayed()
        compose.onNodeWithText("已保存 · JPEG", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("synthetic-4.jpg，已保存，$saved").assertExists()
    }

    @Test fun finishedRowWithOnlyThePixelsShowsOnlyThePixels() {
        // After a restart the queue may confirm the pixels alone; the row adds no encoder of its own.
        val saved = "Spektralab_DSC_4120.jpg · 6000×4000"
        show(listOf(job(5, JobState.DONE).copy(message = saved)), UiLanguage.CHINESE)
        compose.onNodeWithText(saved).assertIsDisplayed()
        compose.onNodeWithText("4:4:4", substring = true).assertDoesNotExist()
    }

    @Test fun finishedRowWithoutDetailsKeepsTheExportKind() {
        show(listOf(job(6, JobState.DONE)), UiLanguage.CHINESE)
        compose.onNodeWithText("已保存 · JPEG · 工作尺寸").assertIsDisplayed()
    }
}
