package com.dearded.spektralab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PNGs of the editor and the light table as the phone draws them (docs/SCREENSHOTS.md),
 * rendered on the JVM by Robolectric's native graphics and written by Roborazzi.
 *
 * The engine (NativeEngine, JNI) cannot load here. The view models are the real
 * ones; the editor's engine start fails as it would without a GPU, and the test
 * then puts a made-up UiState in its place. Photos and film cards are crops of
 * docs/images/samples. Nothing in app/src/main is changed for this.
 *
 *   ./gradlew :app:testDebugUnitTest --tests com.dearded.spektralab.Screenshots
 *   -> app/build/outputs/roborazzi/<name>.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 1080 x 2400 at 440 dpi (most Xiaomi phones): 393 x 873 dp. SDK 34: SDK 35 needs a JDK 21.
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-440dpi-keyshidden-nonav")
class Screenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        // Animations step only when a test says so, and an endless one (the aura) never hangs the wait.
        compose.mainClock.autoAdvance = false
        File(OUT).mkdirs()
    }

    // --- editor -------------------------------------------------------------

    @Test fun editorEmpty() {
        editor(Fake.ui().copy(source = null, image = null, before = null, filmCards = emptyMap(), canUndo = false))
        shot("01_editor_empty")
    }

    @Test fun editorStyleAndPanel() {
        editor(Fake.ui())
        shot("10_style")
        // Tapping the open entry again folds the panel to the toolbar.
        tap("风格")
        shot("11_panel_folded")
        tap("风格")
        compose.onNodeWithContentDescription("面板高度").performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(0f, -100f)) }
            up()
        }
        shot("12_panel_tall")
    }

    @Test fun editorDevelop() {
        editor(Fake.ui())
        tap("冲洗")
        shot("20_develop_1_film")
        tap("② 相纸")
        shot("21_develop_2_paper")
        tap("③ 曝光与放大")
        shot("22_develop_3_exposure")
        tap("④ 胶片效果")
        shot("23_develop_4_effects")
    }

    @Test fun editorAdjust() {
        editor(Fake.ui())
        tap("调整")
        ADJUST_SEGMENTS.forEachIndexed { i, name ->
            tap(name)
            shot("3${i}_adjust_${i + 1}")
        }
    }

    @Test fun editorBorderAndCrop() {
        val ui = Fake.ui()
        val border = Borders.byId("gallery")!!
        editor(ui.copy(edit = Edit.withBorder(ui.edit, border, ui.source!!.width, ui.source!!.height)))
        tap("边框")
        shot("40_border")
        tap("构图")
        shot("50_crop")
    }

    @Test fun settings() {
        val vm = editorVm(Fake.ui())
        content { SettingsScreen(vm.ui.value, vm) {} }
        shot("60_settings")
    }

    // --- light table ----------------------------------------------------------

    @Test fun lightTableGrid() {
        val table = tableVm()
        content { LightTableScreen(table, onBack = {}, onOpen = { _, _ -> }, onRolls = {}) }
        shot("70_table_grid")
    }

    @Test fun lightTableLoupe() {
        val table = tableVm()
        content { LightTableLoupe(table, Loupe(Fake.frames.map { it.id }, 0), onEdit = {}, onClose = {}) }
        shot("71_table_loupe")
        // A tap brings the top and bottom controls in.
        compose.onRoot().performTouchInput { click(center) }
        shot("72_table_loupe_controls")
    }

    // --- batch 2: 无, sheets, viewers, rolls, launch ----------------------------------

    @Test fun editorNoFilm() {
        val ui = Fake.ui()
        editor(ui.copy(edit = ui.edit.withoutStyle()))
        shot("13_style_none")
        tap("冲洗")
        shot("24_develop_none")
    }

    @Test fun editorMasks() {
        val ui = Fake.ui()
        editor(ui.copy(edit = ui.edit.copy(look = ui.edit.look.copy(masks = listOf(EditMask.make(MaskKind.LINEAR))))))
        tap("调整")
        compose.onAllNodes(hasText("局部蒙版")).onFirst().performScrollTo()
        tap("展开（1 个）")
        compose.onAllNodes(hasText("局部蒙版")).onFirst().performScrollTo()
        shot("36_adjust_masks")
    }

    @Test fun changesSheet() {
        val ui = Fake.ui()
        val e = Edit.withBorder(ui.edit, Borders.byId("gallery")!!, ui.source!!.width, ui.source!!.height)
            .copy(autoExposure = false, exposureEv = 0.7, look = ui.edit.look.copy(contrast = 12.0, saturation = -8.0))
        val vm = editorVm(ui.copy(edit = e))
        content { EditorScreen(vm); ChangesSheet(vm.ui.value, vm, onGo = {}, onDismiss = {}) }
        screenShot("61_changes")
    }

    @Test fun pasteSheet() {
        val ui = Fake.ui()
        val vm = editorVm(ui)
        content {
            EditorScreen(vm)
            PasteSheet(CopiedLook(ui.edit, 0.0, 0.0, "DSC_4121.JPG"), count = 1,
                initial = LookGroup.entries.toSet() - LookGroup.BORDER, onDismiss = {}, onPaste = {})
        }
        screenShot("62_paste")
    }

    @Test fun queueSheet() {
        val vm = editorVm(Fake.ui())
        fun job(id: Long, name: String, state: JobState, progress: Float = 0f, phase: String = "", eta: Int = -1) =
            ProcessingJob(id, "file:///shots/$name", name, raw = name.endsWith("DNG"), editJson = "{}", wbK = 0.0, wbTint = 0.0,
                format = ExportFormat.JPEG, size = ExportSize.FULL, workingEdge = 4096, maxPixels = 0, outPixels = 24_000_000,
                filmLabel = "Kodak Portra 400", state = state, progress = progress, phase = phase, etaSeconds = eta,
                finishedAt = if (state == JobState.DONE || state == JobState.FAILED) 1_760_000_000_000L - id else 0)
        val jobs = listOf(
            job(1, "L1000412.DNG", JobState.RUNNING, 0.42f, "冲洗", 38),
            job(2, "DSC_4121.JPG", JobState.QUEUED),
            job(3, "DSC_4119.JPG", JobState.DONE, 1f),
            job(4, "DSC_4117.JPG", JobState.FAILED).copy(message = "内存不足：请调小工作尺寸后重试"),
        )
        content { EditorScreen(vm); QueueSheet(jobs, {}, {}, {}, {}) }
        screenShot("63_queue")
    }

    @Test fun importSheet() {
        val vm = editorVm(Fake.ui())
        val cube = File(compose.activity.cacheDir, "暖调胶片.cube")
        cube.writeText(buildString {
            append("TITLE \"暖调胶片\"\nLUT_3D_SIZE 2\n")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) append("${r * 0.95 + 0.03} ${g * 0.9 + 0.02} ${b * 0.8}\n")
        })
        ImportRequests.add(listOf(android.net.Uri.fromFile(cube)))
        content { EditorScreen(vm); ImportHost(vm) }
        // The file is read on Dispatchers.IO: real threads, so wait in real time.
        val main = shadowOf(Looper.getMainLooper())
        repeat(120) { compose.mainClock.advanceTimeBy(50); main.idle(); Thread.sleep(25) }
        screenShot("64_import")
    }

    @Test fun updateSheet() {
        val vm = editorVm(Fake.ui().copy(source = null, image = null))
        Updater.state.value = Updater.State.Available(Updater.Release(
            version = "0.1.20-alpha",
            notes = "- 全新编辑器版式：每一步一个标题、一行说明\n- 胶片「无」：打开就是原图\n- 局部蒙版：线性、径向和画笔\n- 观片台按卷整理",
            page = Updater.RELEASES_PAGE, apkName = "Spektralab-X-0.1.20-alpha-arm64-v8a.apk",
            apkUrl = "https://example.invalid/app.apk", apkSize = 48L shl 20, sumsUrl = null))
        Updater.show()
        content { EditorScreen(vm) }
        screenShot("65_update")
        Updater.later()
        Updater.state.value = Updater.State.Idle
    }

    @Test fun detailViewer() {
        content { DetailViewer(Fake.detail, onClose = {}) }
        shot("66_detail_1to1")
    }

    @Test fun rolls() {
        val table = tableVm()
        val rolls = rollsOf(Fake.frames, Fake.catalog)
        content { RollList(table, rolls, onOpen = {}, onGrid = {}, onBack = {}) }
        shot("73_table_rolls")
    }

    @Test fun rollStrip() {
        val table = tableVm()
        val roll = rollsOf(Fake.frames, Fake.catalog).maxBy { it.frames.size }
        content { RollView(table, roll, onBack = {}, onOpen = { _, _ -> }, onExport = {}) }
        shot("74_table_roll_strip")
    }

    @Test fun launchIntro() {
        content { LaunchIntro(ready = false, onDone = {}) }
        val main = shadowOf(Looper.getMainLooper())
        repeat(3) { compose.mainClock.advanceTimeBy(150); main.idleFor(java.time.Duration.ofMillis(150)) }
        compose.onRoot().captureRoboImage("$OUT/00_launch_intro.png")
    }

    // --- helpers ------------------------------------------------------------------

    private fun content(body: @Composable () -> Unit) = compose.setContent { SpektraTheme { body() } }

    private fun editor(ui: UiState) {
        val vm = editorVm(ui)
        content { EditorScreen(vm) }
    }

    /** The real EditorViewModel once its engine start has failed, showing [ui]. */
    @Suppress("UNCHECKED_CAST")
    private fun editorVm(ui: UiState): EditorViewModel {
        Fake.seedTable(compose.activity)
        val vm = ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        val main = shadowOf(Looper.getMainLooper())
        for (i in 0 until 400) {
            main.idle()
            if (vm.ui.value.error != null || vm.ui.value.ready) break
            Thread.sleep(25)
        }
        val field = EditorViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }
        (field.get(vm) as MutableStateFlow<UiState>).value = ui
        main.idle()
        return vm
    }

    private fun tableVm(): LightTableViewModel {
        Fake.seedTable(compose.activity)
        return ViewModelProvider(compose.activity)[LightTableViewModel::class.java]
    }

    /** Clicks the first clickable node whose text is exactly [text], wherever it is scrolled. */
    private fun tap(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1200)
    }

    private fun shot(name: String) {
        // Compose frames and the view's own draw both: the window is captured as drawn last.
        val main = shadowOf(Looper.getMainLooper())
        repeat(8) {
            compose.mainClock.advanceTimeBy(200)
            main.idleFor(java.time.Duration.ofMillis(200))
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/$name.png")
    }

    /** Like [shot], but the whole screen: a bottom sheet or dialog is a window of its own. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun screenShot(name: String) {
        val main = shadowOf(Looper.getMainLooper())
        repeat(8) {
            compose.mainClock.advanceTimeBy(200)
            main.idleFor(java.time.Duration.ofMillis(200))
        }
        compose.waitForIdle()
        captureScreenRoboImage("$OUT/$name.png")
    }

    private companion object {
        const val OUT = "build/outputs/roborazzi"
    }
}

/** Made-up state: a catalog like the bundled one, the sample photos, a table of frames. */
private object Fake {
    private val samples = File("../docs/images/samples").let { if (it.isDirectory) it else File("docs/images/samples") }

    private fun load(name: String): Bitmap =
        BitmapFactory.decodeFile(File(samples, name).path) ?: error("cannot read ${File(samples, name).absolutePath}")

    private fun crop(b: Bitmap, x: Int, y: Int, w: Int, h: Int) = Bitmap.createBitmap(b, x, y, w, h)

    /** The compare images are "before | after" with a caption under each half. */
    private fun before(b: Bitmap) = crop(b, 44, 44, 944, b.height - 140)
    private fun after(b: Bitmap) = crop(b, 1012, 44, 944, b.height - 140)

    private fun gray(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(b, 0f, 0f, p)
        return out
    }

    private val lake by lazy { load("compare_lake.jpg") }
    /** The 1:1 viewer's "full render": the after half, unscaled. */
    val detail by lazy { after(lake) }
    /** films_dunes.jpg: six 629 x 420 tiles, three a row. */
    private val dunes by lazy {
        val b = load("films_dunes.jpg")
        listOf(40, 685, 1330).flatMap { x -> listOf(40, 524).map { y -> crop(b, x + 4, y + 4, 620, 412) } }
    }

    private fun film(id: String, label: String, positive: Boolean = false, bw: Boolean = false) =
        Stock(id, label, positive, bw, null)

    val catalog = Catalog(
        films = listOf(
            film("kodak_portra_160", "Kodak Portra 160"), film("kodak_portra_400", "Kodak Portra 400"),
            film("kodak_portra_800", "Kodak Portra 800"), film("kodak_portra_800_push1", "Kodak Portra 800 +1"),
            film("kodak_portra_800_push2", "Kodak Portra 800 +2"), film("kodak_ektar_100", "Kodak Ektar 100"),
            film("kodak_gold_200", "Kodak Gold 200"), film("kodak_ultramax_400", "Kodak UltraMax 400"),
            film("kodak_ektachrome_100", "Kodak Ektachrome 100", positive = true),
            film("kodak_kodachrome_64", "Kodak Kodachrome 64", positive = true),
            film("kodak_vision3_50d", "Kodak Vision3 50D"), film("kodak_vision3_250d", "Kodak Vision3 250D"),
            film("kodak_vision3_200t", "Kodak Vision3 200T"), film("kodak_vision3_500t", "Kodak Vision3 500T"),
            film("kodak_verita_200d", "Kodak Verita 200D"),
            film("kodak_tri_x_400", "Kodak Tri-X 400", bw = true), film("kodak_tmax_100", "Kodak T-Max 100", bw = true),
            film("cinestill_50d", "CineStill 50D"), film("cinestill_800t", "CineStill 800T"),
            film("fujifilm_c200", "Fujifilm C200"), film("fujifilm_xtra_400", "Fujifilm Superia X-TRA 400"),
            film("fujifilm_pro_400h", "Fujifilm Pro 400H"),
            film("fujifilm_provia_100f", "Fujifilm Provia 100F", positive = true),
            film("fujifilm_velvia_100", "Fujifilm Velvia 100", positive = true),
            film("fujifilm_neopan_acros_100_ii", "Fujifilm Neopan Acros 100 II", bw = true),
            film("ilford_hp5_plus_400", "Ilford HP5 Plus 400", bw = true),
        ).sortedBy { it.label },
        papers = listOf(
            film("kodak_supra_endura", "Kodak Supra Endura"), film("kodak_portra_endura", "Kodak Portra Endura"),
            film("kodak_ultra_endura", "Kodak Ultra Endura"), film("kodak_endura_premier", "Kodak Endura Premier"),
            film("kodak_ektacolor_edge", "Kodak Ektacolor Edge"), film("kodak_2383", "Kodak 2383"),
            film("kodak_2393", "Kodak 2393"), film("fujifilm_crystal_archive_typeii", "Fujifilm Crystal Archive Type II"),
            film("ilford_multigrade_iv_rc", "Ilford Multigrade IV RC", bw = true),
        ).sortedBy { it.label },
    )

    private val cards by lazy {
        catalog.films.mapIndexed { i, s ->
            val tile = Bitmap.createScaledBitmap(dunes[i % dunes.size], 384, 255, true)
            s.id to if (s.bw) gray(tile) else tile
        }.toMap()
    }

    fun ui() = UiState(
        ready = true, busy = null, catalog = catalog, edit = Edit(),
        image = after(lake), imageTier = "preview", before = before(lake), filmCards = cards,
        source = SourceInfo("L1000412.DNG", 6000, 4000, 6000, 4000, "raw", "Leica Q (Typ 116)",
            asShotK = 5200.0, asShotTint = 4.0),
        canUndo = true,
        deviceSummary = "Adreno 750 · Vulkan 1.3 · 12 GB",
        engineSummary = "SpektraLab 引擎 · 存储缓冲 2 GB",
    )

    val frames: List<Frame> by lazy {
        val names = listOf("compare_lake.jpg", "compare_skyline.jpg", "compare_fruit.jpg", "compare_dunes.jpg")
        val photos = names.flatMap { n -> load(n).let { listOf(after(it), before(it)) } } + dunes
        photos.mapIndexed { i, b ->
            Frame(
                id = "f%02d".format(i), uri = "file:///shots/frame_%02d.jpg".format(i),
                name = "DSC_%04d.JPG".format(4120 + i), size = 1L shl 22, date = 1_760_000_000_000L - i * 3_600_000L,
                mime = "image/jpeg", raw = i % 5 == 3, width = b.width, height = b.height,
                pick = when (i % 7) { 1 -> 1; 4 -> -1; else -> 0 }, stars = if (i % 3 == 0) 3 + i % 3 else 0,
                edited = i % 4 == 0, film = if (i % 4 == 0) "kodak_portra_400" else "",
            ) to b
        }.also { list ->
            // Every size the grid and the loupe may ask for, so nothing waits on a decoder.
            val cache = Thumbnails::class.java.getDeclaredField("cache").apply { isAccessible = true }
                .get(null) as android.util.LruCache<String, Bitmap>
            // Its 48 MB cap counts each bucket's copy: four sizes of 14 photos would evict the first ones.
            cache.resize(512 shl 20)
            for ((f, b) in list) for (bucket in listOf(256, 512, 1024, 2048)) cache.put("$bucket|${f.uri}", b)
        }.map { it.first }
    }

    /** The light table's store, already "loaded" with [frames] (its index file is never read). */
    @Suppress("UNCHECKED_CAST")
    fun seedTable(context: android.content.Context) {
        val store = FrameStore.of(context)
        FrameStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(store, true)
        val flow = FrameStore::class.java.getDeclaredField("_frames").apply { isAccessible = true }.get(store)
        (flow as MutableStateFlow<List<Frame>>).value = frames
    }
}
