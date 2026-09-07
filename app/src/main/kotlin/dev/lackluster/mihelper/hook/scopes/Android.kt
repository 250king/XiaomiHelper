package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.android.AllowMoreFreeform
import dev.lackluster.mihelper.hook.rules.android.AllowScreenshot
import dev.lackluster.mihelper.hook.rules.android.AllowThirdPartyTheme
import dev.lackluster.mihelper.hook.rules.android.DarkModeForAll
import dev.lackluster.mihelper.hook.rules.android.DisableAppLinkVerify
import dev.lackluster.mihelper.hook.rules.android.DisableDeviceNameCheckSystem
import dev.lackluster.mihelper.hook.rules.android.DisableWakePathChecker
import dev.lackluster.mihelper.hook.rules.android.FCMSystemFix
import dev.lackluster.mihelper.hook.rules.android.FontScale
import dev.lackluster.mihelper.hook.rules.android.HideClipboardAccessToast
import dev.lackluster.mihelper.hook.rules.android.PasskeySystemFix
import dev.lackluster.mihelper.hook.rules.android.RemoveFreeformRestriction
import dev.lackluster.mihelper.hook.rules.shared.XiaomiBrowserRedirect

object Android : StaticHooker() {
    override fun onInit() {
        attach(DarkModeForAll)
        attach(RemoveFreeformRestriction)
        attach(AllowMoreFreeform)
        attach(FontScale)
        attach(DisableWakePathChecker)
        attach(DisableDeviceNameCheckSystem)
        attach(DisableAppLinkVerify)
        attach(FCMSystemFix)
        attach(AllowScreenshot)
        attach(AllowThirdPartyTheme)
        attach(HideClipboardAccessToast)
        attach(PasskeySystemFix)
        attach(XiaomiBrowserRedirect)
    }
}
