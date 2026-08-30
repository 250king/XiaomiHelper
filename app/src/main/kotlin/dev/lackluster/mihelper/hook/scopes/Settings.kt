package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.settings.DisableDeviceNameCheck
import dev.lackluster.mihelper.hook.rules.settings.FontScale
import dev.lackluster.mihelper.hook.rules.settings.HeaderList
import dev.lackluster.mihelper.hook.rules.settings.PasskeySettingsFix
import dev.lackluster.mihelper.hook.rules.settings.QuickPermission
import dev.lackluster.mihelper.hook.rules.shared.XiaomiBrowserRedirect

object Settings : StaticHooker() {
    override fun onInit() {
        attach(FontScale)
        attach(HeaderList)
        attach(QuickPermission)
        attach(DisableDeviceNameCheck)
        attach(PasskeySettingsFix)
        attach(XiaomiBrowserRedirect)
    }
}