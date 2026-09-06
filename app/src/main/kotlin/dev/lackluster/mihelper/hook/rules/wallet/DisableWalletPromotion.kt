package dev.lackluster.mihelper.hook.rules.wallet

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/** Removes Xiaomi Wallet splash promotions and suppresses splash ad loading. */
object DisableWalletPromotion : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.DISABLE_WALLET_PROMOTION.get())
    }

    override fun onHook() {
        hookFinanceActivity()
        hookAdManager()
        hookSplashFragment()
    }

    private fun hookFinanceActivity() {
        val activityClass = "com.xiaomi.jr.app.MiFinanceActivity".toClassOrNull() ?: return
        activityClass.declaredMethods
            .filter { method ->
                method.name == "onCreate" &&
                    method.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
            }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val original = proceed()
                    val activity = thisObject as? Activity
                    if (activity != null) {
                        val resId = activity.resources.getIdentifier(
                            "splash_container",
                            "id",
                            activity.packageName,
                        )
                        if (resId > 0) {
                            activity.findViewById<View>(resId)?.visibility = View.GONE
                        }
                    }
                    result(original)
                }
            }
    }

    private fun hookAdManager() {
        val adManagerClass = "com.xiaomi.jr.ad.AdManager".toClassOrNull() ?: return
        adManagerClass.declaredMethods
            .filter { method ->
                method.parameterCount == 2 &&
                    method.parameterTypes[0] == Context::class.java &&
                    method.parameterTypes[1].name.contains("OnGetAdDataListener")
            }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val listener = getArg(1)
                    if (listener != null) invokeAdCallback(listener)
                    result(null)
                }
            }
    }

    private fun invokeAdCallback(listener: Any) {
        val callback = (listener.javaClass.methods.asSequence() +
            listener.javaClass.declaredMethods.asSequence())
            .firstOrNull { method ->
                method.name == "onGetAdData" && method.parameterCount == 1
            } ?: return
        runCatching {
            callback.isAccessible = true
            callback.invoke(listener, null)
        }
    }

    private fun hookSplashFragment() {
        val fragmentClass = "com.xiaomi.jr.app.splash.SplashFragment".toClassOrNull() ?: return
        fragmentClass.declaredMethods
            .filter { it.name == "onResume" && it.parameterCount == 0 }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val original = proceed()
                    val fragment = thisObject
                    if (fragment != null) {
                        val activity = invokeNoArg(fragment, "getActivity")
                        if (activity != null) {
                            invokeBoolean(activity, "finishSplash", false)
                        }
                    }
                    result(original)
                }
            }
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = (target.javaClass.methods.asSequence() +
            target.javaClass.declaredMethods.asSequence())
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun invokeBoolean(target: Any, name: String, value: Boolean) {
        val method = (target.javaClass.methods.asSequence() +
            target.javaClass.declaredMethods.asSequence())
            .firstOrNull { method ->
                method.name == name &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: return
        runCatching {
            method.isAccessible = true
            method.invoke(target, value)
        }
    }
}
