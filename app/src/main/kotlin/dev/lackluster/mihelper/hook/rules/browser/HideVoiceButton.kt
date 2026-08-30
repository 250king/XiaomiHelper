package dev.lackluster.mihelper.hook.rules.browser

import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

object HideVoiceButton : StaticHooker() {
    override fun onInit() {
        updateSelfState(Preferences.Browser.HIDE_VOICE_BUTTON.get())
    }

    override fun onHook() {
        "com.android.browser.homepage.premium.view.PressVoiceButtonView".toClassOrNull()?.apply {
            resolve().firstMethodOrNull {
                name = "show"
            }?.hook {
                result(null)
            }
        }
    }
}