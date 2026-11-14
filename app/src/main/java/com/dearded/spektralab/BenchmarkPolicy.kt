package com.dearded.spektralab

import java.util.Locale

/**
 * 性能测试的纯逻辑：热状态闸门、建议策略、结果文本。不碰 Android 和引擎，
 * 所以能直接跑 JVM 单元测试。
 *
 * 这里的数字只是本机本次的测量：受当时温度、电量、后台应用影响，不代表这个型号，
 * 更不代表其他机型。阈值是经验值，不是厂商标准。
 */
internal enum class ThermalGate { OK, UNKNOWN, HOT }

internal enum class LivePreviewAdvice { ON, OFF, UNSURE }

internal data class BenchmarkAdvice(
    val livePreview: LivePreviewAdvice,
    val edge: Int,
    /** 测试时的不确定因素（发热、读不到热状态、其他尺寸是换算的）。 */
    val caveats: List<String>,
)

internal data class BenchmarkResult(
    val testEdge: Int,
    val firstPreviewMs: Long,
    /** 切换风格并出一帧实时预览，几次的中位数。 */
    val switchStyleMs: Long,
    val exportMs: Long,
    /** 测试期间进程峰值 RSS 比开始时多出的 MB；-1 = 读不到。 */
    val peakGrowthMb: Long,
    val thermalBefore: Float,
    val thermalAfter: Float,
    val advice: BenchmarkAdvice,
    val finishedAt: Long,
)

internal object BenchmarkPolicy {
    /** 与 ExportPacer.HOT_HEADROOM、EditorViewModel.kHotHeadroom 同一条线：热余量越大越热，1.0 是严重节流。 */
    const val HOT_LINE = 0.85f

    /** 固定负载：每台机器都用同一张合成图、同一个尺寸，结果才能互相比较。 */
    const val TEST_EDGE = 3072

    /** 切换风格一次在这以内，拖滑块基本跟手。 */
    const val LIVE_OK_MS = 80L
    const val LIVE_BAD_MS = 200L

    /** 一张照片导出能接受的上限；超过就推荐小一档。 */
    const val EXPORT_BUDGET_MS = 12_000L

    const val DISCLAIMER = "这是本机本次的测量，受当时温度、电量和后台应用影响，不代表其他机型，也不代表下次结果。"

    fun thermalGate(headroom: Float, hotLine: Float = HOT_LINE): ThermalGate = when {
        !headroom.isFinite() -> ThermalGate.UNKNOWN
        headroom >= hotLine -> ThermalGate.HOT
        else -> ThermalGate.OK
    }

    /**
     * 建议。其他尺寸的耗时和内存按像素数线性换算，不是实测；测试结束时偏热，
     * 就不建议比测试尺寸更大的档位。[peakBudgetMb] 为 0 表示不知道，不按内存筛。
     */
    fun advise(
        testEdge: Int,
        switchStyleMs: Long,
        exportMs: Long,
        peakGrowthMb: Long,
        thermalAfter: Float,
        maxPixels: Long,
        peakBudgetMb: Long,
        hotLine: Float = HOT_LINE,
    ): BenchmarkAdvice {
        val caveats = mutableListOf<String>()
        val gate = thermalGate(thermalAfter, hotLine)
        if (gate == ThermalGate.HOT) caveats += tr("测试结束时机身已偏热，耗时可能偏长，降温后重测更可靠。")
        if (gate == ThermalGate.UNKNOWN) caveats += tr("这台机器读不到热状态，无法判断测试时是否发热。")

        val live = when {
            switchStyleMs <= LIVE_OK_MS -> LivePreviewAdvice.ON
            switchStyleMs >= LIVE_BAD_MS && gate != ThermalGate.HOT -> LivePreviewAdvice.OFF
            else -> LivePreviewAdvice.UNSURE
        }

        val gpuCeiling = DeviceProfile.recommendedEdge(if (maxPixels > 0) maxPixels else DeviceProfile.MAX_CANVAS_PIXELS)
        val ceiling = if (gate == ThermalGate.HOT) minOf(gpuCeiling, testEdge) else gpuCeiling
        val edge = DeviceProfile.EDGES.filter { it <= ceiling }.filter { e ->
            val scale = (e.toDouble() / testEdge).let { it * it }
            val timeOk = exportMs * scale <= EXPORT_BUDGET_MS
            val memOk = peakGrowthMb < 0 || peakBudgetMb <= 0 || peakGrowthMb * scale <= peakBudgetMb
            timeOk && memOk
        }.maxOrNull() ?: DeviceProfile.EDGES.first()
        if (edge != testEdge) caveats += tr("{0} px 的耗时和内存是按像素数从 {1} px 的结果换算的，不是实测。", edge, testEdge)
        return BenchmarkAdvice(live, edge, caveats)
    }

    private fun headroom(h: Float) = if (h.isFinite()) String.format(Locale.ROOT, "%.2f", h) else tr("未知")

    /** 设置页里给用户看的文字，每行一条。 */
    fun lines(r: BenchmarkResult): List<String> = buildList {
        add(tr("首次预览 {0} ms", r.firstPreviewMs))
        add(tr("切换风格 {0} ms", r.switchStyleMs))
        add(tr("导出 {0} ms（{1} px 合成图，不写入相册）", r.exportMs, r.testEdge))
        add(if (r.peakGrowthMb >= 0) tr("峰值内存增加 {0} MB", r.peakGrowthMb) else tr("峰值内存 读不到"))
        add(tr("热余量 {0} → {1}", headroom(r.thermalBefore), headroom(r.thermalAfter)))
        add(when (r.advice.livePreview) {
            LivePreviewAdvice.ON -> tr("实时预览：可以开")
            LivePreviewAdvice.OFF -> tr("实时预览：这次测下来偏慢，建议关")
            LivePreviewAdvice.UNSURE -> tr("实时预览：这次数据不足以判断")
        })
        add(tr("工作尺寸：这次测下来 {0} px 比较合适", r.advice.edge))
        addAll(r.advice.caveats.map { tr(it) })
        add(tr(DISCLAIMER))
    }

    /** 并入「复制诊断信息」的几行，沿用诊断里的英文风格。 */
    fun diagnostics(r: BenchmarkResult): String = buildString {
        appendLine("Benchmark (this device, this run only; not a verdict on other devices): synthetic ${r.testEdge}px")
        appendLine("Benchmark timings: first preview ${r.firstPreviewMs} ms, style switch ${r.switchStyleMs} ms, export ${r.exportMs} ms, peak RSS growth ${r.peakGrowthMb} MB")
        appendLine("Benchmark thermal headroom: ${headroom(r.thermalBefore)} -> ${headroom(r.thermalAfter)}")
        appendLine("Benchmark advice: live preview ${r.advice.livePreview}, working edge ${r.advice.edge}")
    }
}
