package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
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

    private fun show(jobs: List<ProcessingJob>) {
        AppLocale.select(compose.activity, UiLanguage.ENGLISH)
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
}
