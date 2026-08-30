package dev.lackluster.mihelper.hook.rules.android

import android.content.Context
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.util.concurrent.atomic.AtomicBoolean

/** Disable the Wi-Fi service's system-server device/hotspot-name validation path. */
object DisableDeviceNameCheckSystem : StaticHooker() {
    private val wifiUtilsHooked = AtomicBoolean(false)

    override fun onInit() {
        updateSelfState(ParityPreferences.DISABLE_DEVICE_NAME_CHECK.get())
    }

    override fun onHook() {
        val manager = "com.android.server.SystemServiceManager".toClassOrNull() ?: return
        manager.declaredMethods
            .filter { method ->
                method.name == "loadClassFromLoader" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[1] == ClassLoader::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val original = proceed()
                    val className = getArg(0) as? String
                    val loader = getArg(1) as? ClassLoader
                    if (className == "com.android.server.wifi.WifiService" && loader != null) {
                        hookWifiUtils(loader)
                    }
                    result(original)
                }
            }
    }

    private fun hookWifiUtils(loader: ClassLoader) {
        if (!wifiUtilsHooked.compareAndSet(false, true)) return
        val utilsClass = runCatching {
            Class.forName("com.android.server.wifi.Utils", false, loader)
        }.getOrNull() ?: run {
            wifiUtilsHooked.set(false)
            return
        }
        val methods = utilsClass.declaredMethods.filter { method ->
            method.name == "checkDeviceNameIsIllegalSync" &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(
                    arrayOf(Context::class.java, Int::class.javaPrimitiveType, String::class.java),
                )
        }
        if (methods.isEmpty()) {
            wifiUtilsHooked.set(false)
            return
        }
        methods.forEach { method ->
            method.isAccessible = true
            method.hook { result(false) }
        }
    }
}
