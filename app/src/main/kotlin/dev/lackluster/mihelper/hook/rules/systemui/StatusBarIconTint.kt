package dev.lackluster.mihelper.hook.rules.systemui

import android.graphics.PorterDuff
import android.widget.ImageView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import dev.lackluster.mihelper.data.Constants
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.lazyGet
import dev.lackluster.mihelper.hook.utils.toTyped
import kotlin.getValue

object StatusBarIconTint : StaticHooker() {
    private val stackedMobile by Preferences.SystemUI.StatusBar.StackedMobile.ENABLED.lazyGet()
    private val customWifi by Preferences.SystemUI.StatusBar.CustomWifi.ENABLED.lazyGet()

    override fun onInit() {
        updateSelfState(stackedMobile || customWifi)
    }

    override fun onHook() {
        // 强制着色来自动反色
        "com.android.systemui.statusbar.StatusBarIconView".toClassOrNull()?.apply {
            val fldSlot = resolve().firstFieldOrNull {
                name = "mSlot"
            }?.toTyped<String>()
            val metSetDecorColor = resolve().firstMethodOrNull {
                name = "setDecorColor"
                parameters(Int::class)
            }?.toTyped<Unit>()
            val metGetTint = "com.android.systemui.statusbar.DarkIconDispatcherExt".toClassOrNull()?.resolve()?.firstMethodOrNull {
                name = "getTint"
                parameterCount = 3
                modifiers(Modifiers.STATIC)
            }?.toTyped<Int>()
            resolve().firstMethodOrNull {
                name = "updateLightDarkTint"
            }?.hook {
                val ori = proceed()
                val iconView = thisObject as? ImageView
                val slot = fldSlot?.get(thisObject)
                if (iconView == null || slot == null) return@hook result(ori)
                when (slot) {
                    Constants.IconSlots.STACKED_MOBILE_TYPE, Constants.IconSlots.STACKED_MOBILE_ICON,
                    Constants.IconSlots.SINGLE_MOBILE_SIM1, Constants.IconSlots.SINGLE_MOBILE_SIM2,
                    Constants.IconSlots.CUSTOM_WIFI_SIGNAL,
                        -> {
                        metGetTint?.invoke(
                            null,
                            getArg(0),
                            iconView,
                            getArg(2),
                        )?.let { tint ->
//                            iconView.imageTintList = ColorStateList.valueOf(tint)
                            iconView.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
                            metSetDecorColor?.invoke(iconView, tint)
                        }
                    }
                }
                result(ori)
            }
        }
    }
}