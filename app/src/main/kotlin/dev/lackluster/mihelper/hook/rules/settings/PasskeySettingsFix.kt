/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Adapted from Howard20181/HyperPasskey.
 */

package dev.lackluster.mihelper.hook.rules.settings

import android.view.View
import android.widget.CompoundButton
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.DexKit
import dev.lackluster.mihelper.hook.utils.PasskeyUnsafe
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock

/** Restore the AOSP Credential Manager provider UI paths hidden by CN HyperOS Settings. */
object PasskeySettingsFix : StaticHooker() {
    override val requireDexKit: Boolean = true

    private val intlLock = ReentrantLock(true)
    private val intlDepth = ThreadLocal.withInitial { 0 }
    private val previousIntlValue = ThreadLocal<Boolean?>()

    private val leftSideClickMethods by lazy {
        DexKit.findMethodsWithCache("passkey_provider_left_side_click") {
            matcher {
                name = "onLeftSideClicked"
                paramCount = 0
            }
        }
    }

    private val checkChangedMethods by lazy {
        DexKit.findMethodsWithCache("passkey_provider_check_changed") {
            matcher {
                name = "onCheckChanged"
                returnType = "boolean"
                paramCount = 2
                paramTypes(null, "boolean")
            }
        }
    }

    override fun onInit() {
        val enabled = ParityPreferences.FIX_HYPEROS_PASSKEY.get()
        updateSelfState(enabled)
        if (enabled) {
            leftSideClickMethods
            checkChangedMethods
        }
    }

    override fun onHook() {
        val internationalField = runCatching {
            "miui.os.Build".toClassOrNull()
                ?.getDeclaredField("IS_INTERNATIONAL_BUILD")
                ?.apply { isAccessible = true }
        }.getOrNull()

        hookWithInternationalBuild(
            "com.android.settings.applications.credentials.DefaultCombinedPicker",
            "setDefaultKey",
            internationalField,
        )
        hookWithInternationalBuild(
            "com.android.settings.applications.credentials.DefaultCombinedPreferenceController",
            "getCombinedProviderInfos",
            internationalField,
        )
        hookWithInternationalBuild(
            "com.android.settings.applications.defaultapps.DefaultAppPreferenceController",
            "updateState",
            internationalField,
        )

        if (internationalField != null) {
            leftSideClickMethods
                .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                .filter {
                    it.declaringClass.name.startsWith("com.android.settings.applications.credentials")
                }
                .forEach { it.hookWithInternationalBuild(internationalField) }
        }

        hookCredentialCombiPreference()
    }

    private fun hookWithInternationalBuild(
        className: String,
        methodName: String,
        internationalField: Field?,
    ) {
        if (internationalField == null) return
        val clazz = className.toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { it.name == methodName }
            .forEach { method -> method.hookWithInternationalBuild(internationalField) }
    }

    private fun Method.hookWithInternationalBuild(internationalField: Field) {
        isAccessible = true
        runCatching { module.deoptimize(this) }
        hook {
            intlLock.lock()
            return@hook try {
                val depth = intlDepth.get() ?: 0
                if (depth == 0) {
                    val previous = runCatching { internationalField.getBoolean(null) }.getOrDefault(false)
                    previousIntlValue.set(previous)
                    if (!previous) PasskeyUnsafe.setStaticBoolean(internationalField, true)
                }
                intlDepth.set(depth + 1)

                val original = try {
                    proceed()
                } finally {
                    val nextDepth = (intlDepth.get() ?: 0) - 1
                    if (nextDepth <= 0) {
                        val previous = previousIntlValue.get()
                        previousIntlValue.remove()
                        intlDepth.remove()
                        if (previous != null) {
                            runCatching { PasskeyUnsafe.setStaticBoolean(internationalField, previous) }
                        }
                    } else {
                        intlDepth.set(nextDepth)
                    }
                }
                result(original)
            } finally {
                intlLock.unlock()
            }
        }
    }

    /**
     * Android 16+ Settings can create the credential-provider CombiPreference without wiring mSwitch.
     * Reconnect it dynamically instead of depending on Settings' private androidx classes at compile time.
     */
    private fun hookCredentialCombiPreference() {
        val clazz = "com.android.settings.applications.credentials.CredentialManagerPreferenceController\$CombiPreference"
            .toClassOrNull() ?: return
        val checkedField = findField(clazz, "mChecked") ?: return
        val clickListenerField = findField(clazz, "mOnClickListener") ?: return
        val switchField = findField(clazz, "mSwitch") ?: return
        val contentDescriptionMethod = clazz.declaredMethods.firstOrNull {
            it.name == "maybeUpdateContentDescription" && it.parameterCount == 0
        }?.apply { isAccessible = true }
        val switchId = runCatching {
            "com.android.settingslib.R\$id".toClassOrNull()
                ?.getDeclaredField("switchWidget")
                ?.apply { isAccessible = true }
                ?.getInt(null)
        }.getOrNull() ?: return
        val checkChangedMethod = checkChangedMethods
            .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
            .firstOrNull { candidate ->
                candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0].isAssignableFrom(clazz) &&
                    candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            ?.apply { isAccessible = true }
            ?: return

        clazz.declaredMethods
            .filter { it.name == "onBindViewHolder" && it.parameterCount == 1 }
            .forEach { method ->
                method.isAccessible = true
                runCatching { module.deoptimize(method) }
                method.hook {
                    val original = proceed()
                    val preference = thisObject
                    if (runCatching { switchField.get(preference) }.getOrNull() != null) {
                        return@hook result(original)
                    }

                    val holder = getArg(0) ?: return@hook result(original)
                    val itemView = findField(holder.javaClass, "itemView")
                        ?.let { runCatching { it.get(holder) as? View }.getOrNull() }
                        ?: return@hook result(original)
                    val switchView = itemView.findViewById<View>(switchId) as? CompoundButton
                        ?: return@hook result(original)

                    val checked = runCatching { checkedField.getBoolean(preference) }.getOrDefault(false)
                    switchView.isChecked = checked
                    switchView.setOnClickListener {
                        val listener = runCatching { clickListenerField.get(preference) }.getOrNull()
                            ?: return@setOnClickListener
                        val accepted = runCatching {
                            checkChangedMethod.invoke(listener, preference, switchView.isChecked) as? Boolean
                        }.getOrNull() ?: true
                        if (!accepted) {
                            runCatching { PasskeyUnsafe.setBoolean(checkedField, preference, false) }
                            switchView.isChecked = false
                        }
                    }
                    runCatching { PasskeyUnsafe.setObject(switchField, preference, switchView) }
                    runCatching { contentDescriptionMethod?.invoke(preference) }
                    result(original)
                }
            }
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
