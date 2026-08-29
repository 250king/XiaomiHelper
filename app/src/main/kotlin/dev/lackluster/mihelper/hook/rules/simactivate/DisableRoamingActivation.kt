package dev.lackluster.mihelper.hook.rules.simactivate

import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import dev.lackluster.mihelper.hook.utils.toClassOrNull

/**
 * Prevent Xiaomi SIM activation from running for a mainland-China SIM while it is roaming.
 * The receiver-level checks use stable class names and mirror HyperCeiler's China/roaming guard
 * without depending on obfuscated activation task method names.
 */
object DisableRoamingActivation : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.DISABLE_ROAMING_SIM_ACTIVATION.get())
    }

    override fun onHook() {
        hookReceiver("com.xiaomi.activate.ActivationSmsReceiver", "extra_sim_index")
        hookReceiver("com.xiaomi.accountsdk.activate.ActivateStatusReceiver", "extra_sim_index")
        hookReceiver("com.xiaomi.activate.SimStateReceiver", "slot_id")
    }

    private fun hookReceiver(className: String, slotExtra: String) {
        className.toClassOrNull()?.resolve()?.optional(true)?.firstMethodOrNull {
            name = "onReceive"
            parameterCount = 2
        }?.hook {
            val context = getArg(0) as? Context
            val intent = getArg(1) as? Intent
            val slotId = intent?.getIntExtra(slotExtra, -1) ?: -1
            if (context != null && isChinaSimRoaming(context, slotId)) {
                result(null)
            } else {
                result(proceed())
            }
        }
    }

    private fun isChinaSimRoaming(context: Context, slotId: Int): Boolean {
        if (slotId < 0) return false
        return runCatching {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            val info = subscriptionManager?.getActiveSubscriptionInfoForSimSlotIndex(slotId) ?: return false
            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
                ?.createForSubscriptionId(info.subscriptionId) ?: return false

            val iccid = runCatching { info.iccId }.getOrNull().orEmpty()
            val isChinaSim = iccid.startsWith("8986") || telephonyManager.simCountryIso.equals("cn", true)
            isChinaSim && telephonyManager.isNetworkRoaming
        }.getOrDefault(false)
    }
}
