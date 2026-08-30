package dev.lackluster.mihelper.hook.rules.systemui.lockscreen

import android.content.Context
import android.widget.Toast
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.util.Collections
import java.util.WeakHashMap

/** Suppress only the "unlocked by Bluetooth device" SystemUI toast. */
object DisableUnlockByBleToast : StaticHooker() {
    private const val TARGET_RESOURCE = "com.android.systemui:string/miui_keyguard_ble_unlock_succeed_msg"
    private val suppressedToasts = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    override fun onInit() {
        updateSelfState(ParityPreferences.HIDE_BLUETOOTH_UNLOCK_TOAST.get())
    }

    override fun onHook() {
        runCatching {
            Toast::class.java.getDeclaredMethod(
                "makeText",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()?.hook {
            val toast = proceed() as? Toast
            val context = getArg(0) as? Context
            val resId = getArg(1) as? Int
            if (toast != null && context != null && resId != null) {
                val resourceName = runCatching {
                    context.resources.getResourceName(resId)
                }.getOrNull()
                if (resourceName == TARGET_RESOURCE) {
                    suppressedToasts[toast] = true
                }
            }
            result(toast)
        }

        runCatching {
            Toast::class.java.getDeclaredMethod("show")
        }.getOrNull()?.hook {
            if (suppressedToasts.remove(thisObject) == true) {
                result(null)
            } else {
                result(proceed())
            }
        }
    }
}
