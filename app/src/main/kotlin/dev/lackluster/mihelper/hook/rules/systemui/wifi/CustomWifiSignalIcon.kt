package dev.lackluster.mihelper.hook.rules.systemui.wifi

import android.content.Context
import android.graphics.drawable.Icon
import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.BuildConfig
import dev.lackluster.mihelper.data.Constants
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.systemui.ResourcesUtils
import dev.lackluster.mihelper.hook.rules.systemui.compat.CommonClassUtils
import dev.lackluster.mihelper.hook.rules.systemui.compat.IconControllerCompat
import dev.lackluster.mihelper.hook.rules.systemui.compat.FlowCompat
import dev.lackluster.mihelper.hook.rules.systemui.compat.FlowCompat.collectFlow
import dev.lackluster.mihelper.hook.rules.systemui.compat.MutableStateFlowCompat
import dev.lackluster.mihelper.hook.utils.HostExecutor
import dev.lackluster.mihelper.hook.utils.RemotePreferences.lazyGet
import dev.lackluster.mihelper.hook.utils.d
import dev.lackluster.mihelper.hook.utils.e
import dev.lackluster.mihelper.hook.utils.toTyped
import java.io.FileInputStream
import kotlin.math.roundToInt

object CustomWifiSignalIcon : StaticHooker() {
    private val enabled by Preferences.SystemUI.StatusBar.CustomWifi.ENABLED.lazyGet()

    private var renderJob: Any? = null

    private val clzStatusBarIconController by "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl".lazyClassOrNull()
    private val fldContext by lazy {
        clzStatusBarIconController?.resolve()?.firstFieldOrNull { name = "mContext" }?.toTyped<Context>()
    }
    private val fldStatusBarIconList by lazy {
        clzStatusBarIconController?.resolve()?.firstFieldOrNull { name = "mStatusBarIconList" }?.toTyped<Any>()
    }
    private val metHandleSet by lazy {
        clzStatusBarIconController?.resolve()?.firstMethodOrNull { name = "handleSet" }?.toTyped<Unit>()
    }
    private val metGetIconHolder by lazy {
        "com.android.systemui.statusbar.phone.ui.StatusBarIconList".toClassOrNull()?.resolve()?.firstMethodOrNull {
            name = "getIconHolder"
            parameters(Int::class, String::class)
        }?.toTyped<Any>()
    }
    private val fldIcon by lazy {
        "com.android.systemui.statusbar.phone.StatusBarIconHolder".toClassOrNull()?.resolve()?.firstFieldOrNull {
            name = "icon"
        }?.toTyped<Any>()
    }
    private val clzStatusBarIcon by "com.android.internal.statusbar.StatusBarIcon".lazyClassOrNull()
    private val fldRealIcon by lazy {
        clzStatusBarIcon?.resolve()?.firstFieldOrNull { name = "icon" }?.toTyped<Icon>()
    }
    private val fldPkg by lazy {
        clzStatusBarIcon?.resolve()?.firstFieldOrNull { name = "pkg" }?.toTyped<String>()
    }

    override fun onInit() {
        updateSelfState(enabled)
    }

    override fun onHook() {
        // 刷新图标的 Flow
        "com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter".toClassOrNull()?.apply {
            val controller = resolve().firstFieldOrNull {
                name = "iconController"
            }?.toTyped<Any>()
            resolve().firstConstructor().hook {
                val ori = proceed()
                val iconController = controller?.get(thisObject)
                val context = iconController?.let { fldContext?.get(it) }
                val coroutineScope = WifiIconInteractor.coroutineScope
                if (iconController != null && context != null && coroutineScope != null) {
                    // 刷新图标通用方法
                    val renderIcon: (WifiSignalState) -> Unit = { state ->
                        val icon = if (state is WifiSignalState.Visible) {
                            WifiIconRenderEngine.getIcon(context, state.level)
                        } else {
                            null
                        }
                        HostExecutor.runOnMain {
                            if (icon != null) {
                                updateIcon(iconController, icon)
                                IconControllerCompat.setIconVisibility(iconController, Constants.IconSlots.CUSTOM_WIFI_SIGNAL, true)
                            } else {
                                IconControllerCompat.setIconVisibility(iconController, Constants.IconSlots.CUSTOM_WIFI_SIGNAL, false)
                            }
                        }
                    }
                    // 初始化图标缓存
                    HostExecutor.execute(
                        tag = "PRELOAD_CUSTOM_WIFI_SVG",
                        backgroundTask = {
                            val customSvg = runCatching {
                                module.openRemoteFile(Constants.REMOTE_FILE_CUSTOM_WIFI_SIGNAL).use { pfd ->
                                    FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                                }
                            }.getOrNull()
                            WifiIconRenderEngine.preload(
                                context = context.applicationContext,
                                iconHeightResId = ResourcesUtils.status_bar_icon_height,
                                customSvg = customSvg
                            )
                        },
                        runOnMain = true,
                        onResult = {
                            // 数据在图标就绪前到达，补一次刷新
                            WifiIconInteractor.proxyWifiSignal.getValue()?.let(renderIcon)
                        }
                    )
                    // 启动刷新图标的 Flow
                    FlowCompat.cancelJob(renderJob)
                    WifiIconInteractor.proxyWifiSignal.collectFlow(coroutineScope, renderIcon).let { renderJob = it }
                }
                result(ori)
            }
        }
        // 监听数据的 Flow
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel".toClassOrNull()?.apply {
            val fldWifiIcon = resolve().firstFieldOrNull {
                name = "wifiIcon"
            }?.toTyped<Any>()
            val hiddenWifiIcon = $$"com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon$Hidden".toClassOrNull()
                ?.resolve()?.firstFieldOrNull {
                    name = "INSTANCE"
                }?.get()
            val clzWifiInteractorImpl = "com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl".toClassOrNull()
            val fldWifiNetwork = clzWifiInteractorImpl?.resolve()?.firstFieldOrNull {
                name = "wifiNetwork"
            }?.toTyped<Any>()
            val clzActive = $$"com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel$Active".toClassOrNull()
            val activeLevel = clzActive?.resolve()?.firstFieldOrNull {
                name = "level"
            }?.toTyped<Int>()
            val clzCarrierMerged = $$"com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel$CarrierMerged".toClassOrNull()
            val carrierLevel = clzCarrierMerged?.resolve()?.firstFieldOrNull {
                name = "level"
            }?.toTyped<Int>()
            val carrierNumberOfLevels = clzCarrierMerged?.resolve()?.firstFieldOrNull {
                name = "numberOfLevels"
            }?.toTyped<Int>()
            resolve().firstConstructor().hook {
                val ori = proceed()
                hiddenWifiIcon?.let { hidden ->
                    MutableStateFlowCompat(hidden).toReadonlyStateFlow()?.let { hiddenFlow ->
                        fldWifiIcon?.set(thisObject, hiddenFlow)
                    }
                }
                val scope = args.firstOrNull { CommonClassUtils.clzCoroutineScope?.isInstance(it) == true }
                val interactor = args.firstOrNull { clzWifiInteractorImpl?.isInstance(it) == true }
                val networkFlow = interactor?.let { fldWifiNetwork?.get(it) }
                if (scope == null || networkFlow == null) return@hook result(ori)

                WifiIconInteractor.start(scope, networkFlow) { network ->
                    when {
                        clzActive?.isInstance(network) == true -> {
                            d { "active: ${network?.toString()}" }
                            activeLevel?.get(network)?.let {
                                WifiSignalState.Visible((it * 4f / 3).roundToInt().coerceIn(0, 4))
                            } ?: WifiSignalState.Hidden
                        }
                        clzCarrierMerged?.isInstance(network) == true -> {
                            val level = carrierLevel?.get(network)
                            val total = carrierNumberOfLevels?.get(network)
                            if (level == null || total == null || total <= 0) {
                                WifiSignalState.Hidden
                            } else {
                                WifiSignalState.Visible((level * 4f / total).roundToInt().coerceIn(0, 4))
                            }
                        }
                        else -> WifiSignalState.Hidden
                    }
                }
                result(ori)
            }
        }
    }

    private fun updateIcon(controller: Any, icon: Icon) {
        try {
            val list = fldStatusBarIconList?.get(controller)
            var holder = metGetIconHolder?.invoke(list, 0, Constants.IconSlots.CUSTOM_WIFI_SIGNAL)
            if (holder == null) {
                IconControllerCompat.setIcon(
                    controller,
                    null,
                    Constants.IconSlots.CUSTOM_WIFI_SIGNAL,
                    ResourcesUtils.stat_sys_wifi_signal_0
                )
                holder = metGetIconHolder?.invoke(list, 0, Constants.IconSlots.CUSTOM_WIFI_SIGNAL)
            }
            if (holder != null) {
                val statusBarIcon = fldIcon?.get(holder) ?: return
                fldPkg?.set(statusBarIcon, BuildConfig.APPLICATION_ID)
                fldRealIcon?.set(statusBarIcon, icon)
                metHandleSet?.invoke(controller, Constants.IconSlots.CUSTOM_WIFI_SIGNAL, holder)
            }
        } catch (t: Throwable) {
            e(t) { "Failed to update custom Wi-Fi signal icon" }
        }
    }
}
