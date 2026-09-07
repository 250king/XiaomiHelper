package dev.lackluster.mihelper.hook.rules.android

import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.lang.reflect.Method

/**
 * Allow third-party themes while PackageManager's ThemeReceiver validates a theme.
 * Adapted from HyperCeiler ThemeProvider; the DRM result is overridden only inside
 * the receiver invocation instead of globally disabling DrmManager checks.
 */
object AllowThirdPartyTheme : StaticHooker() {
    private val validationDepth = ThreadLocal.withInitial { 0 }

    override fun onInit() {
        updateSelfState(ParityPreferences.ALLOW_THIRD_PARTY_THEME.get())
    }

    override fun onHook() {
        val receivers = listOf(
            "miui.drm.ThemeReceiver" to setOf("validateTheme"),
            "com.android.server.pm.PackageManagerServiceImpl\$ThemeReceiver" to
                setOf("validateTheme", "onReceive"),
        )
        receivers.forEach { (className, names) ->
            className.toClassOrNull()?.declaredMethods
                ?.filter { it.name in names }
                ?.forEach { method ->
                    method.isAccessible = true
                    method.hook {
                        val depth = validationDepth.get() ?: 0
                        validationDepth.set(depth + 1)
                        try {
                            result(proceed())
                        } finally {
                            if (depth == 0) validationDepth.remove() else validationDepth.set(depth)
                        }
                    }
                }
        }

        "miui.drm.DrmManager".toClassOrNull()?.declaredMethods
            ?.filter { it.name == "isLegal" }
            ?.forEach { method ->
                method.isAccessible = true
                method.hook {
                    if ((validationDepth.get() ?: 0) <= 0) return@hook result(proceed())
                    val success = drmSuccess((executable as Method).returnType)
                    if (success != null) result(success) else result(proceed())
                }
            }
    }

    private fun drmSuccess(returnType: Class<*>): Any? {
        if (returnType == Int::class.javaPrimitiveType || returnType == Int::class.javaObjectType) {
            return 0
        }
        if (returnType.isEnum) {
            return returnType.enumConstants
                ?.firstOrNull { (it as? Enum<*>)?.name == "DRM_SUCCESS" }
        }
        return runCatching {
            returnType.getDeclaredField("DRM_SUCCESS").apply { isAccessible = true }.get(null)
        }.getOrNull()
    }
}
