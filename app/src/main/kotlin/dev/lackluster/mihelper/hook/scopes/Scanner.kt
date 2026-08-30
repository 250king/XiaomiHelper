package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.scanner.PasskeyScannerFix

object Scanner : StaticHooker() {
    override fun onInit() {
        attach(PasskeyScannerFix)
    }
}
