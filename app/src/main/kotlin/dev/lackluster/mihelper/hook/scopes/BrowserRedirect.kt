package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.shared.XiaomiBrowserRedirect

/** Shared scope for Xiaomi components that only need the forced-browser redirect fix. */
object BrowserRedirect : StaticHooker() {
    override fun onInit() {
        attach(XiaomiBrowserRedirect)
    }
}
