/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of XiaomiHelper project.
 */

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
 * Restores the pre-Android 12 resolver behaviour for unverified web links.
 */
object DisableAppLinkVerify : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.DISABLE_APP_LINK_VERIFY.get())
    }

    override fun onHook() {
        "com.android.server.pm.verify.domain.DomainVerificationUtils"
            .toClassOrNull()
            ?.declaredMethods
            ?.filter { method ->
                method.name == "isDomainVerificationIntent" &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
            ?.forEach { method ->
                method.isAccessible = true
                runCatching { module.deoptimize(method) }
                method.hook { result(false) }
            }
    }
}
