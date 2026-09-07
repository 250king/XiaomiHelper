package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.themes.SkipSplash
import dev.lackluster.mihelper.hook.rules.themes.AllowThirdPartyThemeManager

object Themes : StaticHooker() {
    override val requireDexKit: Boolean = true

    override fun onInit() {
        attach(SkipSplash)
        attach(AllowThirdPartyThemeManager)
    }
}
