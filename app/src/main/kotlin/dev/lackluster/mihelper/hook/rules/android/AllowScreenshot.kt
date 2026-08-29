package dev.lackluster.mihelper.hook.rules.android

import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/** Allow screenshots from windows that set FLAG_SECURE. */
object AllowScreenshot : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.ALLOW_SCREENSHOT.get())
    }

    override fun onHook() {
        "com.android.server.wm.WindowState".toClassOrNull()?.resolve()?.optional(true)?.firstMethodOrNull {
            name = "isSecureLocked"
            parameterCount = 0
        }?.hook {
            result(false)
        }
    }
}
