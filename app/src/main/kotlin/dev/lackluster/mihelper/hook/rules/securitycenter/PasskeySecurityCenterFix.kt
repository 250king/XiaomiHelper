/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Behavior adapted from Howard20181/HyperPasskey securityCenterHook.
 */

package dev.lackluster.mihelper.hook.rules.securitycenter

import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.DexKit
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import org.luckypray.dexkit.query.enums.StringMatchType

/** Prevent Security Center startup config from overwriting Android autofill / credential providers. */
object PasskeySecurityCenterFix : StaticHooker() {
    override val requireDexKit: Boolean = true

    private val autofillInitializers by lazy {
        DexKit.findMethodsWithCache("passkey_securitycenter_autofill_initializer") {
            matcher {
                addUsingString("autofill_service", StringMatchType.Equals)
            }
        }
    }

    private val credentialInitializers by lazy {
        DexKit.findMethodsWithCache("passkey_securitycenter_credential_initializer") {
            matcher {
                addUsingString("credential_service", StringMatchType.Equals)
                addUsingString("credential_service_primary", StringMatchType.Equals)
            }
        }
    }

    override fun onInit() {
        val enabled = ParityPreferences.FIX_HYPEROS_PASSKEY.get()
        updateSelfState(enabled)
        if (enabled) {
            autofillInitializers
            credentialInitializers
        }
    }

    override fun onHook() {
        (autofillInitializers + credentialInitializers)
            .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
            .distinct()
            .filter { it.returnType == Void.TYPE }
            .forEach { method ->
                method.isAccessible = true
                runCatching { module.deoptimize(method) }
                method.hook { result(null) }
            }
    }
}
