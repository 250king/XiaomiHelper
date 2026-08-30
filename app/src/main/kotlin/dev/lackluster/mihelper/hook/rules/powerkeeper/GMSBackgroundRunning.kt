package dev.lackluster.mihelper.hook.rules.powerkeeper

import android.provider.Settings
import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/**
 * @link https://github.com/kooritea/fcmfix/blob/master/app/src/main/java/com/kooritea/fcmfix/xposed/PowerkeeperFix.java
 */
object GMSBackgroundRunning : StaticHooker() {
    private const val MILLET_NO_RESTRICT_APP = "MILLET_NO_RESTRICT_APP"
    private const val GMS_PACKAGE = "com.google.android.gms"

    override fun onInit() {
        updateSelfState(Preferences.PowerKeeper.GMS_BG_RUNNING.get())
    }

    override fun onHook() {
        preserveMilletNoRestrictSetting()
        "com.miui.powerkeeper.utils.GmsObserver".toClassOrNull()?.apply {
            resolve().optional(true).firstMethodOrNull {
                name = "updateGoogleReletivesWakelock"
            }?.hook {
                val newArgs = args.toTypedArray()
                newArgs[0] = false
                result(proceed(newArgs))
            }
            resolve().firstMethodOrNull {
                name = "isGmsControlEnabled"
            }?.hook {
                result(false)
            }
        }
        $$"com.miui.powerkeeper.provider.SimpleSettings$Misc".toClassOrNull()?.apply {
            resolve().firstMethodOrNull {
                name = "getBoolean"
                parameterCount = 3
            }?.hook {
                val key = getArg(1) as? String
                if (key == "gms_control") {
                    result(false)
                } else {
                    result(proceed())
                }
            }
        }
    }

    private fun preserveMilletNoRestrictSetting() {
        Settings.System::class.java.declaredMethods
            .filter { method ->
                method.name.startsWith("putString") &&
                    method.parameterTypes.count { it == String::class.java } >= 2
            }
            .forEach { method ->
                val stringIndexes = method.parameterTypes
                    .mapIndexedNotNull { index, type -> index.takeIf { type == String::class.java } }
                if (stringIndexes.size < 2) return@forEach
                method.isAccessible = true
                method.hook {
                    val keyIndex = stringIndexes[0]
                    val valueIndex = stringIndexes[1]
                    if (getArg(keyIndex) != MILLET_NO_RESTRICT_APP) {
                        return@hook result(proceed())
                    }
                    val existing = (getArg(valueIndex) as? String)
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        .orEmpty()
                    if (GMS_PACKAGE in existing) return@hook result(proceed())
                    val newArgs = args.toTypedArray()
                    newArgs[valueIndex] = (existing + GMS_PACKAGE).distinct().joinToString(", ")
                    result(proceed(newArgs))
                }
            }
    }
}
