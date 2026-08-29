package dev.lackluster.mihelper.hook.rules.settings

import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.PasskeyUnsafe
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/**
 * Port of HyperCeiler AntiQues.
 * Temporarily exposes Settings to the international-build path while names are saved,
 * and disables the Bluetooth name-compliance gate.
 */
object DisableDeviceNameCheck : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.DISABLE_DEVICE_NAME_CHECK.get())
    }

    override fun onHook() {
        val buildClass = "miui.os.Build".toClassOrNull()
        val internationalField = runCatching {
            buildClass?.getDeclaredField("IS_INTERNATIONAL_BUILD")?.apply { isAccessible = true }
        }.getOrNull()

        fun hookWithInternationalBuild(className: String, methodName: String) {
            className.toClassOrNull()?.resolve()?.optional(true)?.firstMethodOrNull {
                name = methodName
            }?.hook {
                if (internationalField == null) {
                    result(proceed())
                    return@hook
                }
                val original = runCatching { internationalField.getBoolean(null) }.getOrDefault(false)
                try {
                    PasskeyUnsafe.setStaticBoolean(internationalField, true)
                    result(proceed())
                } finally {
                    runCatching { PasskeyUnsafe.setStaticBoolean(internationalField, original) }
                }
            }
        }

        hookWithInternationalBuild("com.android.settings.MiuiDeviceNameEditFragment", "onSave")
        hookWithInternationalBuild("com.android.settings.wifi.EditTetherFragment", "onSave")
        hookWithInternationalBuild("com.android.settings.DeviceNameCheckManager", "getDeviceNameCheckResult")

        "com.android.settings.bluetooth.MiuiBTUtils".toClassOrNull()?.apply {
            resolve().optional(true).firstMethodOrNull {
                name = "isSupportNameComplianceCheck"
            }?.hook { result(false) }

            resolve().optional(true).firstMethodOrNull {
                name = "isInternationalBuild"
                parameterCount = 0
            }?.hook { result(true) }
        }
    }
}
