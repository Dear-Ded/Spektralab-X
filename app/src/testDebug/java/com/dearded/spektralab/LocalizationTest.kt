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

    @Test fun reviewedNamesAcceptLegacyAndCurrentKeysInBothLanguages() {
        val names = listOf(
            Triple("分色", "精细调色", "Colour grading"),
            Triple("单色调整", "按颜色调整", "Colour mixer"),
            Triple("色彩平衡", "按明暗调色", "Tonal grading"),
            Triple("低饱高反", "低饱和高反差", "Low Saturation, High Contrast"),
            Triple("胶片前曲线", "胶片输入曲线", "Film input curve"),
            Triple("工作尺寸（长边）", "处理尺寸（长边）", "Processing size (long edge)"),
            Triple("JPEG · 工作尺寸", "JPEG · 处理尺寸", "JPEG · Processing size"),
            Triple("照片边界角标", "照片四角标记", "Photo corner guides"),
            Triple("高光能量", "高光增强", "Highlight boost"),
            Triple("预闪", "相纸预闪", "Paper preflash"),
            Triple("复位", "恢复风格默认", "Restore style defaults"),
            Triple("应用数值", "应用数值", "Apply value"),
            Triple("输入", "输入", "Input"),
            Triple("输出", "输出", "Output"),
            Triple("整体 RGB", "整体 RGB", "All colours (RGB)"),
        )
        for (language in UiLanguage.entries) {
            assertTrue(AppLocale.select(context, language))
            for ((legacy, chinese, english) in names) {
                val expected = if (language == UiLanguage.CHINESE) chinese else english
                assertEquals(legacy, expected, tr(legacy))
                assertEquals(chinese, expected, tr(chinese))
            }
            assertEquals(if (language == UiLanguage.CHINESE) "无" else "None", tr("无"))
            assertEquals(if (language == UiLanguage.CHINESE) "确定" else "OK", tr("确定"))
        }
    }

    @Test fun reviewedAliasesPreserveArgumentsAndHaveUnambiguousEnglish() {
        val arguments = Regex("\\{\\d+\\}")
        ReviewedChinese.forEach { (legacy, reviewed) ->
            assertEquals(legacy, arguments.findAll(legacy).map { it.value }.toList(),
                arguments.findAll(reviewed).map { it.value }.toList())
            assertEquals(legacy, reviewed, ReviewedChinese[reviewed] ?: reviewed)
            assertNotNull("Missing English: $legacy", EnglishCatalog.text[legacy])
            assertEquals(legacy, EnglishCatalog.text[legacy], EnglishCatalog.text[reviewed])
        }
        ReviewedChinese.entries.groupBy { it.value }.forEach { (reviewed, aliases) ->
            assertEquals(reviewed, 1, aliases.map { EnglishCatalog.text[it.key] }.distinct().size)
        }
    }

    @Test fun importedNamesAndFilenamesThatMatchUiKeysStayVerbatim() {
        val builtin = Style.ALL.first()
        for (name in listOf("分色", "低饱高反", "胶片前曲线", "工作尺寸（长边）", "无", "确定")) {
            val style = builtin.copy(id = "us_custom", name = name, inspiredBy = name)
            val filename = "$name {0} \$photo.spklook"
            val imported = UserStyle("us_custom", SpkLook(name = name, category = name,
                engine = SpkEngine(film = builtin.film, paper = builtin.paper),
                source = SpkSource("user", filename, name)))
            val before = imported.toJson().toString()
            for (language in UiLanguage.entries) {
                assertTrue(AppLocale.select(context, language))
                assertEquals(name, style.displayName)
                assertEquals(name, style.displayInspiration)
                assertEquals(name, imported.name)
                assertEquals(name, imported.category)
                assertEquals(filename, imported.look.source?.file)
                assertEquals(before, imported.toJson().toString())
                val expected = if (language == UiLanguage.CHINESE) "没有找到“$filename”" else "No results for $filename"
                assertEquals(expected, tr("没有找到“{0}”", filename))
            }
        }
    }

    @Test fun renamedBuiltinKeepsItsRecipeAndRemainsSearchableInEnglish() {
        val style = Style.ALL.single { it.id == "cam_bleach" }
        val before = style.toSpkLook().toJson().toString()
        assertEquals("低饱和高反差", style.displayName)
        assertTrue(style.matchesQuery("低饱和高反差"))
        assertEquals("低饱高反", style.name)
        assertTrue(AppLocale.select(context, UiLanguage.ENGLISH))
        assertEquals("Low Saturation, High Contrast", style.displayName)
        assertTrue(style.matchesQuery("low saturation high contrast"))
        assertTrue(style.matchesQuery("低饱高反"))
        assertEquals(before, style.toSpkLook().toJson().toString())
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

    @Test fun activityBaseAttachmentDoesNotReadTheUninitializedTheme() {
        var attachedConfiguration: Configuration? = null
        val unattached = object : ContextWrapper(context) {
            override fun getTheme(): android.content.res.Resources.Theme =
                error("Activity theme is unavailable before attachment")
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
                attachedConfiguration = Configuration(overrideConfiguration)
                return super.createConfigurationContext(overrideConfiguration)
            }
        }
        for (language in UiLanguage.entries) {
            AppLocale.select(context, language)
            val activity = MainActivity()
            MainActivity::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(activity, unattached)
            assertEquals(language.tag, attachedConfiguration!!.locales[0].toLanguageTag())
            assertNotSame(unattached, activity.baseContext)
        }
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
