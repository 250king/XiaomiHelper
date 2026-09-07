package dev.lackluster.mihelper.hook.rules.systemui.notif

import android.app.NotificationChannel
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * SystemUI companion for the extended notification settings switch.
 * It opens the exact channel settings from a notification and keeps
 * HyperOS from rendering channels whose effective importance is blocked.
 */
object MoreNotificationSettingsSystemUI : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.MORE_NOTIFICATION_SETTINGS.get())
    }

    override fun onHook() {
        hookNotificationInfoClick()
        hookImportanceRenderCompatibility()
    }

    private fun hookNotificationInfoClick() {
        val rowClass = "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow"
            .toClassOrNull() ?: return
        rowClass.declaredMethods
            .filter { it.name == "onClickInfoItem" }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val context = args.firstOrNull { it is Context } as? Context
                        ?: return@hook result(proceed())
                    val parent = readField(thisObject, "mParent")
                        ?: return@hook result(proceed())
                    val entry = invokeFirst(parent, "getEntry")
                        ?: return@hook result(proceed())
                    val channel = invokeFirst(entry, "getChannel") as? NotificationChannel
                        ?: return@hook result(proceed())
                    if (channel.id == "miscellaneous") return@hook result(proceed())

                    val notification = invokeFirst(entry, "getSbn")
                        ?: return@hook result(proceed())
                    val packageName = invokeFirst(notification, "getPackageName") as? String
                        ?: return@hook result(proceed())
                    val uid = (invokeFirst(notification, "getAppUid") as? Number)?.toInt()
                        ?: return@hook result(proceed())

                    val argsBundle = Bundle().apply {
                        putString("android.provider.extra.CHANNEL_ID", channel.id)
                        putString("package", packageName)
                        putInt("uid", uid)
                        putString("miui.targetPkg", packageName)
                    }
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(
                            ":android:show_fragment",
                            "com.android.settings.notification.ChannelNotificationSettings",
                        )
                        putExtra(":android:show_fragment_args", argsBundle)
                        setClassName("com.android.settings", "com.android.settings.SubSettings")
                    }
                    val opened = runCatching { context.startActivity(intent) }.isSuccess
                    if (opened) result(null) else result(proceed())
                }
            }
    }

    private fun hookImportanceRenderCompatibility() {
        val coordinator =
            "com.android.systemui.statusbar.notification.collection.coordinator.StackCoordinator\$attach\$1"
                .toClassOrNull() ?: return
        coordinator.declaredMethods
            .filter { it.name == "onAfterRenderList" && it.parameterCount >= 1 }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val groups = getArg(0) as? List<*> ?: return@hook result(proceed())
                    val filtered = groups.filter(::shouldRenderGroup)
                    if (filtered.size == groups.size) {
                        result(proceed())
                    } else {
                        val newArgs = args.toTypedArray()
                        newArgs[0] = ArrayList(filtered)
                        result(proceed(newArgs))
                    }
                }
            }
    }

    private fun shouldRenderGroup(group: Any?): Boolean {
        if (group == null) return true
        val entry = invokeFirst(group, "getRepresentativeEntry") ?: return true
        val ranking = readField(entry, "mRanking") ?: invokeFirst(entry, "getRanking") ?: return true
        val importance = (invokeFirst(ranking, "getImportance") as? Number)?.toInt() ?: return true
        return importance > 1
    }

    private fun readField(target: Any?, name: String): Any? {
        target ?: return null
        var current: Class<*>? = target.javaClass
        while (true) {
            val currentType = current ?: break
            val field: Field? = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
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
                .forEach { method: Method ->
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
