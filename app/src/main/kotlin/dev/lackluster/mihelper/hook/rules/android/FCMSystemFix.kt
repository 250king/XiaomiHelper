package dev.lackluster.mihelper.hook.rules.android

import android.content.Intent
import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get

/**
 * System-server side companion of [dev.lackluster.mihelper.hook.rules.powerkeeper.GMSBackgroundRunning].
 *
 * FCM receivers are detected dynamically from targeted broadcasts sent by GMS. There is intentionally
 * no maintained application allowlist: if GMS sends com.google.android.c2dm.intent.RECEIVE directly to
 * an installed package, that package is treated as an FCM consumer for this delivery.
 *
 * Hook points are adapted from HyperOS_FCM_Live.
 */
object FCMSystemFix : StaticHooker() {
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val GMS_PERSISTENT_PROCESS = "com.google.android.gms.persistent"
    private const val FCM_RECEIVE_ACTION = "com.google.android.c2dm.intent.RECEIVE"

    private val gmsConnectivityActions = setOf(
        "com.google.android.intent.action.GCM_RECONNECT",
        "com.google.android.gcm.DISCONNECTED",
        "com.google.android.gcm.CONNECTED",
        "com.google.android.gms.gcm.HEARTBEAT_ALARM",
    )

    override fun onInit() {
        updateSelfState(Preferences.PowerKeeper.GMS_BG_RUNNING.get())
    }

    override fun onHook() {
        hookGreezeManagerService()
        hookDomesticPolicyManager()
        hookBroadcastAutoStart()
    }

    private fun hookGreezeManagerService() {
        "com.miui.server.greeze.GreezeManagerService".toClassOrNull()?.apply {
            resolve().optional(true).firstMethodOrNull {
                name = "isAllowBroadcast"
                parameterCount = 5
            }?.hook {
                val callerPackage = getArg(1) as? String
                val calleePackage = getArg(3) as? String
                val action = getArg(4) as? String
                val isFcmDelivery = callerPackage == GMS_PACKAGE && action == FCM_RECEIVE_ACTION
                val isGmsConnectivity = (calleePackage == GMS_PACKAGE || calleePackage == GMS_PERSISTENT_PROCESS) &&
                    action in gmsConnectivityActions

                if (isFcmDelivery || isGmsConnectivity) {
                    result(true)
                } else {
                    result(proceed())
                }
            }

            resolve().optional(true).firstMethodOrNull {
                name = "deferBroadcastForMiui"
                parameterCount = 1
            }?.hook {
                val action = getArg(0) as? String
                if (action in gmsConnectivityActions) {
                    result(false)
                } else {
                    result(proceed())
                }
            }

            resolve().optional(true).firstMethodOrNull {
                name = "triggerGMSLimitAction"
                parameterCount = 1
            }?.hook {
                val newArgs = args.copyOf()
                newArgs[0] = false
                result(proceed(newArgs))
            }

            resolve().optional(true).firstMethodOrNull {
                name = "triggerGMSLimitAction"
                parameterCount = 0
            }?.hook {
                runCatching {
                    this@apply.getDeclaredField("mGmsLimitEnabled").apply { isAccessible = true }
                        .setBoolean(thisObject, false)
                }
                result(proceed())
            }
        }
    }

    private fun hookDomesticPolicyManager() {
        "com.miui.server.greeze.DomesticPolicyManager".toClassOrNull()?.apply {
            resolve().optional(true).firstMethodOrNull {
                name = "deferBroadcast"
                parameterCount = 1
            }?.hook {
                val action = getArg(0) as? String
                if (action in gmsConnectivityActions) {
                    result(false)
                } else {
                    result(proceed())
                }
            }
        }
    }

    private fun hookBroadcastAutoStart() {
        val broadcastRecordClass = "com.android.server.am.BroadcastRecord".toClassOrNull() ?: return
        val callerPackageField = runCatching {
            broadcastRecordClass.getDeclaredField("callerPackage").apply { isAccessible = true }
        }.getOrNull() ?: return
        val intentField = runCatching {
            broadcastRecordClass.getDeclaredField("intent").apply { isAccessible = true }
        }.getOrNull() ?: return

        "com.android.server.am.BroadcastQueueModernStubImpl".toClassOrNull()?.apply {
            resolve().optional(true).firstMethodOrNull {
                name = "checkApplicationAutoStart"
                parameterCount = 3
            }?.hook {
                val record = getArg(1)
                val callerPackage = runCatching { callerPackageField.get(record) as? String }.getOrNull()
                val intent = runCatching { intentField.get(record) as? Intent }.getOrNull()
                val targetPackage = intent?.`package` ?: intent?.component?.packageName

                if (
                    callerPackage == GMS_PACKAGE &&
                    intent?.action == FCM_RECEIVE_ACTION &&
                    !targetPackage.isNullOrBlank()
                ) {
                    result(true)
                } else {
                    result(proceed())
                }
            }
        }
    }
}
