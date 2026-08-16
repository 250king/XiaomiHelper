package dev.lackluster.mihelper.utils

import android.graphics.Picture
import android.graphics.PointF
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.caverock.androidsvg.SvgAnchorExtractor
import kotlin.math.ceil

object StackedMobileIconUtils {
    fun parseWifiSignalSegmentCount(svg: String): Int? {
        val ids = Regex("""id\s*=\s*['"](wifi_\d+)['"]""")
            .findAll(svg)
            .map { it.groupValues[1] }
            .toList()
        if (ids.size != ids.distinct().size) return null

        val segmentIds = ids.mapNotNull { id ->
            id.removePrefix("wifi_").toIntOrNull()?.let { number -> id to number }
        }
        if (segmentIds.isEmpty() || segmentIds.any { it.second !in 1..4 }) return null

        val count = segmentIds.maxOf { it.second }
        return count.takeIf { expected ->
            segmentIds.map { it.second }.toSet() == (1..expected).toSet()
        }
    }

    fun remapWifiSignalLevel(rawLevel: Int, segmentCount: Int): Int {
        if (segmentCount !in 1..4) return 0
        return ceil(rawLevel.coerceIn(0, 4) * segmentCount / 4.0).toInt()
    }

    fun generateWifiSignalPictures(
        wifiSvg: String,
        pictureCache: MutableMap<String, Picture>,
        alphaFilled: Float,
        alphaBackground: Float,
    ): Int? {
        val segmentCount = parseWifiSignalSegmentCount(wifiSvg) ?: return null
        return try {
            val baseSvg = SVG.getFromString(wifiSvg)
            for (filledLevel in 0..segmentCount) {
                val cssBuilder = StringBuilder()
                for (index in 1..segmentCount) {
                    val alpha = if (index <= filledLevel) alphaFilled else alphaBackground
                    val id = "wifi_$index"
                    cssBuilder.append("#$id { fill: #FFFFFF !important; fill-opacity: $alpha !important; } ")
                    cssBuilder.append("#$id * { fill: #FFFFFF !important; fill-opacity: $alpha !important; } ")
                }
                pictureCache[filledLevel.toString()] = baseSvg.renderToPicture(
                    RenderOptions().css(cssBuilder.toString())
                )
            }
            segmentCount
        } catch (e: SVGParseException) {
            MLog.e(e)
            null
        }
    }

    fun generateStackedSignalPictures(
        stackedMobileSVGString: String,
        pictureCache: MutableMap<String, Picture>,
        alphaFilled: Float,
        alphaBackground: Float,
        alphaError: Float
    ): Boolean {
        listOf(
            "signal_1_1", "signal_1_2", "signal_1_3", "signal_1_4",
            "signal_2_1", "signal_2_2", "signal_2_3", "signal_2_4",
        ).forEach { id ->
            val regex = Regex("""id\s*=\s*['"]$id['"]""")
            if (!regex.containsMatchIn(stackedMobileSVGString)) {
                MLog.e { "SVG Validation failed: Missing required ID -> $id" }
                return false
            }
        }

        try {
            val baseSvg = SVG.getFromString(stackedMobileSVGString)
            val signalRange = -1..4
            for (sim1Level in signalRange) {
                for (sim2Level in signalRange) {
                    val cssBuilder = StringBuilder()
                    cssBuilder.append("#type_container { display: none !important; } ")
                    appendCssForSignal(cssBuilder, "signal_1", sim1Level, alphaFilled, alphaBackground, alphaError)
                    appendCssForSignal(cssBuilder, "signal_2", sim2Level, alphaFilled, alphaBackground, alphaError)
                    val renderOptions = RenderOptions().css(cssBuilder.toString())
                    val picture = baseSvg.renderToPicture(renderOptions)
                    val cacheKey = "${sim1Level}_${sim2Level}"
                    pictureCache[cacheKey] = picture
                }
            }
            return true
        } catch (e: SVGParseException) {
            MLog.e(e)
            return false
        }
    }

    fun generateSingleSignalPictures(
        singleMobileSVGString: String,
        pictureCache: MutableMap<String, Picture>,
        alphaFilled: Float,
        alphaBackground: Float,
        alphaError: Float
    ): Boolean {
        listOf(
            "signal_1", "signal_2", "signal_3", "signal_4",
        ).forEach{ id ->
            val regex = Regex("""id\s*=\s*['"]$id['"]""")
            if (!regex.containsMatchIn(singleMobileSVGString)) {
                MLog.e { "Single SVG Validation failed: Missing required ID -> $id" }
                return false
            }
        }
        try {
            val baseSvg = SVG.getFromString(singleMobileSVGString)
            val signalRange = -1..4
            for (level in signalRange) {
                val cssBuilder = StringBuilder()
                cssBuilder.append("#type_container { display: none !important; } ")
                appendCssForSignal(cssBuilder, "signal", level, alphaFilled, alphaBackground, alphaError)
                val renderOptions = RenderOptions().css(cssBuilder.toString())
                val picture = baseSvg.renderToPicture(renderOptions)
                val cacheKey = "$level"
                pictureCache[cacheKey] = picture
            }
            return true
        } catch (e: SVGParseException) {
            MLog.e(e)
            return false
        }
    }

    fun extractTypeContainerBounds(svgString: String): PointF? {
        if (!svgString.contains("type_container")) return null

        try {
            // 交给 AndroidSVG 去构建对象树
            val svg = SVG.getFromString(svgString)
            // 调用我们的伪装类，直接拿百分比！
            return SvgAnchorExtractor.extractCenterPercent(svg)
        } catch (e: Exception) {
            MLog.e(e)
        }
        return null
    }

    fun appendCssForSignal(
        cssBuilder: StringBuilder,
        idPrefix: String,
        level: Int,
        alphaFilled: Float,
        alphaBackground: Float,
        alphaError: Float
    ) {
        for (i in 1..4) {
            val alpha = when {
                level == -1 -> alphaError          // -1: 无服务或异常
                i <= level -> alphaFilled          // 实底高亮
                else -> alphaBackground            // 暗色打底
            }
            // 动态拼接出目标 ID，例如 "signal_1" 或 "signal_1_1"
            val elementId = "${idPrefix}_${i}"
            // 1. 针对元素本身的样式覆盖
            cssBuilder.append("#$elementId { fill: #FFFFFF !important; fill-opacity: $alpha !important; } ")
            // 2. 强行穿透 Group，覆盖所有子节点
            cssBuilder.append("#$elementId * { fill: #FFFFFF !important; fill-opacity: $alpha !important; } ")
        }
    }
}
