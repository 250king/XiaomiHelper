/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Behavior adapted from Howard20181/HyperPasskey securityCenterHook.
 */

package dev.lackluster.mihelper.hook.rules.securitycenter

import android.content.ContentResolver
import android.provider.Settings
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/** Prevent Security Center startup config from overwriting Android autofill / credential providers. */
object PasskeySecurityCenterFix : StaticHooker() {
    private val protectedKeys = setOf(
        "autofill_service",
        "credential_service",
        "credential_service_primary",
    )

    override fun onInit() {
        updateSelfState(ParityPreferences.FIX_HYPEROS_PASSKEY.get())
    }

    override fun onHook() {
        Settings.Secure::class.java.declaredMethods
            .filter { method ->
                method.name == "putString" &&
                    method.parameterTypes.size >= 3 &&
                    method.parameterTypes[0] == ContentResolver::class.java &&
                    method.parameterTypes[1] == String::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val key = getArg(1) as? String
                    if (key in protectedKeys) result(true) else result(proceed())
                }
            }
    }
}
