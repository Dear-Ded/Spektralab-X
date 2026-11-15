package com.dearded.spektralab

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** App-only language preference. Photos, recipes and engine identifiers are language independent.
 * Co-created with GPT (OpenAI).
 */
internal enum class UiLanguage(val tag: String, val nativeName: String) {
    CHINESE("zh-CN", "中文"), ENGLISH("en", "English");

    companion object {
        fun fromTag(tag: String?) = entries.firstOrNull { it.tag == tag } ?: CHINESE
    }
}

internal object AppLocale {
    const val KEY = "app_language"
    var language by mutableStateOf(UiLanguage.CHINESE)
        private set

    fun load(context: Context) {
        language = UiLanguage.fromTag(context.getSharedPreferences("settings", 0).getString(KEY, null))
    }

    /** Publish only after the preference is durable, including an immediate process restart. */
    fun select(context: Context, value: UiLanguage): Boolean {
        if (value == language) return true
        if (!context.getSharedPreferences("settings", 0).edit().putString(KEY, value.tag).commit()) return false
        language = value
        return true
    }

    fun configuration(base: Configuration, value: UiLanguage = language) = Configuration(base).apply {
        setLocales(LocaleList(Locale.forLanguageTag(value.tag)))
        setLayoutDirection(Locale.forLanguageTag(value.tag))
    }

    // ActivityInfo is not attached yet; asking the incoming context for its theme can crash.
    fun wrapBase(context: Context) = context.createConfigurationContext(configuration(context.resources.configuration))

    fun wrap(context: Context, config: Configuration = configuration(context.resources.configuration)) =
        android.view.ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(config) }
}

/** Keep the composition and view models alive while updating library controls and locale-sensitive text. */
@Composable
internal fun AppLocaleHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = LocalConfiguration.current
    val language = AppLocale.language
    val config = remember(base, language) { AppLocale.configuration(base, language) }
    val localized = remember(context, config) { AppLocale.wrap(context, config) }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config, content = content)
}

private val MessageArgument = Regex("\\{(\\d+)\\}")

/** Exact UI-message aliases only; recipe values and substituted user text are never rewritten. */
internal val ReviewedChinese: Map<String, String> = mapOf(
    "分色" to "精细调色",
    "单色调整" to "按颜色调整",
    "色彩平衡" to "按明暗调色",
    "低饱高反" to "低饱和高反差",
    "冷峻高反" to "冷调高反差",
    "日常彩负" to "日常彩色负片",
    "硬调彩负" to "高反差彩色负片",
    "硬负片" to "高反差负片",
    "冷负片" to "冷调负片",
    "低感黑白" to "低感光度黑白",
    "胶片前曲线" to "胶片输入曲线",
    "横轴为胶片曝光，纵轴为映射后的曝光：−8 至 +8 EV，中点为中灰。" to
        "在模拟胶片前调整输入曝光。横轴为输入曝光，纵轴为调整后曝光；范围为 −8 至 +8 档（EV），中点为中灰。",
    "engine.scene_curve 不是有效的胶片前曲线" to "engine.scene_curve 不是有效的胶片输入曲线",
    "照片边界角标" to "照片四角标记",
    "放大曝光" to "相纸曝光",
    "高光能量" to "高光增强",
    "预闪" to "相纸预闪",
    "黑白场" to "黑白场校正",
    "冲扫黑白场" to "黑白场校正",
    "扫描仪的 USM 锐化；半径按原图像素算，各档预览按比例缩放。0 为不锐化。" to
        "扫描锐化通过增强边缘对比提高清晰度。半径按原图像素计算，预览时按比例缩放；0 为不锐化。",
    "胶片层间的 DIR 抑制：调高颜色更分明，调低更柔和；1 是这款胶片自己的。" to
        "调整胶片色层之间的显影抑制（DIR）：调高颜色更分明，调低更柔和；1 为这款胶片的默认值。",
    "grain 细节" to "颗粒细节",
    "halation 细节" to "光晕细节",
    "White Balance 偏移" to "白平衡偏移",
    "去边" to "去除色边",
    "适应" to "适应屏幕",
    "复位" to "恢复风格默认",
    "双击滑杆归零" to "双击滑杆恢复默认值",
    "工作尺寸（长边）" to "处理尺寸（长边）",
    "JPEG · 工作尺寸" to "JPEG · 处理尺寸",
    "16 位 TIFF · 工作尺寸" to "16 位 TIFF · 处理尺寸",
    "HEIF · 工作尺寸" to "HEIF · 处理尺寸",
    "HEIF · 工作尺寸（sRGB）" to "HEIF · 处理尺寸（sRGB）",
    "工作尺寸：这次测下来 {0} px 比较合适" to "处理尺寸：这次测下来 {0} px 比较合适",
    "测量本机的预览、切换风格、导出速度，给出工作尺寸建议。" to "测量本机的预览、切换风格、导出速度，给出处理尺寸建议。",
    "原图 {0}×{1}，按工作尺寸缩到 {2}×{3} 处理；可在“设置”里调大" to
        "原图 {0}×{1}，按处理尺寸缩到 {2}×{3} 处理；可在“设置”里调大",
    "原图 {0}×{1}，实际工作尺寸 {2}×{3}；受设置、GPU 和内存上限共同限制。" to
        "原图 {0}×{1}，实际处理尺寸 {2}×{3}；受设置、GPU 和内存上限共同限制。",
    "内存不足，请在“设置”里把工作尺寸调小后重试" to "内存不足，请在“设置”里把处理尺寸调小后重试",
    "工作尺寸超过 1600 万像素，1:1 查看已关闭；导出不受影响" to "处理尺寸超过 1600 万像素，1:1 查看已关闭；导出不受影响",
    "内存不足，渲染失败；请在“设置”里调小工作尺寸" to "内存不足，渲染失败；请在“设置”里调小处理尺寸",
    "处理时两次被系统中断，多半是内存不足；请调小工作尺寸后重试" to "处理时两次被系统中断，多半是内存不足；请调小处理尺寸后重试",
    "内存不足；请调小工作尺寸后重试" to "内存不足；请调小处理尺寸后重试",
    "GPU 连续两次被系统重置；请调小工作尺寸后重试" to "GPU 连续两次被系统重置；请调小处理尺寸后重试",
    "导出画面两次异常（{0}），未保存；请调小工作尺寸后重试" to "导出画面两次异常（{0}），未保存；请调小处理尺寸后重试",
    "内存不足，降档后仍失败；请调小工作尺寸后重试" to "内存不足，降档后仍失败；请调小处理尺寸后重试",
    "预览和导出都用这个尺寸，越大越慢。原始尺寸仍受 GPU 和内存上限限制。" to
        "预览和普通导出采用此尺寸，越大越慢。高质量导出优先使用原始尺寸，仍受 GPU 和内存上限限制。",
)

/** One-pass substitution keeps filenames and other argument contents verbatim. */
internal fun tr(template: String, vararg arguments: Any?): String {
    val reviewed = ReviewedChinese[template] ?: template
    val text = if (AppLocale.language == UiLanguage.ENGLISH) EnglishCatalog.text[template] ?: reviewed else reviewed
    return if (arguments.isEmpty()) text else MessageArgument.replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in arguments.indices }
            ?.let { arguments[it].toString() } ?: match.value
    }
}
