package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.simactivate.DisableRoamingActivation

object SimActivate : StaticHooker() {
    override val requireDexKit: Boolean = true

    override fun onInit() {
        attach(DisableRoamingActivation)
    }
}
