package dev.lackluster.mihelper.app.repository

import android.content.Context
import android.graphics.Picture
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import dev.lackluster.hyperx.ui.preference.core.PreferenceKey
import dev.lackluster.mihelper.app.utils.RemoteFileStore
import dev.lackluster.mihelper.data.Constants
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.utils.StackedMobileIconUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CustomWifiSignalState(
    val enabled: Boolean = false,
    val svgStyle: Int = 0,
    val customSvg: String = "",
    val customSvgName: String = "",
    val alphaFg: Float = 1f,
    val alphaBg: Float = 0.4f,
    val scale: Float = 1f,
    val paddingStart: Float = 0f,
    val paddingEnd: Float = 0f,
)

private data class WifiSvgRenderKey(val hash: Int, val alphaFg: Float, val alphaBg: Float)

private val customWifiKeys: Set<PreferenceKey<*>> = setOf(
    Preferences.SystemUI.StatusBar.CustomWifi.ENABLED,
    Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_STYLE,
    Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_NAME,
    Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_FG,
    Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_BG,
    Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SCALE,
    Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_START,
    Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_END,
)

class WifiSignalRepository(
    private val context: Context,
    private val prefRepo: GlobalPreferencesRepository,
    private val fileStore: RemoteFileStore,
) {
    private val repositoryScope = CoroutineScope(Dispatchers.IO)
    private val pictureCache = LruCache<WifiSvgRenderKey, Map<String, Picture>>(12)

    private val _configState = MutableStateFlow(loadConfig())
    val configState = _configState.asStateFlow()

    private val _pictures = MutableStateFlow<Map<String, Picture>>(emptyMap())
    val pictures = _pictures.asStateFlow()

    private val _segmentCount = MutableStateFlow(0)
    val segmentCount = _segmentCount.asStateFlow()

    init {
        repositoryScope.launch(Dispatchers.Default) {
            prefRepo.preferenceUpdates.collect { key ->
                if (key in customWifiKeys) {
                    _configState.update { current -> loadConfig(current) }
                }
            }
        }
        repositoryScope.launch(Dispatchers.Default) {
            prefRepo.globalReloadEvent.collect {
                _configState.update { current -> loadConfig(current) }
            }
        }
        repositoryScope.launch(Dispatchers.Default) {
            fileStore.isReady.collect { ready ->
                if (ready && _configState.value.svgStyle == 2) reloadCustomSvg()
            }
        }
        repositoryScope.launch(Dispatchers.Default) {
            _configState.map { Triple(it.svgStyle, it.customSvg, it.alphaFg to it.alphaBg) }
                .distinctUntilChanged()
                .collectLatest { (_, customSvg, alpha) ->
                    updatePictures(resolveSvg(_configState.value.svgStyle, customSvg), alpha.first, alpha.second)
                }
        }
    }

    suspend fun importSvgFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = getFileName(uri)
            require(fileName.lowercase().endsWith(".svg")) { "Invalid file type: extension is not .svg" }
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?.trim()
                .orEmpty()
            require(content.isNotBlank()) { "SVG cannot be empty" }
            require(StackedMobileIconUtils.parseWifiSignalSegmentCount(content) != null) {
                "SVG must contain a continuous sequence of wifi_1 through wifi_N (N is 1 to 4)"
            }
            check(fileStore.writeText(Constants.REMOTE_FILE_CUSTOM_WIFI_SIGNAL, content)) {
                "Failed to write custom Wi-Fi SVG"
            }
            prefRepo.update(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_NAME, fileName)
            prefRepo.update(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_STYLE, 2)
            _configState.update { loadConfig(it).copy(customSvg = content) }
        }
    }

    private fun loadConfig(current: CustomWifiSignalState? = null) = CustomWifiSignalState(
        enabled = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.ENABLED),
        svgStyle = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_STYLE),
        customSvg = current?.customSvg.orEmpty(),
        customSvgName = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_NAME),
        alphaFg = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_FG).coerceIn(0f, 1f),
        alphaBg = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_BG).coerceIn(0f, 1f),
        scale = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SCALE).coerceIn(0.5f, 1.5f),
        paddingStart = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_START).coerceAtLeast(0f),
        paddingEnd = prefRepo.get(Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_END).coerceAtLeast(0f),
    )

    private suspend fun reloadCustomSvg() {
        val svg = fileStore.readText(Constants.REMOTE_FILE_CUSTOM_WIFI_SIGNAL).orEmpty()
        _configState.update { it.copy(customSvg = svg) }
    }

    private fun resolveSvg(style: Int, customSvg: String): String {
        if (style == 2 && StackedMobileIconUtils.parseWifiSignalSegmentCount(customSvg) != null) return customSvg
        val assetPath = when (style) {
            1 -> Constants.ASSETS_SVG_WIFI_IOS_26
            3 -> Constants.ASSETS_SVG_WIFI_IOS_27
            4 -> Constants.ASSETS_SVG_WIFI_HYPER_OS_2
            else -> Constants.ASSETS_SVG_WIFI_HYPER_OS_3
        }
        return runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private fun updatePictures(svg: String, alphaFg: Float, alphaBg: Float) {
        if (svg.isBlank()) return
        val key = WifiSvgRenderKey(svg.hashCode(), alphaFg, alphaBg)
        pictureCache.get(key)?.let { cached ->
            _pictures.value = cached
            _segmentCount.value = StackedMobileIconUtils.parseWifiSignalSegmentCount(svg) ?: 0
            return
        }
        repositoryScope.launch(Dispatchers.Default) {
            val generated = mutableMapOf<String, Picture>()
            val count = StackedMobileIconUtils.generateWifiSignalPictures(svg, generated, alphaFg, alphaBg) ?: return@launch
            pictureCache.put(key, generated)
            _pictures.value = generated
            _segmentCount.value = count
        }
    }

    private fun getFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return uri.path?.let(::File)?.name ?: "UNKNOWN.svg"
    }
}
