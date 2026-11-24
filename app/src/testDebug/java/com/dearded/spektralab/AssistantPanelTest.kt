package com.dearded.spektralab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The darkroom assistant's UI contract (Assistant.kt) on a fake session: what each state shows and
 * which action each control asks for. No model, no network; the real session is codex's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-440dpi-keyshidden-nonav")
class AssistantPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var closed = false

    // System animations off: the light is still, so the test clock can go idle (and that path is covered).
    @Before fun internalBuild() {
        assistantBuildAllowed = true; FilmDevelop.cpuOnly = true
        animatorScale(0f)
    }
    @After fun restore() {
        assistantBuildAllowed = BuildConfig.DEBUG
        animatorScale(1f)
    }

    /** ValueAnimator's duration scale (hidden API; Robolectric's shadow takes the call). */
    private fun animatorScale(scale: Float) {
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun panel(fake: AssistantFake) = compose.setContent {
        SpektraTheme { Column(Modifier.padding(20.dp)) { AssistantPanel(fake, enabled = true) { closed = true } } }
    }

    private fun entry(port: AssistantPort?) = compose.setContent {
        SpektraTheme { CompositionLocalProvider(LocalAssistant provides port) { assistantEntry(true) {}?.invoke() } }
    }

    @Test fun publicBuildHasNoEntry() {
        assistantBuildAllowed = false
        entry(AssistantFake(AssistantFake.configured))
        assertTrue(compose.onAllNodes(hasText("暗房助手")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun noSessionNoEntry() {
        entry(null)
        assertTrue(compose.onAllNodes(hasText("暗房助手")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun internalBuildWithSessionHasEntry() {
        entry(AssistantFake(AssistantFake.configured))
        compose.onNodeWithText("暗房助手").assertIsEnabled()
    }

    @Test fun unconfiguredOffersFixedTrialsAndSetup() {
        val fake = AssistantFake(AssistantView(configured = false))
        panel(fake)
        compose.onNodeWithText("提亮").performClick()
        compose.onNodeWithText("配置服务").performClick()
        assertTrue(compose.onAllNodes(hasText("生成草稿")).fetchSemanticsNodes().isEmpty())
        assertEquals(listOf("open", "preset:Brighten", "settings"), fake.calls)
    }

    @Test fun nothingIsSentUntilTheUserSubmits() {
        val fake = AssistantFake(AssistantFake.configured)
        panel(fake)
        // Before sending, where a request would go.
        compose.onNodeWithText("Anthropic · claude-sonnet-5-5").assertExists()
        compose.onNodeWithText("生成草稿").assertIsNotEnabled()
        compose.onNodeWithContentDescription("修图目标").performTextInput("暖一点")
        assertEquals(listOf("open"), fake.calls)
        compose.onNodeWithText("生成草稿").performClick()
        assertEquals("generate:暖一点", fake.calls.last())
    }

    @Test fun statisticsAreOffAndOnlyForThisRound() {
        val fake = AssistantFake(AssistantFake.configured)
        panel(fake)
        compose.onNodeWithText("电影光影").assertIsNotEnabled()
        compose.onNodeWithText("画面统计").performClick()
        assertEquals("statistics:true", fake.calls.last())
        fake.view.value = fake.view.value.copy(cinematicReady = true)
        compose.onNodeWithText("电影光影").performClick()
        assertEquals("cinematic", fake.calls.last())
    }

    @Test fun generatingCanAlwaysBeCancelled() {
        val fake = AssistantFake(AssistantFake.configured.copy(phase = AssistantPhase.Generating))
        panel(fake)
        compose.onNodeWithText("正在生成").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals("cancel", fake.calls.last())
    }

    @Test fun draftComparesAppliesAndDiscards() {
        val fake = AssistantFake(AssistantFake.draft)
        panel(fake)
        compose.onNodeWithText("当前").performClick()
        assertEquals("showCurrent:true", fake.calls.last())
        compose.onNodeWithText("建议").performClick()
        assertEquals("showCurrent:false", fake.calls.last())
        compose.onAllNodes(hasText("应用") and hasClickAction()).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("应用").performClick()
        assertEquals("apply", fake.calls.last())
        compose.onNodeWithText("放弃").performClick()
        assertEquals("discard", fake.calls.last())
    }

    @Test fun applyWaitsForTheBackend() {
        val fake = AssistantFake(AssistantFake.draft.copy(canApply = false))
        panel(fake)
        compose.onNodeWithText("应用").assertIsNotEnabled()
    }

    @Test fun appliedUndoesInOneStepAndRetriesAFailedSave() {
        val fake = AssistantFake(AssistantFake.configured.copy(phase = AssistantPhase.Applied, error = AssistantError.Save))
        panel(fake)
        compose.onNodeWithText("已应用，保存失败").assertExists()
        compose.onNodeWithText("重试").performClick()
        assertEquals("retrySave", fake.calls.last())
        compose.onNodeWithText("撤销").performClick()
        assertEquals("undo", fake.calls.last())
    }

    @Test fun aFailedRequestShowsTheRealStateAndRetries() {
        val fake = AssistantFake(AssistantFake.configured)
        panel(fake)
        compose.onNodeWithContentDescription("修图目标").performTextInput("压暗高光")
        fake.view.value = fake.view.value.copy(error = AssistantError.Timeout)
        compose.onNodeWithText("请求超时").assertExists()
        compose.onNodeWithText("重试").performClick()
        assertEquals("generate:压暗高光", fake.calls.last())
    }

    @Test fun aQuestionIsAnsweredByTheUser() {
        val fake = AssistantFake(AssistantFake.configured.copy(question = AssistantQuestion("想要偏暖还是偏冷？", listOf("偏暖", "偏冷"))))
        panel(fake)
        compose.onNodeWithText("偏暖").performClick()
        assertEquals("answer:偏暖", fake.calls.last())
    }

    @Test fun backClosesThePanel() {
        val fake = AssistantFake(AssistantFake.configured)
        panel(fake)
        compose.onNodeWithContentDescription("返回调整").performClick()
        assertTrue(closed)
        assertEquals("close", fake.calls.last())
    }

    @Test fun serviceSettingsSaveWithoutSendingAndNeverShowTheKey() {
        val fake = AssistantFake(AssistantFake.configured)
        fake.settings.value = fake.settings.value.copy(service = "Anthropic", address = "https://api.anthropic.com", model = "claude-sonnet-5-5")
        compose.setContent { SpektraTheme { AssistantSettingsSheet(fake) {} } }
        compose.onNodeWithText("claude-haiku-5-5").performClick()
        compose.onNodeWithContentDescription("密钥").performTextInput("sk-test-secret")
        // Masked on screen and marked as a password for accessibility services.
        compose.onNodeWithContentDescription("密钥").assert(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        assertTrue(fake.calls.isEmpty())
        compose.onNodeWithText("保存").performClick()
        assertEquals("save:Anthropic|https://api.anthropic.com|claude-haiku-5-5|key", fake.calls.last())
        compose.onNodeWithText("测试连接").performClick()
        assertEquals("test", fake.calls.last())
    }

    @Test fun clearingPreferencesTakesASecondTap() {
        val fake = AssistantFake(AssistantFake.configured)
        compose.setContent { SpektraTheme { AssistantPreferencesSheet(fake) {} } }
        compose.onNodeWithText("清除本地偏好").performClick()
        assertTrue(fake.calls.isEmpty())
        compose.onNodeWithText("确认清除").performClick()
        assertEquals(listOf("clear"), fake.calls)
    }
}
