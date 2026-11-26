package com.dearded.spektralab.advisor.session

import com.dearded.spektralab.advisor.domain.AdviceConstraints
import com.dearded.spektralab.advisor.domain.AdviceParameter
import com.dearded.spektralab.advisor.domain.AdviceStatisticsValues
import com.dearded.spektralab.advisor.domain.EditSnapshot
import com.dearded.spektralab.advisor.domain.ParameterRange
import com.dearded.spektralab.advisor.domain.effectiveValueChanged

internal fun cinematicConstraints(snapshot: EditSnapshot, hard: AdviceConstraints, statistics: AdviceStatisticsValues?): AdviceConstraints {
    if (statistics == null) return AdviceConstraints(emptyMap(), hard.revision)
    val (dark, mid, bright) = statistics.lumaQuantiles
    val crowdedBlacks = statistics.nearBlack >= .005
    val crowdedWhites = statistics.nearWhite >= .005
    val shadowLimited = dark < .075 || crowdedBlacks
    val highlightLimited = bright > .92 || crowdedWhites
    val channelEnds = listOf(statistics.red, statistics.green, statistics.blue)
        .maxOf { maxOf(it.first(), it.last()) }
    val spread = bright - dark
    // P2-CINE-01: even +2 contrast clips both ends of a full-range neutral ramp.
    // P2-CINE-03: a dark median or a crowded RGB endpoint can hide clipping from luma quantiles.
    val contrast = when {
        shadowLimited || highlightLimited || channelEnds >= .05 || mid < .25 || spread >= .68 -> 0.0
        dark >= .16 && bright <= .82 && spread < .48 && statistics.nearBlack < .0015 -> 4.0
        else -> 2.0
    }
    val look = snapshot.edit.look
    val effective = look.resolved()
    val ranges = buildMap {
        fun allow(parameter: AdviceParameter, delta: Double) {
            val lockedRange = hard.ranges[parameter] ?: return
            val current = parameter.read(look)
            val low = maxOf(lockedRange.minimum, minOf(current, current + delta))
            val high = minOf(lockedRange.maximum, maxOf(current, current + delta))
            if (low > high) return
            val baseline = parameter.read(effective)
            fun moves(target: Double): Boolean {
                val resolved = parameter.read(parameter.write(look, target).resolved())
                return effectiveValueChanged(baseline, resolved)
            }
            val decreasing = delta < 0
            val far = if (decreasing) low else high
            val near = if (decreasing) high else low
            if (!moves(far)) return
            if (moves(near) || (near == current && baseline > parameter.minimum && baseline < parameter.maximum)) {
                put(parameter, ParameterRange(low, high))
                return
            }
            // The cinematic scalars resolve monotonically; trim the flat part at the clamp.
            var unchanged = near
            var changed = far
            repeat(32) {
                val midpoint = (unchanged + changed) / 2
                if (moves(midpoint)) changed = midpoint else unchanged = midpoint
            }
            put(parameter, if (decreasing) ParameterRange(low, changed) else ParameterRange(changed, high))
        }
        val palette = (look.palette / 100).coerceIn(0.0, 1.0)
        if (contrast > 0 && palette > 0) {
            // Restore only part of the lost response without changing the style strength.
            val restored = minOf(.5, contrast * (1 - palette), contrast * .5 * palette)
            allow(AdviceParameter.CONTRAST, contrast + restored / palette)
        }
        // A dark or bright tail can block extra contrast without requiring tone compression.
        if (crowdedWhites && bright - mid >= .08) allow(AdviceParameter.HIGHLIGHTS, -8.0)
        if (crowdedBlacks && mid - dark >= .07) allow(AdviceParameter.SHADOWS, 5.0)
        if (contrast > 0 && dark >= .24) allow(AdviceParameter.SHADOWS, -3.0)
        if (contrast > 0) {
            var colored = 0.0
            var strong = 0.0
            statistics.coarseRgb.forEachIndexed { i, fraction ->
                val r = i / 16; val g = i / 4 % 4; val b = i % 4
                val separation = maxOf(r, g, b) - minOf(r, g, b)
                if (separation > 0) colored += fraction
                if (separation >= 2) strong += fraction
            }
            if (colored >= .15 && strong < .05) allow(AdviceParameter.VIBRANCE, 4.0)
        }
    }
    return AdviceConstraints(ranges, hard.revision)
}

internal val CINEMATIC_RECOMMENDATION_GOAL = """
    执行范围：下面是完整的摄影后期目标，本轮只做全局参数。没有原图或空间数据，不推断主体、光向或局部位置，不执行蒙版、渐变、清理或修补。允许范围是根据当前成片统计给出的绝对存储值，不是增量，也不是调满的目标。只选有依据的最少改动：反差足够就不再加；亮暗端余量不足时优先保护现有层次，不承诺恢复已剪切细节。只有颜色偏弱且有余量时才轻调鲜艳度。不改变曝光和白平衡；无需调整时返回 no_change，无法决定取舍时先澄清。

    严格保留原图主体身份、构图、透视、结构、材质、纹理及真实反射，仅通过光色、亮度、对比度与蒙版进行摄影后期，绝不重绘物体。

    以顶级商业摄影标准，自适应分析原图光照与空间关系，主动采用更大胆、更深沉的明暗塑造，允许大面积暗部接近深黑，显著提高整体光比，但保留必要层次。

    以大光影统领小光影，优先使用少量高强度、超大块羽化的方向性线性渐变。严格遵循同轴互补原则：整张画面只建立一条贯穿全局的主明暗轴线，主光方向形成连续、完整、宽阔的大面积亮部，亮度最高的一侧必须形成覆盖整个对应方向的大面积连续亮区，而非仅在角落、边缘或局部形成一束亮光；亮部应沿整条明暗轴线的垂直方向充分铺展，保持同一明暗位置上的亮度整体一致，不得向两侧衰减形成聚光效果。反方向形成连续、完整、深沉的大面积暗部，两者之间仅通过一条超宽、平滑、自然的羽化过渡带衔接。所有明暗变化必须沿同一轴线单向、连续、渐进地变化，同一轴线位置上的不同区域必须服从统一的亮度分布，不得出现横向收窄、局部亮度峰值或亮区面积不足。不得出现独立亮区、局部聚光、环绕主体的亮圈或多个互不连贯的明暗区域。亮部与暗部必须各自构成完整统一的大块面，而非多个局部蒙版拼接。禁止夹光、交叉压暗及零碎分区。

    主体内部同样进行多区域塑形，不以整体提亮主体或机械暗角代替精细蒙版。主体与环境必须共同服从同一条全局明暗轴线，主体内部的精细塑形只能强化原有结构和材质层次，不得破坏整体光场的连续性，不得为了突出主体而单独提亮主体或压暗其周围环境。重点保护主体关键轮廓、高光及材质细节，非关键区域大胆沉入暗部。亮部清透克制，暗部厚重纯净，中间调适度收缩，形成鲜明视觉张力。

    禁止新增光源、无线性渐变感、地面反光、全局压暗、畸形光影、光照割裂感、投影、虚假反射、机械暗角、碎光斑、孤立聚光、HDR及过度锐化。智能清理、修补画面视觉干扰，原色去灰增强，最终呈现大面积深暗、高对比、局部通透、干净、强方向、强反差且自然真实的高级电影级摄影质感。
""".trimIndent()
