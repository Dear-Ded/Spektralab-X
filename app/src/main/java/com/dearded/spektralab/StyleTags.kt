package com.dearded.spektralab

import java.util.Locale

/*
 * Folk names for the one-tap styles (docs/USER_VOICES.md: 风格名带俗称标签,
 * "富士味" "CCD" "徕卡味" are what Bilibili tutorials are titled with), used
 * only as search keywords. Never shown as a style's name or badge: maker names
 * stay out of display names (docs/STYLES.md section 1, UI.md appendix B); a
 * search for one simply finds the styles inspired by it.
 */

/** Search keywords for this style: its own folk names plus those of its group. */
val Style.tags: List<String> get() = TAGS[id].orEmpty() + GROUP_TAGS[group].orEmpty()

/** Whether [query] (any case, spaces between words mean all of them) finds this style. */
fun Style.matchesQuery(query: String, filmLabel: String = ""): Boolean {
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = (listOf(name, inspiredBy, group, filmLabel) + tags).joinToString("\u0000").lowercase(Locale.ROOT)
    return words.all { it in hay }
}

private val GROUP_TAGS = mapOf(
    "实验室" to listOf("冲扫", "胶片感", "彩负"),
    "相机" to listOf("相机直出", "胶片模拟"),
    "负片" to listOf("胶片感", "彩负", "负片"),
    "反转" to listOf("胶片感", "反转片", "正片"),
    "电影" to listOf("电影感", "电影卷"),
    "黑白" to listOf("黑白", "单色", "胶片感"),
    "手机" to listOf("手机直出"),
    "摄影师" to listOf("大师", "胶片感"),
)

private val TAGS: Map<String, List<String>> = mapOf(
    // 实验室
    "lab_gold" to listOf("Frontier", "暖调", "日常"),
    "lab_airy" to listOf("Noritsu", "日系", "清新", "粉调"),
    "lab_wedding" to listOf("婚礼", "人像", "日系", "清新"),
    "lab_vivid" to listOf("风光", "鲜艳"),
    "lab_daily" to listOf("日常", "扫街"),
    // 相机
    "cam_chrome" to listOf("富士味", "富士", "纪实", "扫街", "低饱和"),
    "cam_classic_neg" to listOf("富士味", "富士", "日系", "复古"),
    "cam_nostalgic" to listOf("富士味", "富士", "暖调", "复古", "怀旧"),
    "cam_eterna" to listOf("富士味", "富士", "电影感"),
    "cam_bleach" to listOf("富士味", "富士", "电影感", "高反差", "低饱和"),
    "cam_astia" to listOf("富士味", "富士", "人像"),
    "cam_cine_warm" to listOf("徕卡味", "徕卡", "电影感", "暖调"),
    "cam_bright_shadow" to listOf("徕卡味", "徕卡", "明亮"),
    "cam_teal" to listOf("徕卡味", "徕卡", "青调", "夜景"),
    "cam_posi" to listOf("理光", "GR", "扫街", "正片"),
    "cam_nega" to listOf("理光", "GR", "扫街", "负片"),
    "cam_cinema_y" to listOf("理光", "GR", "适马", "电影感"),
    "cam_fl" to listOf("索尼", "冷调"),
    "cam_instant" to listOf("索尼", "哑光", "拍立得"),
    "cam_skin" to listOf("佳能", "人像", "肤色"),
    "cam_vivid_print" to listOf("尼康", "风光", "鲜艳"),
    "cam_denim" to listOf("尼康", "蓝调"),
    "cam_classicneo" to listOf("松下", "复古", "清新"),
    "cam_pale" to listOf("奥林巴斯", "宾得", "清新", "日系", "淡雅"),
    "cam_cross" to listOf("奥林巴斯", "宾得", "交叉冲洗", "复古", "lomo"),
    "cam_teal_orange" to listOf("适马", "青橙", "电影感"),
    "cam_powder" to listOf("适马", "清新", "蓝调"),
    "cam_forest" to listOf("适马", "绿色", "风光"),
    "cam_satobi" to listOf("宾得", "昭和", "复古", "日系"),
    "cam_gold_blue" to listOf("宾得", "金色", "暖调"),
    // 负片
    "neg_amber" to listOf("暖调", "怀旧", "日常"),
    "neg_hard" to listOf("高反差"),
    "neg_cool" to listOf("冷调"),
    "neg_soft" to listOf("清新", "日系"),
    "neg_portrait" to listOf("人像", "日系", "富士"),
    "neg_natural" to listOf("中画幅", "人像"),
    "neg_classic" to listOf("暗角", "复古"),
    "neg_push1" to listOf("增感", "夜景"),
    "neg_push2" to listOf("增感", "夜景", "颗粒"),
    "neg_night" to listOf("夜景", "人像"),
    "neg_pull" to listOf("减感", "柔和"),
    "neg_expired" to listOf("过期", "复古", "lomo", "怀旧"),
    // 反转
    "slide_positive" to listOf("富士"),
    "slide_velvia" to listOf("风光", "鲜艳", "富士"),
    "slide_documentary" to listOf("杂志", "复古", "纪实"),
    "slide_kodachrome" to listOf("柯达", "复古", "扫街"),
    "slide_dark" to listOf("风光", "浓郁"),
    // 电影
    "cine_soft" to listOf("柔和"),
    "cine_night" to listOf("夜景", "霓虹", "港风", "赛博"),
    "cine_bypass" to listOf("留银", "高反差", "低饱和"),
    "cine_day" to listOf("日光"),
    "cine_verita" to listOf("浓郁", "鲜艳"),
    "cine_lamp" to listOf("灯光"),
    "cine_dark" to listOf("夜景", "暗调"),
    // 黑白
    "bw_silver" to listOf("富士", "银盐", "细腻"),
    "bw_street" to listOf("扫街", "纪实"),
    "bw_harbour" to listOf("港风", "扫街"),
    "bw_soft" to listOf("柔和"),
    "bw_selenium" to listOf("徕卡", "硒调"),
    "bw_sepia" to listOf("棕调", "复古", "富士", "索尼"),
    "bw_hicon" to listOf("理光", "GR", "高反差", "扫街"),
    "bw_deep" to listOf("尼康", "暗调"),
    "bw_dynamic" to listOf("松下", "高反差"),
    "bw_grainy" to listOf("奥林巴斯", "颗粒", "高反差"),
    "bw_push" to listOf("增感", "颗粒", "夜景"),
    // 手机
    "ph_authentic" to listOf("徕卡味", "徕卡", "小米"),
    "ph_vibrant" to listOf("徕卡味", "徕卡", "小米", "鲜艳"),
    "ph_ccd" to listOf("CCD", "ccd味", "徕卡", "小米", "复古"),
    "ph_mono50" to listOf("徕卡", "小米", "黑白"),
    "ph_hncs" to listOf("哈苏", "OPPO", "一加", "自然"),
    "ph_zeiss_natural" to listOf("蔡司", "vivo", "自然"),
    "ph_zeiss_vivid" to listOf("蔡司", "vivo", "鲜艳"),
    "ph_xmage" to listOf("华为", "XMAGE", "自然"),
    "ph_xmage_bright" to listOf("华为", "XMAGE", "明亮"),
    "ph_bright" to listOf("三星", "明亮"),
    "ph_cool" to listOf("Pixel", "谷歌", "冷调", "高反差"),
    "ph_studio_bw" to listOf("黑白", "人像", "影棚"),
    "ph_amber" to listOf("苹果", "iPhone", "暖调"),
    "ph_rose" to listOf("苹果", "iPhone", "粉调"),
    "ph_quiet" to listOf("苹果", "iPhone", "淡雅"),
    "ph_dramatic" to listOf("苹果", "iPhone", "高反差"),
    // 摄影师
    "m_snow" to listOf("雪景", "扫街"),
    "m_dye" to listOf("鲜艳", "美式"),
    "m_road" to listOf("大画幅", "美式", "公路"),
    "m_cape" to listOf("大画幅", "海边"),
    "m_airy" to listOf("婚礼", "日系", "清新"),
    "m_flash" to listOf("闪光灯", "日记"),
)
