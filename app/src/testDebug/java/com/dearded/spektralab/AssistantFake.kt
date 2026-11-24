package com.dearded.spektralab

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A stand-in for codex's session behind [AssistantPort], for UI tests only: it records what the
 * panel asked for and shows whatever [AssistantView] a test sets. It never calls a model or a
 * network and never makes up a result on its own.
 */
internal class AssistantFake(initial: AssistantView) : AssistantPort, AssistantSettingsPort {
    override val view = MutableStateFlow(initial)
    val calls = mutableListOf<String>()

    override fun open() { calls += "open" }
    override fun close() { calls += "close" }
    override fun generate(goal: String) { calls += "generate:$goal" }
    override fun cinematic() { calls += "cinematic" }
    override fun cancel() { calls += "cancel" }
    override fun statistics(on: Boolean) { calls += "statistics:$on"; view.value = view.value.copy(statistics = on) }
    override fun showCurrent(current: Boolean) { calls += "showCurrent:$current"; view.value = view.value.copy(showingCurrent = current) }
    override fun adjust(param: AssistantParam, value: Double) { calls += "adjust:${param.id}" }
    override fun apply() { calls += "apply" }
    override fun discard() { calls += "discard" }
    override fun undo() { calls += "undo" }
    override fun retrySave() { calls += "retrySave" }
    override fun answer(text: String) { calls += "answer:$text" }
    override fun tryPreset(preset: AssistantPreset) { calls += "preset:${preset.name}" }
    override fun openSettings() { calls += "settings" }

    override val settings = MutableStateFlow(AssistantSettings(services = listOf(
        AssistantService("Anthropic", listOf("claude-sonnet-5-5", "claude-haiku-5-5")), AssistantService("OpenAI-compatible", emptyList()))))
    override fun save(service: String, address: String, model: String, key: CharArray?) {
        calls += "save:$service|$address|$model|${if (key != null) "key" else "nokey"}"; key?.fill(Char(0))
    }
    override fun testConnection() { calls += "test" }
    override fun cancelTest() { calls += "cancelTest" }
    override val preferences = MutableStateFlow(AssistantPreferences())
    override fun learn(on: Boolean) { calls += "learn:$on" }
    override fun useRules(on: Boolean) { calls += "useRules:$on" }
    override fun clearPreferences() { calls += "clear" }

    companion object {
        val configured = AssistantView(configured = true, service = "Anthropic", model = "claude-sonnet-5-5")
        val draft = configured.copy(phase = AssistantPhase.Previewing, canApply = true, changes = listOf(
            AssistantChange(AssistantParam.EXPOSURE, 0.0, 0.35),
            AssistantChange(AssistantParam.HIGHLIGHTS, 0.0, -24.0),
            AssistantChange(AssistantParam.TEMPERATURE, 0.0, 12.0),
        ))
    }
}
