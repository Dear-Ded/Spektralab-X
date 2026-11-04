package com.dearded.spektralab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A maker whose mark can go on the data bar. [art] names the logo files in
 * assets/logos (logo_<art>_mono.xml / _color.xml); a co-branded mark has two.
 * Every logo is a trademark of its owner; sources are in docs/LOGOS.md.
 */
data class Brand(val id: String, val name: String, val kind: Kind, val art: List<String> = listOf(id)) {
    enum class Kind { CAMERA, PHONE, COLLAB, FILM }
}

/** What goes in the logo slot: a brand's logo, or plain text. */
sealed interface Mark {
    data class Logo(val brand: Brand) : Mark
    data class Word(val text: String) : Mark
}

object Brands {
    private val CAMERA = Brand.Kind.CAMERA
    private val PHONE = Brand.Kind.PHONE
    private val COLLAB = Brand.Kind.COLLAB
    private val FILM = Brand.Kind.FILM

    val ALL = listOf(
        Brand("leica", "Leica", CAMERA), Brand("hasselblad", "Hasselblad", CAMERA), Brand("canon", "Canon", CAMERA),
        Brand("nikon", "Nikon", CAMERA), Brand("sony", "Sony", CAMERA), Brand("fujifilm", "Fujifilm", CAMERA),
        Brand("lumix", "Lumix", CAMERA), Brand("panasonic", "Panasonic", CAMERA), Brand("olympus", "Olympus", CAMERA),
        Brand("omsystem", "OM System", CAMERA), Brand("ricoh", "Ricoh", CAMERA), Brand("pentax", "Pentax", CAMERA),
        Brand("sigma", "Sigma", CAMERA), Brand("zeiss", "ZEISS", CAMERA), Brand("dji", "DJI", CAMERA),
        Brand("insta360", "Insta360", CAMERA),
        Brand("apple", "Apple", PHONE), Brand("xiaomi", "Xiaomi", PHONE), Brand("vivo", "vivo", PHONE),
        Brand("oppo", "OPPO", PHONE), Brand("oneplus", "OnePlus", PHONE), Brand("huawei", "Huawei", PHONE),
        Brand("xmage", "XMAGE", PHONE), Brand("honor", "Honor", PHONE), Brand("samsung", "Samsung", PHONE),
        Brand("pixel", "Google Pixel", PHONE),
        // Co-branded marks: only ever picked by hand (a phone's EXIF names the phone maker alone).
        Brand("xiaomi_leica", "Xiaomi × Leica", COLLAB, listOf("xiaomi", "leica")),
        Brand("vivo_zeiss", "vivo × ZEISS", COLLAB, listOf("vivo", "zeiss")),
        Brand("oppo_hasselblad", "OPPO × Hasselblad", COLLAB, listOf("oppo", "hasselblad")),
        Brand("huawei_xmage", "Huawei × XMAGE", COLLAB, listOf("huawei", "xmage")),
        Brand("kodak", "Kodak", FILM), Brand("ilford", "Ilford", FILM), Brand("harman", "Harman", FILM),
        Brand("cinestill", "CineStill", FILM), Brand("agfa", "Agfa", FILM), Brand("lomography", "Lomography", FILM),
        Brand("polaroid", "Polaroid", FILM),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /**
     * The brand an EXIF Make (and Model) names, or null. Make is matched
     * trimmed and lower-cased by containment ("NIKON CORPORATION", "RICOH
     * IMAGING COMPANY, LTD."); Model settles the makers that share one:
     * Olympus and OM System, Ricoh and Pentax.
     */
    fun detect(make: String, model: String): Brand? {
        val d = model.trim().lowercase()
        val s = make.trim().lowercase().ifEmpty { d }
        if (s.isEmpty()) return null
        fun has(vararg k: String) = k.any { s.contains(it) }
        val id = when {
            has("om digital") -> "omsystem"
            has("olympus") -> if (Regex("^om-\\d").containsMatchIn(d)) "omsystem" else "olympus"
            has("pentax") -> "pentax"
            has("ricoh") -> if (d.startsWith("pentax") || Regex("^(k-|kp|k\\d|645)").containsMatchIn(d)) "pentax" else "ricoh"
            has("panasonic", "lumix") -> "lumix"
            has("leica") -> "leica"
            has("hasselblad") -> "hasselblad"
            has("canon") -> "canon"
            has("nikon") -> "nikon"
            has("sony") -> "sony"
            has("fujifilm") || s.startsWith("fuji") -> "fujifilm"
            has("sigma") -> "sigma"
            has("zeiss") -> "zeiss"
            has("dji") -> "dji"
            has("insta360", "arashi vision") -> "insta360"
            has("apple") || s.startsWith("iphone") -> "apple"
            has("xiaomi") -> "xiaomi"
            has("oneplus") -> "oneplus"
            has("oppo") -> "oppo"
            has("vivo") -> "vivo"
            has("huawei") -> "huawei"
            has("honor") -> "honor"
            has("samsung") -> "samsung"
            has("google") || s.startsWith("pixel") -> "pixel"
            else -> null
        }
        return id?.let(::byId)
    }

    /** The film maker in a stock's label ("Kodak Portra 400", "CineStill 800T"), or null. */
    fun filmBrand(label: String): Brand? {
        val s = label.trim().lowercase()
        val id = when {
            s.startsWith("kodak") -> "kodak"
            s.startsWith("fujifilm") || s.startsWith("fuji ") -> "fujifilm"
            s.startsWith("ilford") -> "ilford"
            s.startsWith("harman") -> "harman"
            s.startsWith("cinestill") -> "cinestill"
            s.startsWith("agfa") -> "agfa"
            s.startsWith("lomo") -> "lomography"
            s.startsWith("polaroid") -> "polaroid"
            else -> null
        }
        return id?.let(::byId)
    }

    /**
     * The camera mark [spec] asks for on a photo with caption [c]: auto
     * (from EXIF; an unknown maker is written out), a brand picked by hand,
     * the user's own text, or nothing.
     */
    fun cameraMark(spec: FrameSpec, c: Caption): Mark? = when (spec.brand) {
        "", "none" -> null
        "custom" -> spec.brandText.trim().takeIf { it.isNotEmpty() }?.let { Mark.Word(it) }
        "auto" -> detect(c.make, c.exifModel)?.let { Mark.Logo(it) } ?: makerWord(c.make)
        else -> byId(spec.brand)?.let { Mark.Logo(it) }
    }

    /** The film maker's mark, from the film on the print (not from EXIF). */
    fun filmMark(c: Caption): Mark? = filmBrand(c.film)?.let { Mark.Logo(it) }

    // "SOME CAMERA CO., LTD." -> "SOME"
    private fun makerWord(make: String): Mark? =
        make.trim().split(Regex("[\\s,]+")).firstOrNull()?.takeIf { it.isNotEmpty() }?.let { Mark.Word(it) }

    /**
     * The name a phone is sold under, for a model code written in EXIF
     * ("23127PN0CC" -> "Xiaomi 14"); anything not in the table comes back as
     * written. Codes are from Google Play's supported-devices list.
     */
    fun marketName(model: String): String {
        val m = model.trim()
        return MODELS[m.uppercase()] ?: m
    }

    private val MODELS: Map<String, String> by lazy {
        val out = HashMap<String, String>(1024)
        for ((_, table) in MODEL_TABLES) for (e in table.split('|')) {
            val i = e.indexOf('=')
            if (i > 0) out[e.substring(0, i)] = e.substring(i + 1)
        }
        out
    }

    // Generated from https://storage.googleapis.com/play_public/supported_devices.csv
    // (2026-11-03): flagship lines only, code=marketing name.
    private val MODEL_TABLES = listOf(
        "xiaomi" to (
            "2107113SG=Xiaomi 11T Pro|2107113SI=Xiaomi 11T Pro|2107113SR=Xiaomi 11T Pro|2107119DC=Mi 11 LE|" +
            "21081111RG=Xiaomi 11T|21091116I=Xiaomi 11i|21091116UI=Xiaomi 11i HyperCharge|" +
            "2109119BC=Xiaomi Civi|2109119DG=Xiaomi 11 Lite 5G NE|2109119DI=Xiaomi 11 Lite NE|" +
            "2112123AC=Xiaomi 12X|2201122C=Xiaomi 12 Pro|2201122G=Xiaomi 12 Pro|2201123C=Xiaomi 12|" +
            "2201123G=Xiaomi 12|2203121C=Xiaomi 12S Ultra|2203129G=Xiaomi 12 Lite|22061218C=Xiaomi MIX Fold 2|" +
            "2206122SC=Xiaomi 12S Pro|2206123SC=Xiaomi 12S|22071212AG=Xiaomi 12T|" +
            "2207122MC=Xiaomi 12 Pro Dimensity|22081212C=Xiaomi 12T Pro|22081212R=Xiaomi 12T Pro|" +
            "22081212UG=Xiaomi 12T Pro|2210129SG=Xiaomi 13 Lite|2210132C=Xiaomi 13 Pro|2210132G=Xiaomi 13 Pro|" +
            "2211133C=Xiaomi 13|2211133G=Xiaomi 13|22200414R=Xiaomi 12T Pro|2304FPN6DC=Xiaomi 13 Ultra|" +
            "2304FPN6DG=Xiaomi 13 Ultra|2306EPN60G=Xiaomi 13T|23078PND5G=Xiaomi 13T Pro|" +
            "23088PND5R=Xiaomi 13T Pro|2308CPXD0C=Xiaomi MIX Fold 3|23116PN5BC=Xiaomi 14 Pro|" +
            "2311BPN23C=Xiaomi 14 Pro|23127PN0CC=Xiaomi 14|23127PN0CG=Xiaomi 14|24030PN60G=Xiaomi 14 Ultra|" +
            "24031PN0DC=Xiaomi 14 Ultra|24053PY09C=Xiaomi Civi 4|24053PY09I=Xiaomi 14 Civi|" +
            "2405CPX3DC=Xiaomi MIX Flip|2405CPX3DG=Xiaomi MIX Flip|2406APNFAG=Xiaomi 14T|" +
            "24072PX77C=Xiaomi MIX Fold 4|2407FPN8EG=Xiaomi 14T Pro|2407FPN8ER=Xiaomi 14T Pro|" +
            "2410DPN6CC=Xiaomi 15 Pro|24129PN74C=Xiaomi 15|24129PN74G=Xiaomi 15|24129PN74I=Xiaomi 15|" +
            "25010PN30C=Xiaomi 15 Ultra|25010PN30G=Xiaomi 15 Ultra|25010PN30I=Xiaomi 15 Ultra|" +
            "25019PNF3C=Xiaomi 15 Ultra|25042PN24C=Xiaomi 15S Pro|25067PYE3C=Xiaomi Civi 5 Pro|" +
            "25069PTEBG=Xiaomi 15T|2506BPN68G=Xiaomi 15T Pro|2506BPN68R=Xiaomi 15T Pro|" +
            "25098PN5AC=Xiaomi 17 Pro|2509FPN0BC=Xiaomi 17 Pro Max|25113PN0EC=Xiaomi 17|25113PN0EG=Xiaomi 17|" +
            "25113PN0EI=Xiaomi 17|25128PNA1C=Xiaomi 17 Ultra|2512BPNDAC=Xiaomi 17 Ultra|" +
            "2512BPNDAG=Xiaomi 17 Ultra|2512BPNDAI=Xiaomi 17 Ultra|2602DPT53G=Xiaomi 17T|" +
            "2602DPT53I=Xiaomi 17T|2602EPTC0G=Xiaomi 17T Pro|2602EPTC0R=Xiaomi 17T Pro|" +
            "2605EPN8EC=Xiaomi 17 Max|2608BPX34C=Xiaomi 18 Fold|A201XM=Xiaomi 12T Pro|A301XM=Xiaomi 13T Pro|" +
            "A402XM=Xiaomi 14T Pro|M025EC=Xiaomi 17T Pro|M154FF=Xiaomi 18 Pro Max|M2002J9E=Mi 10 Lite zoom|" +
            "M2002J9G=Mi 10 Lite 5G|M2002J9S=Mi 10 Lite 5G|M2007J17G=Mi 10T Lite|M2007J17I=Mi 10i|" +
            "M2007J1SC=Mi 10 Ultra|M2007J3SG=Mi 10T Pro|M2007J3SI=Mi 10T Pro|M2007J3SP=Mi 10T|" +
            "M2007J3SY=Mi 10T|M2011J18C=Xiaomi MIX Fold|M2011K2C=Mi 11|M2011K2G=Mi 11|M2012K11G=Mi 11i|" +
            "M2012K11I=Mi 11X Pro|M2101K9AG=Mi 11 Lite|M2101K9AI=Mi 11 Lite|M2101K9C=Mi 11 Lite 5G|" +
            "M2101K9G=Mi 11 Lite 5G|M2102J2SC=Mi 10S|M2102K1AC=Mi 11 Pro|M2102K1C=Mi 11 Ultra|" +
            "M2102K1G=Mi 11 Ultra|M531DA=Xiaomi 17T|M610BB=Xiaomi 18 Pro|MI-4C=Mi 4c|XIG01=Mi 10 Lite 5G|" +
            "XIG04=Xiaomi 13T|XIG07=Xiaomi 14T"),
        "oppo" to (
            "CPH1871=OPPO Find X|CPH1875=OPPO Find X|CPH2005=OPPO Find X2 Lite|" +
            "CPH2009=OPPO Find X2 Neo/Reno3 Pro|CPH2023=OPPO Find X2|CPH2025=OPPO Find X2 Pro|" +
            "CPH2173=OPPO Find X3 Pro|CPH2305=OPPO Find X5 Pro|CPH2307=OPPO Find X5|CPH2651=OPPO Find X8|" +
            "CPH2659=OPPO Find X8 Pro|CPH2791=OPPO Find X9 Pro|CPH2797=OPPO Find X9|" +
            "CPH2841=OPPO Find X9 Ultra|CPH2873=OPPO Find X9s|CPH2911=OPPO Find X10|" +
            "CPH2913=OPPO Find X10 Pro Max|OPG01=OPPO Find X2 Pro|OPG03=OPPO Find X3 Pro|OPG07=OPPO Find X9|" +
            "OPG09=OPPO Find X10|PAFM00=OPPO Find X|PAFT00=OPPO Find X|PAHM00=OPPO Find X|PDEM10=OPPO Find X2|" +
            "PDEM30=OPPO Find X2 Pro|PDET10=OPPO Find X2|PEDM00=OPPO Find X3|PEEM00=OPPO Find X3 Pro|" +
            "PGEM10=OPPO Find X6 Pro|PGFM10=OPPO Find X6|PHY110=OPPO Find X7 Ultra|PHY120=OPPO Find X7 Ultra|" +
            "PHZ110=OPPO Find X7|PKB110=OPPO Find X8|PKC110=OPPO Find X8 Pro|PKC130=OPPO Find X8 Pro|" +
            "PKJ110=OPPO Find X8 Ultra|PKT110=OPPO Find X8s|PKU110=OPPO Find X8 Ultra|PLB110=OPPO Find X8s+|" +
            "PLG110=OPPO Find X9 Pro|PLG120=OPPO Find X9 Pro|PLJ110=OPPO Find X9|PMA110=OPPO Find X9 Ultra|" +
            "PMA120=OPPO Find X9 Ultra|PME110=OPPO Find X9s Pro|PMW110=OPPO Find X10|" +
            "PMX110=OPPO Find X10 Pro Max|PNA110=OPPO Find X10 E"),
        "vivo" to (
            "V2114=vivo X70 Pro+|V2144=vivo X80|V2145=vivo X80 Pro|V2178A=vivo X Fold|V2183A=vivo X80|" +
            "V2185A=vivo X80 Pro|V2186A=vivo X80 Pro|V2208=vivo X80 Lite 5G|V2218=vivo X90|V2219=vivo X90 Pro|" +
            "V2227A=vivo X90 Pro+|V2229A=vivo X Fold+|V2241A=vivo X90|V2241HA=vivo X90s|V2242A=vivo X90 Pro|" +
            "V2266A=vivo X Fold2|V2303A=vivo X Fold3|V2308=vivo X100|V2309=vivo X100 Pro|V2309A=vivo X100|" +
            "V2324A=vivo X100 Pro|V2324HA=vivo X100s Pro|V2330=vivo X Fold3 Pro|V2337A=vivo X Fold3 Pro|" +
            "V2359A=vivo X100s|V2366GA=vivo X100 Ultra|V2366HA=vivo X100 Ultra|V2405A=vivo X200 Pro|" +
            "V2405DA=vivo X200 Pro|V2413=vivo X200 Pro|V2415=vivo X200|V2415A=vivo X200|" +
            "V2419A=vivo X200 Pro mini|V2429=vivo X Fold5|V2436A=vivo X Fold5|V2454A=vivo X200 Ultra|" +
            "V2454DA=vivo X200 Ultra|V2458A=vivo X200s|V2502A=vivo X300 Pro|V2502DA=vivo X300 Pro|" +
            "V2503=vivo X200 FE|V2505=vivo X200 FE|V2509A=vivo X300|V2514=vivo X300 Pro|V2515=vivo X300|" +
            "V2537=vivo X300 FE|V2542=vivo X300 FE|V2545A=vivo X Fold6|V2547A=vivo X300 Ultra|" +
            "V2547DA=vivo X300 Ultra|V2548A=vivo X300s|V2559=vivo X Fold6|V2561=vivo X200T|" +
            "V2562=vivo X300 Ultra|V2602A=vivo X500 Pro Max|V2602DA=vivo X500 Pro Max|V2608A=vivo X500 Pro|" +
            "V2608DA=vivo X500 Pro|V2609A=vivo X500|V2610=vivo X500 Pro Max|V2612A=vivo X300 E|" +
            "V2621=vivo X500 Pro|V2622=vivo X500 Pro|V2633=vivo X300 FE|V2656=vivo X500 E"),
        "oneplus" to (
            "CPH2573=OnePlus 12|CPH2581=OnePlus 12|CPH2583=OnePlus 12|CPH2649=OnePlus 13|CPH2653=OnePlus 13|" +
            "CPH2655=OnePlus 13|CPH2745=OnePlus 15|CPH2747=OnePlus 15|CPH2749=OnePlus 15|GM1900=OnePlus 7|" +
            "GM1901=OnePlus 7|GM1903=OnePlus 7|GM1905=OnePlus 7|GM1910=OnePlus 7 Pro|GM1911=OnePlus 7 Pro|" +
            "GM1913=OnePlus 7 Pro|GM1915=OnePlus 7 Pro|GM1917=OnePlus 7 Pro|GM1925=OnePlus 7 Pro|" +
            "IN2010=OnePlus 8|IN2011=OnePlus 8|IN2013=OnePlus 8|IN2015=OnePlus 8|IN2020=OnePlus 8 Pro|" +
            "IN2021=OnePlus 8 Pro|IN2023=OnePlus 8 Pro|IN2025=OnePlus 8 Pro|NE2210=OnePlus 10 Pro|" +
            "PJD110=OnePlus 12|PJZ110=OnePlus 13|PLK110=OnePlus 15|PYB110=OnePlus 16"),
        "huawei" to (
            "ANE-LX2J=HUAWEI P20 Lite|BLL-L23=HUAWEI Mate 9 Lite|ELE-AL00=HUAWEI P30|ELE-L04=HUAWEI P30|" +
            "ELE-L09=HUAWEI P30|ELE-L14=HUAWEI P30|ELE-L29=HUAWEI P30|ELE-L39=HUAWEI P30|ELE-L49=HUAWEI P30|" +
            "ELE-TL00=HUAWEI P30|EVR-AN00=HUAWEI Mate 20 X|EVR-N29=HUAWEI Mate 20 X|HMA-L09=HUAWEI Mate 20|" +
            "HMA-L29=HUAWEI Mate 20|HWV32=HUAWEI P20 Lite|HWV33=HUAWEI P30 Lite|LYA-L0C=HUAWEI Mate 20 Pro|" +
            "LYA-L29=HUAWEI Mate 20 Pro|MAR-LX1A=HUAWEI P30 Lite|MAR-LX1AM=HUAWEI P30 Lite|" +
            "MAR-LX1B=HUAWEI P30 Lite|MAR-LX1M=HUAWEI P30 Lite|MAR-LX1MM=HUAWEI P30 Lite|" +
            "MAR-LX2=HUAWEI P30 Lite|MAR-LX2B=HUAWEI P30 Lite|MAR-LX2M=HUAWEI P30 Lite|" +
            "MAR-LX3A=HUAWEI P30 Lite|MAR-LX3AM=HUAWEI P30 Lite|MAR-LX3BM=HUAWEI P30 Lite|" +
            "SNE-LX1=HUAWEI Mate 20 Lite"),
        "honor" to (
            "ALI-NX1=HONOR Magic6 Lite 5G|ALI-NX3=HONOR Magic6 Lite 5G|ANY-LX1=HONOR Magic4 Lite|" +
            "ANY-NX1=HONOR Magic4 Lite 5G|BKQ-N49=HONOR Magic8 Pro|BRP-NX1M=HONOR Magic7 Lite|" +
            "BRP-NX3=HONOR Magic7 Lite|BSN-NX1M=HONOR Magic9 Lite+|BSN-NX3=HONOR Magic9 Lite+|" +
            "BVL-AN00=HONOR Magic6|BVL-AN16=HONOR Magic6 Pro|BVL-N49=HONOR Magic6 Pro|" +
            "LGE-NX9=HONOR Magic4 Pro|MTN-NX1M=HONOR Magic8 Lite|MTN-NX3=HONOR Magic8 Lite|" +
            "PGT-N09=HONOR Magic5|PGT-N19=HONOR Magic5 Pro|PTP-N29=HONOR Magic7|PTP-N49=HONOR Magic7 Pro|" +
            "RMO-NX1=HONOR Magic5 Lite 5G|RMO-NX3=HONOR Magic5 Lite 5G"),
        "samsung" to (
            "SC-03L=Samsung Galaxy S10|SC-04L=Samsung Galaxy S10+|" +
            "SC-05L=Samsung Galaxy S10+ Olympic Games Edition|SC-51A=Samsung Galaxy S20 5G|" +
            "SC-51B=Samsung Galaxy S21 5G|SC-51C=Samsung Galaxy S22|SC-51D=Samsung Galaxy S23|" +
            "SC-51E=Samsung Galaxy S24|SC-51F=Samsung Galaxy S25|SC-51G=Samsung Galaxy S26|" +
            "SC-52A=Samsung Galaxy S20+ 5G|SC-52B=Samsung Galaxy S21 Ultra 5G|SC-52C=Samsung Galaxy S22 Ultra|" +
            "SC-52D=Samsung Galaxy S23 Ultra|SC-52E=Samsung Galaxy S24 Ultra|SC-52F=Samsung Galaxy S25 Ultra|" +
            "SC-52G=Samsung Galaxy S26+|SC-53G=Samsung Galaxy S26 Ultra|SC-54B=Samsung Galaxy Z Flip3 5G|" +
            "SC-54C=Samsung Galaxy Z Flip4|SC-54D=Samsung Galaxy Z Flip5|SC-54E=Samsung Galaxy Z Flip6|" +
            "SC-55B=Samsung Galaxy Z Fold3 5G|SC-55C=Samsung Galaxy Z Fold4|SC-55D=Samsung Galaxy Z Fold5|" +
            "SC-55E=Samsung Galaxy Z Fold6|SC-55F=Samsung Galaxy Z Flip7|SC-55G=Samsung Galaxy Z Flip8|" +
            "SC-56F=Samsung Galaxy Z Fold7|SC-56G=Samsung Galaxy Z Fold8 Ultra|SC-57G=Samsung Galaxy Z Fold8|" +
            "SC51AA=Samsung Galaxy S20 5G|SCG01=Samsung Galaxy S20 5G|SCG02=Samsung Galaxy S20+ 5G|" +
            "SCG03=Samsung Galaxy S20 Ultra 5G|SCG04=Samsung Galaxy Z Flip 5G|SCG09=Samsung Galaxy S21 5G|" +
            "SCG10=Samsung Galaxy S21+ 5G|SCG11=Samsung Galaxy Z Fold3 5G|SCG12=Samsung Galaxy Z Flip3 5G|" +
            "SCG13=Samsung Galaxy S22|SCG14=Samsung Galaxy S22 Ultra|SCG16=Samsung Galaxy Z Fold4|" +
            "SCG17=Samsung Galaxy Z Flip4|SCG19=Samsung Galaxy S23|SCG20=Samsung Galaxy S23 Ultra|" +
            "SCG22=Samsung Galaxy Z Fold5|SCG23=Samsung Galaxy Z Flip5|SCG24=Samsung Galaxy S23 FE|" +
            "SCG25=Samsung Galaxy S24|SCG26=Samsung Galaxy S24 Ultra|SCG28=Samsung Galaxy Z Fold6|" +
            "SCG29=Samsung Galaxy Z Flip6|SCG30=Samsung Galaxy S24 FE|SCG31=Samsung Galaxy S25|" +
            "SCG32=Samsung Galaxy S25 Ultra|SCG34=Samsung Galaxy Z Fold7|SCG35=Samsung Galaxy Z Flip7|" +
            "SCG36=Samsung Galaxy S26|SCG37=Samsung Galaxy S26 Ultra|SCG38=Samsung Galaxy S26+|" +
            "SCG39=Samsung Galaxy Z Fold8 Ultra|SCG40=Samsung Galaxy Z Flip8|SCG41=Samsung Galaxy Z Fold8|" +
            "SCV41=Samsung Galaxy S10|SCV42=Samsung Galaxy S10+|SCV47=Samsung Galaxy Z Flip|" +
            "SM-F7000=Samsung Galaxy Z Flip|SM-F700F=Samsung Galaxy Z Flip|SM-F700N=Samsung Galaxy Z Flip|" +
            "SM-F700U=Samsung Galaxy Z Flip|SM-F700U1=Samsung Galaxy Z Flip|SM-F700W=Samsung Galaxy Z Flip|" +
            "SM-F7070=Samsung Galaxy Z Flip 5G|SM-F707B=Samsung Galaxy Z Flip 5G|" +
            "SM-F707N=Samsung Galaxy Z Flip 5G|SM-F707U=Samsung Galaxy Z Flip 5G|" +
            "SM-F707U1=Samsung Galaxy Z Flip 5G|SM-F707W=Samsung Galaxy Z Flip 5G|" +
            "SM-F7110=Samsung Galaxy Z Flip3 5G|SM-F711B=Samsung Galaxy Z Flip3 5G|" +
            "SM-F711N=Samsung Galaxy Z Flip3 5G|SM-F711U=Samsung Galaxy Z Flip3 5G|" +
            "SM-F711U1=Samsung Galaxy Z Flip3 5G|SM-F711W=Samsung Galaxy Z Flip3 5G|" +
            "SM-F7210=Samsung Galaxy Z Flip4|SM-F721B=Samsung Galaxy Z Flip4|SM-F721C=Samsung Galaxy Z Flip4|" +
            "SM-F721N=Samsung Galaxy Z Flip4|SM-F721U=Samsung Galaxy Z Flip4|SM-F721U1=Samsung Galaxy Z Flip4|" +
            "SM-F721W=Samsung Galaxy Z Flip4|SM-F7310=Samsung Galaxy Z Flip5|SM-F731B=Samsung Galaxy Z Flip5|" +
            "SM-F731N=Samsung Galaxy Z Flip5|SM-F731Q=Samsung Galaxy Z Flip5|SM-F731U=Samsung Galaxy Z Flip5|" +
            "SM-F731U1=Samsung Galaxy Z Flip5|SM-F731W=Samsung Galaxy Z Flip5|SM-F7410=Samsung Galaxy Z Flip6|" +
            "SM-F741B=Samsung Galaxy Z Flip6|SM-F741N=Samsung Galaxy Z Flip6|SM-F741Q=Samsung Galaxy Z Flip6|" +
            "SM-F741U=Samsung Galaxy Z Flip6|SM-F741U1=Samsung Galaxy Z Flip6|SM-F741W=Samsung Galaxy Z Flip6|" +
            "SM-F7610=Samsung Galaxy Z Flip7 FE|SM-F761B=Samsung Galaxy Z Flip7 FE|" +
            "SM-F761N=Samsung Galaxy Z Flip7 FE|SM-F761U=Samsung Galaxy Z Flip7 FE|" +
            "SM-F761U1=Samsung Galaxy Z Flip7 FE|SM-F7660=Samsung Galaxy Z Flip7|" +
            "SM-F766B=Samsung Galaxy Z Flip7|SM-F766N=Samsung Galaxy Z Flip7|SM-F766Q=Samsung Galaxy Z Flip7|" +
            "SM-F766U=Samsung Galaxy Z Flip7|SM-F766U1=Samsung Galaxy Z Flip7|SM-F766W=Samsung Galaxy Z Flip7|" +
            "SM-F766Z=Samsung Galaxy Z Flip7|SM-F7760=Samsung Galaxy Z Flip8|SM-F776B=Samsung Galaxy Z Flip8|" +
            "SM-F776C=Samsung Galaxy Z Flip8|SM-F776N=Samsung Galaxy Z Flip8|SM-F776Q=Samsung Galaxy Z Flip8|" +
            "SM-F776U=Samsung Galaxy Z Flip8|SM-F776U1=Samsung Galaxy Z Flip8|SM-F776W=Samsung Galaxy Z Flip8|" +
            "SM-F776Z=Samsung Galaxy Z Flip8|SM-F9160=Samsung Galaxy Z Fold2 5G|" +
            "SM-F916B=Samsung Galaxy Z Fold2 5G|SM-F916N=Samsung Galaxy Z Fold2 5G|" +
            "SM-F916Q=Samsung Galaxy Z Fold2 5G|SM-F916U=Samsung Galaxy Z Fold2 5G|" +
            "SM-F916U1=Samsung Galaxy Z Fold2 5G|SM-F916W=Samsung Galaxy Z Fold2 5G|" +
            "SM-F9260=Samsung Galaxy Z Fold3 5G|SM-F926B=Samsung Galaxy Z Fold3 5G|" +
            "SM-F926N=Samsung Galaxy Z Fold3 5G|SM-F926U=Samsung Galaxy Z Fold3 5G|" +
            "SM-F926U1=Samsung Galaxy Z Fold3 5G|SM-F926W=Samsung Galaxy Z Fold3 5G|" +
            "SM-F9360=Samsung Galaxy Z Fold4|SM-F936B=Samsung Galaxy Z Fold4|SM-F936N=Samsung Galaxy Z Fold4|" +
            "SM-F936U=Samsung Galaxy Z Fold4|SM-F936U1=Samsung Galaxy Z Fold4|SM-F936W=Samsung Galaxy Z Fold4|" +
            "SM-F9460=Samsung Galaxy Z Fold5|SM-F946B=Samsung Galaxy Z Fold5|SM-F946N=Samsung Galaxy Z Fold5|" +
            "SM-F946Q=Samsung Galaxy Z Fold5|SM-F946U=Samsung Galaxy Z Fold5|SM-F946U1=Samsung Galaxy Z Fold5|" +
            "SM-F946W=Samsung Galaxy Z Fold5|SM-F9560=Samsung Galaxy Z Fold6|SM-F956B=Samsung Galaxy Z Fold6|" +
            "SM-F956N=Samsung Galaxy Z Fold6|SM-F956Q=Samsung Galaxy Z Fold6|SM-F956U=Samsung Galaxy Z Fold6|" +
            "SM-F956U1=Samsung Galaxy Z Fold6|SM-F956W=Samsung Galaxy Z Fold6|" +
            "SM-F958N=Samsung Galaxy Z Fold Special Edition|SM-F9660=Samsung Galaxy Z Fold7|" +
            "SM-F966B=Samsung Galaxy Z Fold7|SM-F966N=Samsung Galaxy Z Fold7|SM-F966Q=Samsung Galaxy Z Fold7|" +
            "SM-F966U=Samsung Galaxy Z Fold7|SM-F966U1=Samsung Galaxy Z Fold7|SM-F966W=Samsung Galaxy Z Fold7|" +
            "SM-F966Z=Samsung Galaxy Z Fold7|SM-F9710=Samsung Galaxy Z Fold8|SM-F971B=Samsung Galaxy Z Fold8|" +
            "SM-F971C=Samsung Galaxy Z Fold8|SM-F971N=Samsung Galaxy Z Fold8|SM-F971Q=Samsung Galaxy Z Fold8|" +
            "SM-F971U=Samsung Galaxy Z Fold8|SM-F971U1=Samsung Galaxy Z Fold8|SM-F971W=Samsung Galaxy Z Fold8|" +
            "SM-F971Z=Samsung Galaxy Z Fold8|SM-F9760=Samsung Galaxy Z Fold8 Ultra|" +
            "SM-F976B=Samsung Galaxy Z Fold8 Ultra|SM-F976C=Samsung Galaxy Z Fold8 Ultra|" +
            "SM-F976N=Samsung Galaxy Z Fold8 Ultra|SM-F976Q=Samsung Galaxy Z Fold8 Ultra|" +
            "SM-F976U=Samsung Galaxy Z Fold8 Ultra|SM-F976U1=Samsung Galaxy Z Fold8 Ultra|" +
            "SM-F976W=Samsung Galaxy Z Fold8 Ultra|SM-F976Z=Samsung Galaxy Z Fold8 Ultra|" +
            "SM-G770F=Samsung Galaxy S10 Lite|SM-G770U1=Samsung Galaxy S10 Lite|" +
            "SM-G780F=Samsung Galaxy S20 FE|SM-G780G=Samsung Galaxy S20 FE|SM-G7810=Samsung Galaxy S20 FE 5G|" +
            "SM-G781B=Samsung Galaxy S20 FE 5G|SM-G781N=Samsung Galaxy S20 FE 5G|" +
            "SM-G781U=Samsung Galaxy S20 FE 5G|SM-G781U1=Samsung Galaxy S20 FE 5G|" +
            "SM-G781V=Samsung Galaxy S20 FE 5G|SM-G781W=Samsung Galaxy S20 FE 5G|SM-G9700=Samsung Galaxy S10e|" +
            "SM-G9708=Samsung Galaxy S10e|SM-G970F=Samsung Galaxy S10e|SM-G970N=Samsung Galaxy S10e|" +
            "SM-G970U=Samsung Galaxy S10e|SM-G970U1=Samsung Galaxy S10e|SM-G970W=Samsung Galaxy S10e|" +
            "SM-G9730=Samsung Galaxy S10|SM-G9738=Samsung Galaxy S10|SM-G973C=Samsung Galaxy S10|" +
            "SM-G973F=Samsung Galaxy S10|SM-G973N=Samsung Galaxy S10|SM-G973U=Samsung Galaxy S10|" +
            "SM-G973U1=Samsung Galaxy S10|SM-G973W=Samsung Galaxy S10|SM-G9750=Samsung Galaxy S10+|" +
            "SM-G9758=Samsung Galaxy S10+|SM-G975F=Samsung Galaxy S10+|SM-G975N=Samsung Galaxy S10+|" +
            "SM-G975U=Samsung Galaxy S10+|SM-G975U1=Samsung Galaxy S10+|SM-G975W=Samsung Galaxy S10+|" +
            "SM-G977B=Samsung Galaxy S10 5G|SM-G977N=Samsung Galaxy S10 5G|SM-G977P=Samsung Galaxy S10 5G|" +
            "SM-G977T=Samsung Galaxy S10 5G|SM-G977U=Samsung Galaxy S10 5G|SM-G980F=Samsung Galaxy S20|" +
            "SM-G9810=Samsung Galaxy S20 5G|SM-G981B=Samsung Galaxy S20 5G|SM-G981N=Samsung Galaxy S20 5G|" +
            "SM-G981U=Samsung Galaxy S20 5G|SM-G981U1=Samsung Galaxy S20 5G|SM-G981V=Samsung Galaxy S20 5G|" +
            "SM-G981W=Samsung Galaxy S20 5G|SM-G985F=Samsung Galaxy S20+|SM-G9860=Samsung Galaxy S20+ 5G|" +
            "SM-G986B=Samsung Galaxy S20+ 5G|SM-G986N=Samsung Galaxy S20+ 5G|SM-G986U=Samsung Galaxy S20+ 5G|" +
            "SM-G986U1=Samsung Galaxy S20+ 5G|SM-G986W=Samsung Galaxy S20+ 5G|" +
            "SM-G9880=Samsung Galaxy S20 Ultra 5G|SM-G988B=Samsung Galaxy S20 Ultra 5G|" +
            "SM-G988N=Samsung Galaxy S20 Ultra 5G|SM-G988Q=Samsung Galaxy S20 Ultra 5G|" +
            "SM-G988U=Samsung Galaxy S20 Ultra 5G|SM-G988U1=Samsung Galaxy S20 Ultra 5G|" +
            "SM-G988W=Samsung Galaxy S20 Ultra 5G|SM-G9900=Samsung Galaxy S21 FE 5G|" +
            "SM-G990B=Samsung Galaxy S21 FE 5G|SM-G990B2=Samsung Galaxy S21 FE 5G|" +
            "SM-G990E=Samsung Galaxy S21 FE 5G|SM-G990U=Samsung Galaxy S21 FE 5G|" +
            "SM-G990U1=Samsung Galaxy S21 FE 5G|SM-G990U2=Samsung Galaxy S21 FE 5G|" +
            "SM-G990U3=Samsung Galaxy S21 FE 5G|SM-G990W=Samsung Galaxy S21 FE 5G|" +
            "SM-G990W2=Samsung Galaxy S21 FE 5G|SM-G9910=Samsung Galaxy S21 5G|SM-G991B=Samsung Galaxy S21 5G|" +
            "SM-G991N=Samsung Galaxy S21 5G|SM-G991Q=Samsung Galaxy S21 5G|SM-G991U1=Samsung Galaxy S21 5G|" +
            "SM-G991W=Samsung Galaxy S21 5G|SM-G9960=Samsung Galaxy S21+ 5G|SM-G996B=Samsung Galaxy S21+ 5G|" +
            "SM-G996N=Samsung Galaxy S21+ 5G|SM-G996U=Samsung Galaxy S21+ 5G|SM-G996U1=Samsung Galaxy S21+ 5G|" +
            "SM-G996W=Samsung Galaxy S21+ 5G|SM-G9980=Samsung Galaxy S21 Ultra 5G|" +
            "SM-G998B=Samsung Galaxy S21 Ultra 5G|SM-G998N=Samsung Galaxy S21 Ultra 5G|" +
            "SM-G998U=Samsung Galaxy S21 Ultra 5G|SM-G998U1=Samsung Galaxy S21 Ultra 5G|" +
            "SM-G998W=Samsung Galaxy S21 Ultra 5G|SM-S7110=Samsung Galaxy S23 FE|" +
            "SM-S711B=Samsung Galaxy S23 FE|SM-S711N=Samsung Galaxy S23 FE|SM-S711U=Samsung Galaxy S23 FE|" +
            "SM-S711U1=Samsung Galaxy S23 FE|SM-S711W=Samsung Galaxy S23 FE|SM-S7210=Samsung Galaxy S24 FE|" +
            "SM-S721B=Samsung Galaxy S24 FE|SM-S721N=Samsung Galaxy S24 FE|SM-S721Q=Samsung Galaxy S24 FE|" +
            "SM-S721U=Samsung Galaxy S24 FE|SM-S721U1=Samsung Galaxy S24 FE|SM-S721W=Samsung Galaxy S24 FE|" +
            "SM-S731B=Samsung Galaxy S25 FE|SM-S731N=Samsung Galaxy S25 FE|SM-S731U=Samsung Galaxy S25 FE|" +
            "SM-S731U1=Samsung Galaxy S25 FE|SM-S731W=Samsung Galaxy S25 FE|SM-S741B=Samsung Galaxy S26 FE|" +
            "SM-S741N=Samsung Galaxy S26 FE|SM-S741U=Samsung Galaxy S26 FE|SM-S741U1=Samsung Galaxy S26 FE|" +
            "SM-S741W=Samsung Galaxy S26 FE|SM-S9010=Samsung Galaxy S22|SM-S901B=Samsung Galaxy S22|" +
            "SM-S901E=Samsung Galaxy S22|SM-S901N=Samsung Galaxy S22|SM-S901U=Samsung Galaxy S22|" +
            "SM-S901U1=Samsung Galaxy S22|SM-S901W=Samsung Galaxy S22|SM-S9060=Samsung Galaxy S22+|" +
            "SM-S906B=Samsung Galaxy S22+|SM-S906E=Samsung Galaxy S22+|SM-S906N=Samsung Galaxy S22+|" +
            "SM-S906U=Samsung Galaxy S22+|SM-S906U1=Samsung Galaxy S22+|SM-S906W=Samsung Galaxy S22+|" +
            "SM-S9080=Samsung Galaxy S22 Ultra|SM-S908B=Samsung Galaxy S22 Ultra|" +
            "SM-S908E=Samsung Galaxy S22 Ultra|SM-S908N=Samsung Galaxy S22 Ultra|" +
            "SM-S908U=Samsung Galaxy S22 Ultra|SM-S908U1=Samsung Galaxy S22 Ultra|" +
            "SM-S908W=Samsung Galaxy S22 Ultra|SM-S9110=Samsung Galaxy S23|SM-S911B=Samsung Galaxy S23|" +
            "SM-S911C=Samsung Galaxy S23|SM-S911N=Samsung Galaxy S23|SM-S911U=Samsung Galaxy S23|" +
            "SM-S911U1=Samsung Galaxy S23|SM-S911W=Samsung Galaxy S23|SM-S9160=Samsung Galaxy S23+|" +
            "SM-S916B=Samsung Galaxy S23+|SM-S916N=Samsung Galaxy S23+|SM-S916U=Samsung Galaxy S23+|" +
            "SM-S916U1=Samsung Galaxy S23+|SM-S916W=Samsung Galaxy S23+|SM-S9180=Samsung Galaxy S23 Ultra|" +
            "SM-S918B=Samsung Galaxy S23 Ultra|SM-S918N=Samsung Galaxy S23 Ultra|" +
            "SM-S918Q=Samsung Galaxy S23 Ultra|SM-S918U=Samsung Galaxy S23 Ultra|" +
            "SM-S918U1=Samsung Galaxy S23 Ultra|SM-S918W=Samsung Galaxy S23 Ultra|SM-S9210=Samsung Galaxy S24|" +
            "SM-S921B=Samsung Galaxy S24|SM-S921E=Samsung Galaxy S24|SM-S921N=Samsung Galaxy S24|" +
            "SM-S921Q=Samsung Galaxy S24|SM-S921U=Samsung Galaxy S24|SM-S921U1=Samsung Galaxy S24|" +
            "SM-S921W=Samsung Galaxy S24|SM-S9260=Samsung Galaxy S24+|SM-S926B=Samsung Galaxy S24+|" +
            "SM-S926N=Samsung Galaxy S24+|SM-S926U=Samsung Galaxy S24+|SM-S926U1=Samsung Galaxy S24+|" +
            "SM-S926W=Samsung Galaxy S24+|SM-S9280=Samsung Galaxy S24 Ultra|SM-S928B=Samsung Galaxy S24 Ultra|" +
            "SM-S928N=Samsung Galaxy S24 Ultra|SM-S928Q=Samsung Galaxy S24 Ultra|" +
            "SM-S928U=Samsung Galaxy S24 Ultra|SM-S928U1=Samsung Galaxy S24 Ultra|" +
            "SM-S928W=Samsung Galaxy S24 Ultra|SM-S9310=Samsung Galaxy S25|SM-S931B=Samsung Galaxy S25|" +
            "SM-S931N=Samsung Galaxy S25|SM-S931Q=Samsung Galaxy S25|SM-S931U=Samsung Galaxy S25|" +
            "SM-S931U1=Samsung Galaxy S25|SM-S931W=Samsung Galaxy S25|SM-S931Z=Samsung Galaxy S25|" +
            "SM-S9360=Samsung Galaxy S25+|SM-S936B=Samsung Galaxy S25+|SM-S936N=Samsung Galaxy S25+|" +
            "SM-S936U=Samsung Galaxy S25+|SM-S936U1=Samsung Galaxy S25+|SM-S936W=Samsung Galaxy S25+|" +
            "SM-S9370=Samsung Galaxy S25 Edge|SM-S937B=Samsung Galaxy S25 Edge|" +
            "SM-S937N=Samsung Galaxy S25 Edge|SM-S937U=Samsung Galaxy S25 Edge|" +
            "SM-S937U1=Samsung Galaxy S25 Edge|SM-S937W=Samsung Galaxy S25 Edge|" +
            "SM-S9380=Samsung Galaxy S25 Ultra|SM-S938B=Samsung Galaxy S25 Ultra|" +
            "SM-S938N=Samsung Galaxy S25 Ultra|SM-S938Q=Samsung Galaxy S25 Ultra|" +
            "SM-S938U=Samsung Galaxy S25 Ultra|SM-S938U1=Samsung Galaxy S25 Ultra|" +
            "SM-S938W=Samsung Galaxy S25 Ultra|SM-S938Z=Samsung Galaxy S25 Ultra|SM-S9420=Samsung Galaxy S26|" +
            "SM-S942B=Samsung Galaxy S26|SM-S942C=Samsung Galaxy S26|SM-S942N=Samsung Galaxy S26|" +
            "SM-S942Q=Samsung Galaxy S26|SM-S942U=Samsung Galaxy S26|SM-S942U1=Samsung Galaxy S26|" +
            "SM-S942W=Samsung Galaxy S26|SM-S942Z=Samsung Galaxy S26|SM-S9470=Samsung Galaxy S26+|" +
            "SM-S947B=Samsung Galaxy S26+|SM-S947C=Samsung Galaxy S26+|SM-S947N=Samsung Galaxy S26+|" +
            "SM-S947Q=Samsung Galaxy S26+|SM-S947U=Samsung Galaxy S26+|SM-S947U1=Samsung Galaxy S26+|" +
            "SM-S947W=Samsung Galaxy S26+|SM-S947Z=Samsung Galaxy S26+|SM-S9480=Samsung Galaxy S26 Ultra|" +
            "SM-S948B=Samsung Galaxy S26 Ultra|SM-S948C=Samsung Galaxy S26 Ultra|" +
            "SM-S948N=Samsung Galaxy S26 Ultra|SM-S948Q=Samsung Galaxy S26 Ultra|" +
            "SM-S948U=Samsung Galaxy S26 Ultra|SM-S948U1=Samsung Galaxy S26 Ultra|" +
            "SM-S948W=Samsung Galaxy S26 Ultra|SM-S948Z=Samsung Galaxy S26 Ultra"),
    )
}

/** One logo file: its viewport and one filled path per colour. */
class LogoArt(val width: Float, val height: Float, val layers: List<Pair<Int, Path>>) {
    val aspect get() = width / height

    /** Draws at [height] px tall with its top-left at ([x], [top]); [ink] replaces every colour. */
    fun draw(canvas: Canvas, x: Float, top: Float, height: Float, ink: Int?, paint: Paint) {
        val k = height / this.height
        canvas.save()
        canvas.translate(x, top)
        canvas.scale(k, k)
        for ((c, p) in layers) { paint.color = ink ?: c; canvas.drawPath(p, paint) }
        canvas.restore()
    }
}

/**
 * The logos in assets/logos, parsed once into [Path]s. They are drawn as
 * paths at the size of the canvas they land on, so a 50 MP export is as sharp
 * as the preview (a VectorDrawable would go through a cached bitmap).
 *
 * The files are VectorDrawable XML written by the project's converter: only
 * absolute M L C Q Z, one fill colour per path. A missing or unreadable file
 * gives null and the caller writes the brand's name instead.
 */
object Logos {
    private const val NS = "http://schemas.android.com/apk/res/android"
    private val TOKEN = Regex("[MLCQZ]|-?(?:\\d+\\.?\\d*|\\.\\d+)")

    @Volatile private var assets: android.content.res.AssetManager? = null
    private val cache = HashMap<String, LogoArt?>()

    /** Called from the editor; an export resumed with no editor yet reads through the class loader. */
    fun attach(context: Context) {
        if (assets == null) assets = context.applicationContext.assets
    }

    fun get(art: String, colour: Boolean): LogoArt? {
        val name = "logo_${art}_${if (colour) "color" else "mono"}"
        synchronized(cache) {
            if (cache.containsKey(name)) return cache[name]
            val parsed = runCatching { open("logos/$name.xml")?.use(::parse) }.getOrNull()
            // A miss before attach() may only be the class-loader route failing; try again later.
            if (parsed != null || assets != null) cache[name] = parsed
            return parsed
        }
    }

    private fun open(path: String): java.io.InputStream? =
        assets?.let { a -> runCatching { a.open(path) }.getOrNull() }
            ?: Logos::class.java.classLoader?.getResourceAsStream("assets/$path")

    private fun parse(input: java.io.InputStream): LogoArt {
        val p = android.util.Xml.newPullParser()
        p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)
        var w = 0f
        var h = 0f
        val layers = ArrayList<Pair<Int, Path>>()
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            when (p.name) {
                "vector" -> {
                    w = p.getAttributeValue(NS, "viewportWidth").toFloat()
                    h = p.getAttributeValue(NS, "viewportHeight").toFloat()
                }
                "path" -> {
                    val path = toPath(p.getAttributeValue(NS, "pathData"))
                    path.fillType = if (p.getAttributeValue(NS, "fillType") == "evenOdd") Path.FillType.EVEN_ODD
                        else Path.FillType.WINDING
                    layers += android.graphics.Color.parseColor(p.getAttributeValue(NS, "fillColor")) to path
                }
            }
        }
        require(w > 0f && h > 0f && layers.isNotEmpty())
        return LogoArt(w, h, layers)
    }

    private fun toPath(d: String): Path {
        val path = Path()
        val v = FloatArray(6)
        var cmd = 'M'
        var n = 0
        for (m in TOKEN.findAll(d)) {
            val t = m.value
            if (t[0].isLetter()) {
                cmd = t[0]; n = 0
                if (cmd == 'Z') path.close()
                continue
            }
            v[n++] = t.toFloat()
            val need = when (cmd) { 'M', 'L' -> 2; 'Q' -> 4; 'C' -> 6; else -> 0 }
            if (need == 0 || n < need) continue
            when (cmd) {
                'M' -> { path.moveTo(v[0], v[1]); cmd = 'L' }
                'L' -> path.lineTo(v[0], v[1])
                'Q' -> path.quadTo(v[0], v[1], v[2], v[3])
                'C' -> path.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5])
            }
            n = 0
        }
        return path
    }
}

/**
 * Marks laid out on one row: logos, a hairline between co-branded halves
 * and between marks, and plain text where a logo is missing. [base] is the cap height of the
 * text beside it; a square logo stands taller than a long wordmark so the two
 * read as the same weight.
 */
class MarkRow(marks: List<Mark>, private val base: Float, private val colour: Boolean, typeface: Typeface) {
    private sealed interface Piece
    private class Art(val art: LogoArt, val h: Float) : Piece { val w = art.aspect * h }
    private class Word(val text: String) : Piece
    private object Bar : Piece

    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = base / 0.72f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gap = base * 0.5f
    private val hairline = max(1f, base * 0.06f)
    private val pieces: List<Piece> = marks.distinct().mapIndexed { i, m -> if (i == 0) piecesOf(m) else listOf(Bar) + piecesOf(m) }.flatten()

    private fun piecesOf(mark: Mark): List<Piece> = when (mark) {
        is Mark.Word -> listOf(Word(mark.text))
        is Mark.Logo -> mark.brand.art.flatMapIndexed { i, id ->
            val art = Logos.get(id, colour)
            val p = if (art != null) {
                // Height falls with the square root of the aspect, and no logo runs
                // wider than 10 cap heights (Hasselblad's wordmark is 14:1).
                val k = minOf((1.9f / sqrt(art.aspect)).coerceIn(0.85f, 1.8f), 10f / art.aspect)
                Art(art, base * k)
            } else Word(Brands.byId(id)?.name ?: mark.brand.name)
            if (i == 0) listOf(p) else listOf(Bar, p)
        }
    }

    val width: Float = pieces.sumOf { p ->
        when (p) { is Art -> p.w; is Word -> word.measureText(p.text); Bar -> hairline }.toDouble()
    }.toFloat() + gap * (pieces.size - 1)

    val height: Float = pieces.maxOf { p -> when (p) { is Art -> p.h; is Word -> base; Bar -> base * 1.4f } }

    /** Draws from [x], centred on [mid]; [ink] for mono logos, text and the hairline, null keeps logo colours. */
    fun draw(canvas: Canvas, x: Float, mid: Float, ink: Int, logoInk: Int?) {
        var at = x
        for (p in pieces) {
            when (p) {
                is Art -> { p.art.draw(canvas, at, mid - p.h / 2, p.h, logoInk, fill); at += p.w }
                is Word -> { word.color = ink; canvas.drawText(p.text, at, mid + base / 2, word); at += word.measureText(p.text) }
                Bar -> {
                    fill.color = ink
                    canvas.drawRect(at, mid - base * 0.7f, at + hairline, mid + base * 0.7f, fill)
                    at += hairline
                }
            }
            at += gap
        }
    }
}

/**
 * The data bar's brand controls: which mark (auto from EXIF, a brand picked
 * by hand, the user's own text, none), the layout, the film maker's logo and
 * colour or mono. Shown under 参数文字 while the frame has a caption.
 */
@Composable
fun BrandPanel(ui: UiState, vm: EditorViewModel, enabled: Boolean) {
    Logos.attach(LocalContext.current)
    val e = ui.edit
    val f = e.frame
    if (!f.caption) return
    val caption = remember(ui.exif, e.film, ui.catalog) { Caption.of(ui.exif, ui.catalog.film(e.film)?.label ?: "") }
    val detected = remember(caption) { Brands.detect(caption.make, caption.exifModel) }
    fun set(next: FrameSpec) = vm.update(e.copy(frame = next))
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("品牌标识", style = MaterialTheme.typography.labelLarge)
        val mode = when (f.brand) { "", "none" -> "none"; "auto", "custom" -> f.brand; else -> "pick" }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((v, label) in listOf("auto" to "自动", "pick" to "选择品牌", "custom" to "自定义文字", "none" to "无")) {
                SelectPill(
                    selected = mode == v, enabled = enabled, label = { Text(label) },
                    onClick = {
                        if (v != mode) set(f.copy(brand = if (v == "pick") (detected?.id ?: Brands.ALL.first().id) else v))
                    },
                )
            }
        }
        when (mode) {
            "auto" -> Text(
                when {
                    caption.make.isBlank() && caption.exifModel.isBlank() -> "照片没有相机信息，不显示品牌"
                    detected != null -> "按照片 EXIF 识别为 ${detected.name}（${caption.camera}）"
                    else -> "没有 ${caption.make} 的 logo，写出厂商名"
                },
                style = small, color = dim,
            )
            "pick" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in Brands.ALL) if (b.kind != Brand.Kind.FILM) {
                    SelectPill(
                        selected = f.brand == b.id, enabled = enabled, onClick = { set(f.copy(brand = b.id)) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LogoGlyph(b)
                                Text(b.name)
                            }
                        },
                    )
                }
            }
            "custom" -> {
                var text by remember(f.brandText) { mutableStateOf(f.brandText) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.take(24) }, singleLine = true, enabled = enabled,
                        label = { Text("文字") }, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { set(f.copy(brandText = text.trim())) }, enabled = enabled && text.trim() != f.brandText) {
                        Text("应用")
                    }
                }
            }
        }
        Text("版式", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((v, label) in FrameSpec.CAPTION_LAYOUTS) {
                SelectPill(selected = f.captionLayout == v, enabled = enabled, onClick = { set(f.copy(captionLayout = v)) },
                    label = { Text(label) })
            }
        }
        val filmBrand = Brands.filmBrand(caption.film)
        ToggleRow(if (filmBrand != null) "胶片厂牌 logo（${filmBrand.name}）" else "胶片厂牌 logo", f.filmLogo, enabled && filmBrand != null) {
            set(f.copy(filmLogo = it))
        }
        ToggleRow("彩色 logo", f.logoColor, enabled && f.lightPaper) { set(f.copy(logoColor = it)) }
        Text(
            if (f.lightPaper) "彩色只用于浅色纸；深色纸自动换成单色。" else "深色纸上 logo 一律用单色。",
            style = small, color = dim,
        )
        Text("各品牌 logo 为其权利人的商标，仅用于标识拍摄器材，与本应用无隶属或背书关系。", style = small, color = dim)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A brand's mono logo, 16 dp tall, in the chip's text colour. */
@Composable
private fun LogoGlyph(b: Brand) {
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val arts = remember(b) { b.art.mapNotNull { Logos.get(it, colour = false) } }
    if (arts.isEmpty()) return
    val h = 16.dp
    val w = h * minOf(arts.sumOf { it.aspect.toDouble() }.toFloat() + 0.4f * (arts.size - 1), 6f)
    androidx.compose.foundation.Canvas(Modifier.size(w, h)) {
        drawIntoCanvas { c ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val total = arts.sumOf { it.aspect.toDouble() }.toFloat() + 0.4f * (arts.size - 1)
            val k = minOf(size.height, size.width / total)
            var x = 0f
            for (a in arts) {
                a.draw(c.nativeCanvas, x, (size.height - k) / 2, k, ink, paint)
                x += a.aspect * k + 0.4f * k
            }
        }
    }
}
