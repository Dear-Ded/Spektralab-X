package com.dearded.spektralab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 性能测试对引擎的全部需求。真实实现用合成图开一个自己的会话（不碰编辑器正在用的那个），
 * 单元测试里换成假的。close() 必须可以重复调用，也必须能在 open() 失败后调用。
 */
internal interface BenchmarkBackend {
    suspend fun open(edge: Int)

    /** 首次预览：会话刚打开后的第一帧预览。 */
    suspend fun firstPreview()

    /** 换到第 [index] 个风格并出一帧实时预览；偶数和奇数换成不同的两个风格。 */
    suspend fun switchStyle(index: Int)

    /** 完整导出渲染，只渲染不写入相册。 */
    suspend fun export()

    /** 任意线程：尽快打断正在进行的渲染（NativeEngine.cancel）。 */
    fun cancel()

    suspend fun close()
}

internal sealed interface BenchmarkOutcome {
    data class Done(val result: BenchmarkResult) : BenchmarkOutcome

    /** 机身偏热，没有硬跑；[phase] 是停在哪一步。 */
    data class TooHot(val headroom: Float, val phase: String) : BenchmarkOutcome

    /** [code] 是 ErrorCodes 里的 E-xxxx。 */
    data class Failed(val code: String, val message: String) : BenchmarkOutcome
}

/**
 * 固定负载：同一张合成图、同一个尺寸、同一串动作。取消时抛出 CancellationException
 * （先打断引擎，再在 NonCancellable 里释放会话），由调用方决定怎么提示。
 * 发热时不硬跑：开始前和每个阶段之间都看一次热余量，偏热就停。
 */
internal class BenchmarkRunner(
    private val backend: BenchmarkBackend,
    private val clockMs: () -> Long,
    private val headroom: () -> Float,
    /** 把 VmHWM 重置为当前 RSS；false = 内核不让，峰值内存就报读不到。 */
    private val resetPeak: () -> Boolean,
    private val rssKb: () -> Long,
    private val peakKb: () -> Long,
    private val maxPixels: Long,
    private val peakBudgetMb: Long,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(onProgress: (String, Float) -> Unit = { _, _ -> }): BenchmarkOutcome {
        val before = headroom()
        if (BenchmarkPolicy.thermalGate(before) == ThermalGate.HOT) return BenchmarkOutcome.TooHot(before, "开始前")
        val reset = resetPeak()
        val rss0 = rssKb()
        val edge = BenchmarkPolicy.TEST_EDGE
        try {
            onProgress("准备合成图", 0.05f)
            backend.open(edge)

            hot("首次预览")?.let { return it }
            onProgress("首次预览", 0.2f)
            val firstMs = timed { backend.firstPreview() }

            hot("切换风格")?.let { return it }
            onProgress("切换风格", 0.45f)
            val samples = List(STYLE_SAMPLES) { i -> timed { backend.switchStyle(i) } }

            hot("导出")?.let { return it }
            onProgress("导出", 0.7f)
            val exportMs = timed { backend.export() }

            val after = headroom()
            val peak = peakKb()
            val growthMb = if (reset && rss0 >= 0 && peak >= rss0) (peak - rss0) / 1024 else -1L
            val switchMs = median(samples)
            val advice = BenchmarkPolicy.advise(edge, switchMs, exportMs, growthMb, after, maxPixels, peakBudgetMb)
            onProgress("完成", 1f)
            return BenchmarkOutcome.Done(BenchmarkResult(edge, firstMs, switchMs, exportMs, growthMb, before, after, advice, wallClockMs()))
        } catch (e: CancellationException) {
            backend.cancel()
            throw e
        } catch (e: Throwable) {
            return BenchmarkOutcome.Failed(codeOf(e), e.message ?: e.toString())
        } finally {
            withContext(NonCancellable) { runCatching { backend.close() } }
        }
    }

    private fun hot(phase: String): BenchmarkOutcome.TooHot? {
        val h = headroom()
        return if (BenchmarkPolicy.thermalGate(h) == ThermalGate.HOT) BenchmarkOutcome.TooHot(h, phase) else null
    }

    private suspend fun timed(block: suspend () -> Unit): Long {
        currentCoroutineContextEnsureActive()
        val t0 = clockMs()
        block()
        return clockMs() - t0
    }

    private suspend fun currentCoroutineContextEnsureActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    companion object {
        /** 切换风格测几次取中位数，第一次常带着管线编译，单次不稳。 */
        const val STYLE_SAMPLES = 3

        fun median(values: List<Long>): Long = values.sorted().let { it[it.size / 2] }

        fun codeOf(e: Throwable): String = when (e) {
            is EngineException -> e.errorCode
            is DeviceLostException -> ErrorCodes.GPU_DEVICE_LOST
            is OutOfMemoryError, is OutOfMemoryException -> ErrorCodes.OUT_OF_MEMORY
            else -> ErrorCodes.ENGINE_EXCEPTION
        }
    }
}
