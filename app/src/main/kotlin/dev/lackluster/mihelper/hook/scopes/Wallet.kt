package dev.lackluster.mihelper.hook.scopes

import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.wallet.DisableWalletPromotion

object Wallet : StaticHooker() {
    override fun onInit() {
        attach(DisableWalletPromotion)
    }
}
