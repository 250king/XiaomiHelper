package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.taplus.CustomSearch
import dev.lackluster.mihelper.hook.rules.taplus.ResourcesUtils
import dev.lackluster.mihelper.hook.rules.taplus.XiaoAiIntegration

object Taplus : StaticHooker() {
    override fun onInit() {
        attach(ResourcesUtils)
        attach(CustomSearch)
        attach(XiaoAiIntegration)
    }
}
