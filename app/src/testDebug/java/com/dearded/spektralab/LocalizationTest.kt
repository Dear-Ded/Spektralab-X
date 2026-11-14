package com.dearded.spektralab

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Locale changes must preserve user data, resource configuration and the Activity context chain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalizationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("settings", 0).edit().remove(AppLocale.KEY).commit()
        AppLocale.load(context)
    }

    @After fun restoreChinese() { reset() }

    @Test fun defaultsToChineseEvenOnEnglishSystemAndUnknownPreference() {
        val english = Configuration(context.resources.configuration).apply { setLocale(Locale.US) }
        assertEquals("zh-CN", AppLocale.configuration(english).locales[0].toLanguageTag())
        context.getSharedPreferences("settings", 0).edit().putString(AppLocale.KEY, "unknown").commit()
        AppLocale.load(context)
        assertEquals(UiLanguage.CHINESE, AppLocale.language)
        assertEquals("设置", tr("设置"))
    }

    @Test fun selectionIsDurableAndReloadsAfterInMemoryLanguageChanges() {
        assertTrue(AppLocale.select(context, UiLanguage.ENGLISH))
        assertEquals("en", context.getSharedPreferences("settings", 0).getString(AppLocale.KEY, null))
        assertEquals("Settings", tr("设置"))
        context.getSharedPreferences("settings", 0).edit().remove(AppLocale.KEY).commit()
        AppLocale.load(context)
        assertEquals(UiLanguage.CHINESE, AppLocale.language)
        context.getSharedPreferences("settings", 0).edit().putString(AppLocale.KEY, "en").commit()
        AppLocale.load(context)
        assertEquals(UiLanguage.ENGLISH, AppLocale.language)
    }

    @Test fun failedPreferenceWriteDoesNotPublishLanguage() {
        val refused = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val real = super.getSharedPreferences(name, mode)
                return object : android.content.SharedPreferences by real {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = real.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?) = this
                            override fun commit() = false
                        }
                    }
                }
            }
        }
        assertFalse(AppLocale.select(refused, UiLanguage.ENGLISH))
        assertEquals(UiLanguage.CHINESE, AppLocale.language)
    }

    @Test fun translatedArgumentsKeepFilenamesAndBracesVerbatim() {
        AppLocale.select(context, UiLanguage.ENGLISH)
        val filename = "设置 {0} \$photo.png"
        assertEquals("No results for $filename", tr("没有找到“{0}”", filename))
        assertEquals("unmapped 设置", tr("unmapped 设置"))
        assertEquals("Value null", tr("Value {0}", null))
        assertEquals("Value {12345678901234567890}", tr("Value {12345678901234567890}", "unused"))
    }

    @Test fun switchingUpdatesPreviouslyInitializedLabelsWithoutChangingRecipeIdentity() {
        val style = Style.ALL.first()
        val recipe = style.toSpkLook().toJson().toString()
        val border = Borders.byId("none")!!
        assertEquals("无边框", border.label)
        assertEquals("光线", ADJUST_SEGMENTS.first())
        AppLocale.select(context, UiLanguage.ENGLISH)
        assertEquals("Golden Lab", style.displayName)
        assertEquals("No border", border.label)
        assertEquals("Light", ADJUST_SEGMENTS.first())
        assertEquals("Brush", MaskKind.BRUSH.label)
        assertEquals("Undeveloped", FilmProcess.NONE.label)
        assertEquals(recipe, style.toSpkLook().toJson().toString())
        assertEquals("设置", style.copy(id = "user_custom", name = "设置").displayName)
        assertTrue(style.matchesQuery("golden lab"))
        assertTrue(style.matchesQuery("暖金"))
    }

    @Test fun localizedContextRetainsActivityAndDoesNotMutateBaseResources() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val original = Configuration(activity.resources.configuration)
        val wrapped = AppLocale.wrap(activity, AppLocale.configuration(original, UiLanguage.ENGLISH))
        assertEquals("en", wrapped.resources.configuration.locales[0].language)
        assertEquals(original, activity.resources.configuration)
        var current: Context = wrapped
        while (current is ContextWrapper && current !== activity) current = current.baseContext
        assertSame(activity, current)
        controller.pause().stop().destroy()
    }

    @Test fun catalogKeepsAllNumberedArgumentsAndHasNoChineseTranslations() {
        val args = Regex("\\{\\d+\\}")
        val han = Regex("[\\u3400-\\u9fff]")
        EnglishCatalog.text.forEach { (source, english) ->
            assertEquals(source, args.findAll(source).map { it.value }.toSet(), args.findAll(english).map { it.value }.toSet())
            assertFalse("Untranslated: $source", han.containsMatchIn(english))
        }
    }
}
