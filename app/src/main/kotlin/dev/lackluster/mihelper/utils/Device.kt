package dev.lackluster.mihelper.utils

import android.os.Build
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import dev.lackluster.mihelper.hook.utils.toTyped

object Device {
    private const val TAG = "Device"

    private val clzBuild by lazy {
        try {
            "miui.os.Build".toClass()
        } catch (e: Exception) {
            MLog.e(TAG, e) { "miui.os.Build class not found" }
            null
        }
    }
    private val clzMiuiMultiDisplayTypeInfo by lazy {
        try {
            "miui.util.MiuiMultiDisplayTypeInfo".toClass()
        } catch (e: Exception) {
            MLog.e(TAG, e) { "miui.util.MiuiMultiDisplayTypeInfo class not found" }
            null
        }
    }

    private val clzDeviceFeature by lazy {
        try {
            "miui.os.DeviceFeature".toClass()
        } catch (e: Exception) {
            MLog.e(TAG, e) { "miui.os.DeviceFeature class not found" }
            null
        }
    }

    val isPad by lazy {
        clzBuild?.resolve()?.firstFieldOrNull {
            name = "IS_TABLET"
            modifiers(Modifiers.STATIC)
        }?.get<Boolean>() ?: false
    }
    val isInternationalBuild by lazy {
        clzBuild?.resolve()?.firstFieldOrNull {
            name = "IS_INTERNATIONAL_BUILD"
            modifiers(Modifiers.STATIC)
        }?.get<Boolean>() ?: false
    }
    val isGlobal by lazy {
        clzBuild?.resolve()?.firstFieldOrNull {
            name = "IS_GLOBAL_BUILD"
            modifiers(Modifiers.STATIC)
        }?.get<Boolean>() ?: false
    }

    val androidVersion by lazy {
        Build.VERSION.SDK_INT
    }

    val isIndependentRearDeviceAndSupportAssistant by lazy {
        val isIndependentRearDevice = clzMiuiMultiDisplayTypeInfo?.resolve()?.optional(true)?.firstMethodOrNull {
            name = "isIndependentRearDevice"
            modifiers(Modifiers.STATIC)
        }?.toTyped<Boolean>()?.invoke(null) == true
        val isSupportRearSmartAssistant = clzDeviceFeature?.resolve()?.optional(true)?.firstMethodOrNull {
            name = "isSupportRearSmartAssistant"
            modifiers(Modifiers.STATIC)
        }?.toTyped<Boolean>()?.invoke(null) != false
        isIndependentRearDevice && isSupportRearSmartAssistant
    }
}