/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Adapted from Howard20181/HyperPasskey hookMiFiDoBean.
 */

package dev.lackluster.mihelper.hook.rules.scanner

import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/** Prevent Xiaomi Scanner from forcing a Xiaomi app package for FIDO/passkey QR flows. */
object PasskeyScannerFix : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.FIX_HYPEROS_PASSKEY.get())
    }

    override fun onHook() {
        val clazz = "com.xiaomi.scanner.module.code.utils.bean.MiFiDoBean".toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { it.name == "getAppPackageName" && it.parameterCount == 0 }
            .forEach { method ->
                method.isAccessible = true
                method.hook { result("") }
            }
    }
}
