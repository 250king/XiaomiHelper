package dev.lackluster.mihelper.hook.rules.android

import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import dev.lackluster.mihelper.hook.utils.toClassOrNull

/**
 * Allow third-party themes while PackageManager's ThemeReceiver validates a theme.
 * Adapted from HyperCeiler ThemeProvider; the DRM result is overridden only inside
 * the receiver invocation instead of globally disabling DrmManager checks.
 */
object AllowThirdPartyTheme : StaticHooker() {
    private val checkingTheme = ThreadLocal.withInitial { false }

    override fun onInit() {
        updateSelfState(ParityPreferences.ALLOW_THIRD_PARTY_THEME.get())
    }

    override fun onHook() {
        "com.android.server.pm.PackageManagerServiceImpl\$ThemeReceiver".toClassOrNull()
            ?.resolve()?.optional(true)?.firstMethodOrNull {
                name = "onReceive"
            }?.hook {
                checkingTheme.set(true)
                try {
                    result(proceed())
                } finally {
                    checkingTheme.remove()
                }
            }

        "miui.drm.DrmManager".toClassOrNull()?.apply {
            // DrmManager has changed overloads across MIUI/HyperOS releases.
            // Probe the known argument counts so every present isLegal overload is covered.
            (1..6).forEach { count ->
                resolve().optional(true).firstMethodOrNull {
                    name = "isLegal"
                    parameterCount = count
                }?.hook {
                    if (checkingTheme.get() == true) {
                        result(0) // DrmManager.DRM_SUCCESS
                    } else {
                        result(proceed())
                    }
                }
            }
        }
    }
}
