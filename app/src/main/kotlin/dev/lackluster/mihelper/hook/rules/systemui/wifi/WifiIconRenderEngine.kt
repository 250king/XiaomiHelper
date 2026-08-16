package dev.lackluster.mihelper.hook.rules.systemui.wifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.drawable.Icon
import android.util.LruCache
import android.view.View
import androidx.core.graphics.createBitmap
import dev.lackluster.mihelper.BuildConfig
import dev.lackluster.mihelper.data.Constants
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import dev.lackluster.mihelper.utils.MLog
import dev.lackluster.mihelper.utils.StackedMobileIconUtils
import kotlin.math.max
import kotlin.math.roundToInt

object WifiIconRenderEngine {
    private data class CacheKey(val filledSegments: Int, val isRtl: Boolean)

    private const val TAG = "WifiIconRenderEngine"

    private val pictures = HashMap<String, Picture>()
    private val finalIcons = LruCache<CacheKey, Icon>(10)

    @Volatile
    var isPreloaded = false
        private set
    private var segmentCount = 0
    private var currentDpi = -1
    private var currentDensity = 1f
    private var iconHeightResId = 0
    private var iconHeightPx = 0
    private var paddingStartDp = 0f
    private var paddingEndDp = 0f
    private var signalScale = 1f

    fun preload(context: Context, iconHeightResId: Int, customSvg: String?): Boolean {
        synchronized(this) {
            pictures.clear()
            finalIcons.evictAll()
            isPreloaded = false

            segmentCount = 0
            WifiIconRenderEngine.iconHeightResId = iconHeightResId

            paddingStartDp = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_START.get().coerceAtLeast(0f)
            paddingEndDp = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_END.get().coerceAtLeast(0f)
            signalScale = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SCALE.get().coerceIn(0.5f, 1.5f)
            ensureEnvironment(context, true)

            val moduleContext = try {
                context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            } catch (e: Exception) {
                MLog.e(TAG, e) { "Error occurred while retrieving built-in svg resources" }
                null
            }
            fun getAssetSvg(assetPath: String): String {
                if (moduleContext == null) return ""
                return runCatching {
                    moduleContext.assets.open(assetPath).bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }
            val wifiSVGString = when (Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_STYLE.get()) {
                0 -> getAssetSvg(Constants.ASSETS_SVG_WIFI_HYPER_OS_3)
                1 -> getAssetSvg(Constants.ASSETS_SVG_WIFI_IOS_26)
                3 -> getAssetSvg(Constants.ASSETS_SVG_WIFI_IOS_27)
                4 -> getAssetSvg(Constants.ASSETS_SVG_WIFI_HYPER_OS_2)
                else -> customSvg?.takeIf { it.isNotBlank() } ?: getAssetSvg(Constants.ASSETS_SVG_WIFI_HYPER_OS_3)
            }

            val alphaFilled = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_FG.get().coerceIn(0f, 1f)
            val alphaBackground = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_BG.get().coerceIn(0f, 1f)

            segmentCount = StackedMobileIconUtils.generateWifiSignalPictures(wifiSVGString, pictures, alphaFilled, alphaBackground) ?: return false
            isPreloaded = true
            return true
        }
    }

    fun getIcon(context: Context, rawLevel: Int): Icon? {
        if (!isPreloaded) return null
        ensureEnvironment(context)
        val filled = StackedMobileIconUtils.remapWifiSignalLevel(rawLevel, segmentCount)
        val isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val key = CacheKey(filled, isRtl)
        finalIcons.get(key)?.let { return it }

        val picture = pictures[filled.toString()] ?: return null
        if (iconHeightPx <= 0 || picture.height <= 0 || picture.width <= 0) return null

        val contentHeight = max(1, (iconHeightPx * signalScale).roundToInt())
        val contentWidth = max(1, (contentHeight * picture.width.toFloat() / picture.height).roundToInt())

        val paddingStart = (paddingStartDp * currentDensity).roundToInt()
        val paddingEnd = (paddingEndDp * currentDensity).roundToInt()
        val paddingLeft = if (isRtl) paddingEnd else paddingStart
        val paddingRight = if (isRtl) paddingStart else paddingEnd

        val bitmap = createBitmap(contentWidth + paddingLeft + paddingRight, iconHeightPx, Bitmap.Config.ALPHA_8)
        Canvas(bitmap).apply {
            translate(paddingLeft.toFloat(), (iconHeightPx - contentHeight) / 2f)
            scale(contentWidth.toFloat() / picture.width, contentHeight.toFloat() / picture.height)
            drawPicture(picture)
        }
        return Icon.createWithBitmap(bitmap).also { finalIcons.put(key, it) }
    }

    private fun ensureEnvironment(context: Context, force: Boolean = false) {
        val newDpi = context.resources.displayMetrics.densityDpi
        if (force || newDpi != currentDpi) {
            // DPI 变了（比如用户在系统设置里改了显示大小），立刻清空光栅缓存！
            finalIcons.evictAll() // L3 清理

            currentDpi = newDpi
            currentDensity = context.resources.displayMetrics.density
            if (iconHeightResId != 0) {
                // 只有在这里才去查 Resource 表！性能大幅提升！
                iconHeightPx = context.resources.getDimensionPixelSize(iconHeightResId)
            }
        }
    }
}
