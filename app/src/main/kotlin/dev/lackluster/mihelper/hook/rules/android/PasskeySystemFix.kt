/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Adapted from Howard20181/HyperPasskey.
 */

package dev.lackluster.mihelper.hook.rules.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.PasskeyUnsafe
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/** Repair the system-server side of Credential Manager / passkey integration on CN HyperOS. */
object PasskeySystemFix : StaticHooker() {
    private const val GMS_HYBRID_SERVICE =
        "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService"
    private const val GMS_CHOOSER =
        "com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity"

    override fun onInit() {
        updateSelfState(ParityPreferences.FIX_HYPEROS_PASSKEY.get())
    }

    override fun onHook() {
        hookRequestSession()
        if (Build.VERSION.SDK_INT >= 35) hookIntentFactory()
    }

    private fun hookRequestSession() {
        val clazz = "com.android.server.credentials.RequestSession".toClassOrNull() ?: return
        val hybridField = runCatching {
            clazz.getDeclaredField("mHybridService").apply { isAccessible = true }
        }.getOrNull() ?: return

        clazz.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            constructor.hook {
                val original = proceed()
                runCatching {
                    PasskeyUnsafe.setObject(hybridField, thisObject, GMS_HYBRID_SERVICE)
                }
                result(original)
            }
        }
    }

    private fun hookIntentFactory() {
        val clazz = "android.credentials.selection.IntentFactory".toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { it.name == "getOemOverrideComponentName" }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val context = args.firstOrNull { it is Context } as? Context
                        ?: return@hook result(proceed())
                    val builder = args.firstOrNull {
                        it?.javaClass?.name == "android.credentials.selection.IntentCreationResult\$Builder"
                    } ?: return@hook result(proceed())

                    val component = ComponentName.unflattenFromString(GMS_CHOOSER)
                        ?: return@hook result(proceed())
                    if (!isUsableSystemActivity(context, component)) {
                        return@hook result(proceed())
                    }

                    runCatching {
                        invokeBuilder(builder, "setOemUiPackageName", component.packageName)
                        val statusClass = Class.forName(
                            "android.credentials.selection.IntentCreationResult\$OemUiUsageStatus",
                            false,
                            builder.javaClass.classLoader,
                        )
                        val success = statusClass.enumConstants
                            ?.firstOrNull { (it as? Enum<*>)?.name == "SUCCESS" }
                        if (success != null) invokeBuilder(builder, "setOemUiUsageStatus", success)
                    }
                    result(component)
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun isUsableSystemActivity(context: Context, component: ComponentName): Boolean {
        return runCatching {
            val pm = context.packageManager
            val info = pm.getActivityInfo(component, PackageManager.MATCH_SYSTEM_ONLY)
            var enabled = info.enabled
            when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> enabled = true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> enabled = false
            }
            enabled && info.exported
        }.getOrDefault(false)
    }

    private fun invokeBuilder(builder: Any, name: String, argument: Any) {
        val method = (builder.javaClass.methods.asSequence() + builder.javaClass.declaredMethods.asSequence())
            .firstOrNull { it.name == name && it.parameterCount == 1 }
            ?: return
        method.isAccessible = true
        method.invoke(builder, argument)
    }
}
