/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of XiaomiHelper project.
 */

package dev.lackluster.mihelper.hook.rules.android

import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/** Suppresses the framework toast shown when an application reads the clipboard. */
object HideClipboardAccessToast : StaticHooker() {
    private const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
    private const val ACCESS_NOTIFICATION_LAMBDA_PREFIX = "lambda\$showAccessNotificationLocked\$"

    override fun onInit() {
        updateSelfState(ParityPreferences.HIDE_CLIPBOARD_USAGE_TOAST.get())
    }

    override fun onHook() {
        val clipboardService = CLIPBOARD_SERVICE.toClassOrNull() ?: return
        val toastLambdas = clipboardService.declaredMethods.filter { method ->
            method.name.startsWith(ACCESS_NOTIFICATION_LAMBDA_PREFIX) &&
                method.returnType == Void.TYPE
        }

        // Android 15/16 currently compile this path as ...$4 and Android 17 as ...$5.
        // Match the semantic prefix so vendor changes to the synthetic suffix do not break it.
        if (toastLambdas.isNotEmpty()) {
            toastLambdas.forEach { method ->
                method.isAccessible = true
                runCatching { module.deoptimize(method) }
                method.hook { result(null) }
            }
            return
        }

        // Fallback for a build whose compiler does not emit the synthetic lambda method.
        clipboardService.declaredMethods
            .filter { method ->
                method.name == "showAccessNotificationLocked" && method.returnType == Void.TYPE
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching { module.deoptimize(method) }
                method.hook { result(null) }
            }
    }
}
