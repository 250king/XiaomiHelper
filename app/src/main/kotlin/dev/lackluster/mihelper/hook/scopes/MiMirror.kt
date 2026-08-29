package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.mimirror.ContinueTasks
import dev.lackluster.mihelper.hook.rules.shared.XiaomiBrowserRedirect

object MiMirror : StaticHooker() {
    override val requireDexKit: Boolean = true

    override fun onInit() {
        attach(ContinueTasks)
        attach(XiaomiBrowserRedirect)
    }
}