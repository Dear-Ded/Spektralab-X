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

/** One-pass substitution keeps filenames and other argument contents verbatim. */
internal fun tr(template: String, vararg arguments: Any?): String {
    val text = if (AppLocale.language == UiLanguage.ENGLISH) EnglishCatalog.text[template] ?: template else template
    return if (arguments.isEmpty()) text else MessageArgument.replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in arguments.indices }
            ?.let { arguments[it].toString() } ?: match.value
    }
}
