package com.dearded.spektralab

import android.graphics.Bitmap
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 真实的基准测试后端：用合成图开一个自己的会话（不碰编辑器），渲染几个固定动作后释放。
 * 必须在 EngineThread 上跑；取消时先打断引擎，再在 NonCancellable 里释放。
 */
internal class RealBenchmarkBackend(
    private val engine: Long,
    private val catalog: Catalog,
    private val dir: File,
) : BenchmarkBackend {
    @Volatile private var session = 0L
    private val films = catalog.films.filter { !it.bw }.take(2)

    override suspend fun open(edge: Int) = withContext(EngineThread.dispatcher) {
        val bitmap = generateSyntheticBitmap(edge)
        try {
            val photo = Photo(bitmap, "sRGB", sourceWidth = bitmap.width, sourceHeight = bitmap.height)
            val params = Edit.openingParams(Edit.NONE, INPUT_SPACE, linear = true).toString()
            val opened = openPhotoSession(engine, photo, 0f) { _, _ -> params }
            session = opened.handle
            if (session == 0L) throw EngineException(tr("合成图会话打开失败"), ErrorCodes.ENGINE_INIT_FAILED)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    override suspend fun firstPreview() = withContext(EngineThread.dispatcher) {
        check(session != 0L) { tr("会话未打开") }
        val result = NativeEngine.renderBitmap(session, "preview")
        if (result == null) {
            if (NativeEngine.lastWasCancelled()) return@withContext
            throw EngineException(tr("首次预览失败：{0}", NativeEngine.lastError()), ErrorCodes.ENGINE_EXCEPTION)
        }
        result.recycle()
    }

    override suspend fun switchStyle(index: Int) = withContext(EngineThread.dispatcher) {
        check(session != 0L) { tr("会话未打开") }
        check(films.isNotEmpty()) { tr("性能测试需要至少一款彩色胶卷") }
        val film = films[Math.floorMod(index, films.size)].id
        val delta = benchmarkFilmParams(film)
        if (NativeEngine.setParams(session, delta.toString()) == null) {
            throw EngineException(tr("切换风格失败：{0}", NativeEngine.lastError()), ErrorCodes.ENGINE_EXCEPTION)
        }
        val result = NativeEngine.renderBitmap(session, "live")
        if (result == null) {
            if (NativeEngine.lastWasCancelled()) return@withContext
            throw EngineException(tr("实时预览失败：{0}", NativeEngine.lastError()), ErrorCodes.ENGINE_EXCEPTION)
        }
        result.recycle()
    }

    override suspend fun export() = withContext(EngineThread.dispatcher) {
        check(session != 0L) { tr("会话未打开") }
        val result = NativeEngine.exportBitmap(session, "full")
        if (result == null) {
            if (NativeEngine.lastWasCancelled()) return@withContext
            throw EngineException(tr("导出渲染失败：{0}", NativeEngine.lastError()), ErrorCodes.ENGINE_EXCEPTION)
        }
        result.recycle()
    }

    override fun cancel() {
        val s = session
        if (s != 0L) NativeEngine.cancel(s)
    }

    override suspend fun close() = withContext(EngineThread.dispatcher) {
        val s = session
        if (s != 0L) {
            session = 0L
            NativeEngine.release(s)
        }
    }

    private fun generateSyntheticBitmap(edge: Int): Bitmap {
        val w = edge
        val h = (edge * 3 / 4).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint()
        val gradient = android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF2196F3.toInt(), 0xFF4CAF50.toInt(), 0xFFFFC107.toInt()),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }
}

/** Use the same engine keys and render mode as an actual film selection. */
internal fun benchmarkFilmParams(film: String): JSONObject = JSONObject()
    .put("film_stock", film)
    .put("x_no_film", false)
    .put("digital_intermediate", false)
    .put("scan_film", false)
