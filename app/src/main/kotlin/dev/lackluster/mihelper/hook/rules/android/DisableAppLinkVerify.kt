package dev.lackluster.mihelper.hook.rules.android

import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/**
 * Disable Android App Links domain verification so matching third-party apps can
 * participate in normal ACTION_VIEW intent resolution instead of verified links
 * being forced down the domain-verification path.
 *
 * Adapted from HyperCeiler AppLinkVerify / tehcneko Disable app link verify.
 */
object DisableAppLinkVerify : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.DISABLE_APP_LINK_VERIFY.get())
    }

    override fun onHook() {
        val clazz = "com.android.server.pm.verify.domain.DomainVerificationUtils".toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { it.name == "isDomainVerificationIntent" }
            .forEach { method ->
                method.isAccessible = true
                method.hook { result(false) }
            }
    }
}
