package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.aiengine.CopyWebsite
import dev.lackluster.mihelper.hook.rules.shared.XiaomiBrowserRedirect

object AIEngine : StaticHooker() {
    override val requireDexKit: Boolean = true

    override fun onInit() {
        attach(CopyWebsite)
        attach(XiaomiBrowserRedirect)
    }
}