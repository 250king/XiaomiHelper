package dev.lackluster.mihelper.hook.rules.settings

import android.app.NotificationChannel
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Restores Android notification-channel controls hidden by HyperOS Settings.
 * The implementation is reflection-based so it can tolerate both legacy and
 * newer Settings package layouts without depending on hidden framework APIs.
 */
object MoreNotificationSettings : StaticHooker() {
    private val visiblePreferenceKeys = setOf("importance", "badge", "allow_keyguard")

    override fun onInit() {
        updateSelfState(ParityPreferences.MORE_NOTIFICATION_SETTINGS.get())
    }

    override fun onHook() {
        hookPreferenceVisibility()
        hookChannelImportance()
    }

    private fun hookPreferenceVisibility() {
        listOf(
            "com.android.settings.notification.BaseNotificationSettings",
            "com.android.settings.notification.app.BaseNotificationSettings",
        ).mapNotNull { it.toClassOrNull() }
            .flatMap { clazz -> clazz.declaredMethods.asList() }
            .filter { it.name == "setPrefVisible" && it.parameterCount >= 2 }
            .distinctBy(Method::toGenericString)
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val preference = getArg(0)
                    val key = preference?.let { invokeFirst(it, "getKey") as? String }
                    if (key in visiblePreferenceKeys) {
                        val newArgs = args.toTypedArray()
                        newArgs[1] = true
                        result(proceed(newArgs))
                    } else {
                        result(proceed())
                    }
                }
            }
    }

    private fun hookChannelImportance() {
        listOf(
            "com.android.settings.notification.ChannelNotificationSettings",
            "com.android.settings.notification.app.ChannelNotificationSettings",
        ).mapNotNull { it.toClassOrNull() }
            .flatMap { clazz -> clazz.declaredMethods.asList() }
            .filter { it.name == "setupChannelDefaultPrefs" }
            .distinctBy(Method::toGenericString)
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val original = proceed()
                    runCatching { configureImportancePreference(thisObject) }
                    result(original)
                }
            }
    }

    private fun configureImportancePreference(settings: Any?) {
        settings ?: return
        val preference = invokeFirst(settings, "findPreference", "importance") ?: return
        writeField(settings, "mImportance", preference)

        val backupImportance = (readField(settings, "mBackupImportance") as? Number)?.toInt() ?: 0
        if (backupImportance > 0) {
            val index = (invokeFirst(
                preference,
                "findSpinnerIndexOfValue",
                backupImportance.toString(),
            ) as? Number)?.toInt() ?: -1
            if (index >= 0) invokeFirst(preference, "setValueIndex", index)
        }

        val listenerClass = runCatching {
            Class.forName(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
                false,
                classLoader,
            )
        }.getOrNull() ?: return

        val listener = Proxy.newProxyInstance(
            classLoader,
            arrayOf(listenerClass),
        ) { proxy, method, methodArgs ->
            when (method.name) {
                "equals" -> proxy === methodArgs?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MoreNotificationSettingsListener@${Integer.toHexString(System.identityHashCode(proxy))}"
                "onPreferenceChange" -> {
                    val importance = methodArgs?.getOrNull(1)?.toString()?.toIntOrNull()
                    if (importance != null) updateImportance(settings, importance)
                    true
                }
                else -> null
            }
        }
        invokeFirst(preference, "setOnPreferenceChangeListener", listener)
    }

    private fun updateImportance(settings: Any, importance: Int) {
        writeField(settings, "mBackupImportance", importance)
        val channel = readField(settings, "mChannel") as? NotificationChannel ?: return
        channel.setImportance(importance)
        invokeFirst(channel, "lockFields", 4)

        val backend = readField(settings, "mBackend") ?: return
        val pkg = readField(settings, "mPkg") as? String ?: return
        val uid = (readField(settings, "mUid") as? Number)?.toInt() ?: return
        invokeFirst(backend, "updateChannel", pkg, uid, channel)
        invokeFirst(settings, "updateDependents", false)
    }

    private fun readField(target: Any, name: String): Any? =
        findField(target.javaClass, name)?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(target)
            }.getOrNull()
        }

    private fun writeField(target: Any, name: String, value: Any?) {
        findField(target.javaClass, name)?.let { field ->
            runCatching {
                field.isAccessible = true
                if (field.type == Int::class.javaPrimitiveType && value is Number) {
                    field.setInt(target, value.toInt())
                } else {
                    field.set(target, value)
                }
            }
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (true) {
            val currentType = current ?: break
            runCatching { currentType.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = currentType.superclass
        }
        return null
    }

    private fun invokeFirst(target: Any, name: String, vararg values: Any?): Any? {
        var current: Class<*>? = target.javaClass
        while (true) {
            val currentType = current ?: break
            currentType.declaredMethods
                .asSequence()
                .filter { it.name == name && it.parameterCount == values.size }
                .forEach { method ->
                    val invocation = runCatching {
                        method.isAccessible = true
                        method.invoke(target, *values)
                    }
                    if (invocation.isSuccess) return invocation.getOrNull()
                }
            current = currentType.superclass
        }
        return null
    }
}
