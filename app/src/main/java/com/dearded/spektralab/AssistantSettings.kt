package com.dearded.spektralab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/*
 * 设置 → 暗房助手 and 设置 → 本地偏好 (docs/FRONTEND_ITERATION_2026-11-24.md C9). UI only, over
 * codex's AdvisorRuntime (service, address, model, key, connection test) and the local preference
 * store (learn, use confirmed rules, clear). Loading, saving and testing never send a model request
 * from here except 测试连接, which says so by its name. The key is never shown back.
 */

/** A service the catalog offers and its models. */
@Immutable
internal data class AssistantService(val name: String, val models: List<String>)

/** 设置 → 暗房助手 (AdvisorRuntimeState). */
@Immutable
internal data class AssistantSettings(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val services: List<AssistantService> = emptyList(),
    val service: String = "",
    val address: String = "",
    val model: String = "",
    /** A key is stored for this service (it is never shown). */
    val keySaved: Boolean = false,
    /** The last 测试连接: null not tested since the last change, true passed, false failed ([error] says why). */
    val tested: Boolean? = null,
    val error: AssistantError? = null,
)

/** 设置 → 本地偏好. */
@Immutable
internal data class AssistantPreferences(
    val learn: Boolean = false,
    val useRules: Boolean = false,
    val busy: Boolean = false,
    val error: AssistantError? = null,
)

/** The settings side of the session (AdvisorRuntime and the preference store). */
internal interface AssistantSettingsPort {
    val settings: StateFlow<AssistantSettings>
    /** Saves the service; [key] null keeps the stored key. The array is the port's to wipe. */
    fun save(service: String, address: String, model: String, key: CharArray?)
    fun testConnection()
    fun cancelTest()
    val preferences: StateFlow<AssistantPreferences>
    fun learn(on: Boolean)
    fun useRules(on: Boolean)
    fun clearPreferences()
}

/** The settings side of the session, provided with [LocalAssistant]; null = no assistant settings. */
internal val LocalAssistantSettings = androidx.compose.runtime.staticCompositionLocalOf<AssistantSettingsPort?> { null }

/** The service set-up as a sheet: service, address, model, key; 测试连接 and 保存. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSettingsSheet(port: AssistantSettingsPort, onDismiss: () -> Unit) {
    val s by port.settings.collectAsState()
    var service by remember(s.service) { mutableStateOf(s.service) }
    var address by remember(s.address) { mutableStateOf(s.address) }
    var model by remember(s.model) { mutableStateOf(s.model) }
    var key by remember { mutableStateOf("") }
    val busy = s.loading || s.saving
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.PanelHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(RowGap)) {
            PanelHeader(tr("暗房助手"))
            GroupLabel(tr("服务"))
            PillRow {
                for (sv in s.services) SelectPill(selected = sv.name == service, onClick = {
                    service = sv.name; if (model !in sv.models) model = sv.models.firstOrNull().orEmpty()
                }, label = { Text(sv.name) }, enabled = !busy)
            }
            Field(address, { address = it }, tr("服务地址"), !busy, KeyboardType.Uri)
            val models = s.services.firstOrNull { it.name == service }?.models.orEmpty()
            GroupLabel(tr("模型"))
            if (models.isNotEmpty()) PillRow {
                for (m in models) SelectPill(selected = m == model, onClick = { model = m }, label = { Text(m) }, enabled = !busy)
            } else Field(model, { model = it }, tr("模型名称"), !busy, KeyboardType.Ascii)
            Field(key, { key = it }, if (s.keySaved) tr("密钥已保存") else tr("密钥"), !busy, KeyboardType.Password, secret = true)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        s.loading -> tr("正在读取")
                        s.saving -> tr("正在保存")
                        s.testing -> tr("正在测试连接")
                        s.tested == true -> tr("连接正常")
                        else -> s.error?.let { errorLabel(it) }.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.error != null && !s.testing) Palette.Warning else Palette.OnSurfaceSoft,
                    modifier = Modifier.weight(1f),
                )
                if (s.testing) TonalButton(tr("取消")) { port.cancelTest() }
                else TonalButton(tr("测试连接"), !busy && s.service.isNotBlank()) { port.testConnection() }
                PrimaryButton(tr("保存"), !busy && service.isNotBlank() && address.isNotBlank() && model.isNotBlank()) {
                    port.save(service, address.trim(), model.trim(), key.takeIf { it.isNotEmpty() }?.toCharArray())
                    key = ""
                }
            }
        }
    }
}

/** 本地偏好 as a sheet: learn, use confirmed rules, clear (a second tap confirms). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantPreferencesSheet(port: AssistantSettingsPort, onDismiss: () -> Unit) {
    val p by port.preferences.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.PanelHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PanelHeader(tr("本地偏好"))
            SettingRow(tr("学习我的偏好"), p.learn, !p.busy) { port.learn(it) }
            SettingRow(tr("使用已确认的规则"), p.useRules, !p.busy) { port.useRules(it) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.error?.let { errorLabel(it) }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.Warning,
                    modifier = Modifier.weight(1f))
                TonalButton(if (confirm) tr("确认清除") else tr("清除本地偏好"), !p.busy) {
                    if (confirm) { port.clearPreferences(); confirm = false } else confirm = true
                }
            }
        }
    }
}

private fun errorLabel(e: AssistantError) = when (e) {
    AssistantError.Configuration -> tr("服务未配置")
    AssistantError.Auth -> tr("密钥无效")
    AssistantError.Address -> tr("服务地址无效")
    AssistantError.RateLimit -> tr("请求太频繁")
    AssistantError.Timeout -> tr("请求超时")
    AssistantError.Network -> tr("网络连接失败")
    AssistantError.Refused -> tr("服务拒绝了这次请求")
    AssistantError.Invalid -> tr("返回的结果无法使用")
    AssistantError.Render -> tr("预览渲染失败")
    AssistantError.Save -> tr("保存失败")
    AssistantError.Preferences -> tr("无法读取本地偏好")
}

/** A one-line field in the sheet's style; [secret] hides what is typed. */
@Composable
private fun Field(text: String, onText: (String) -> Unit, label: String, enabled: Boolean, type: KeyboardType, secret: Boolean = false) {
    BasicTextField(
        value = text, onValueChange = onText, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.OnSurface),
        cursorBrush = SolidColor(Palette.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Corners.m).background(Palette.Panel)
                    .border(1.dp, Palette.OutlineSoft, Corners.m).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.OnSurfaceSoft.copy(alpha = 0.7f))
                inner()
            }
        },
    )
}
