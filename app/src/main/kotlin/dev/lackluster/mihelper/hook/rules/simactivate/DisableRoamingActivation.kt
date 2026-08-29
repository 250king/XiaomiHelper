package dev.lackluster.mihelper.hook.rules.simactivate

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.DexKit
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import dev.lackluster.mihelper.hook.utils.d
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/** Prevents Xiaomi SIM activation tasks and result receivers for a roaming/foreign SIM. */
object DisableRoamingActivation : StaticHooker() {
    override val requireDexKit: Boolean = true

    private const val CLOUD_CONTROL_LOG =
        "cloud control not allow this mccmnc activate with method "

    private val startActivateSimMethod by lazy {
        DexKit.findMethodWithCache("roaming_activation_start_sim") {
            matcher {
                addUsingString("IOException", StringMatchType.Contains)
                addUsingString(CLOUD_CONTROL_LOG, StringMatchType.Contains)
            }
        }
    }

    private val startActivateImsMethod by lazy {
        DexKit.findMethodWithCache("roaming_activation_start_ims") {
            matcher {
                addUsingString("ImsActivateTask", StringMatchType.Contains)
                addUsingString(CLOUD_CONTROL_LOG, StringMatchType.Contains)
            }
        }
    }

    private val customGetterMethod by lazy {
        DexKit.findMethodWithCache("roaming_activation_custom_getter") {
            matcher {
                declaredClass {
                    addUsingString("ActivateExternal", StringMatchType.Contains)
                    addUsingString("set exception handler failed", StringMatchType.Contains)
                }
                addCaller {
                    declaredClass = "com.xiaomi.activate.ActivateService"
                    name = "onHandleIntent"
                }
            }
        }
    }

    private val slotIdGetterMethod by lazy {
        DexKit.findMethodWithCache("roaming_activation_slot_getter") {
            matcher {
                addUsingString("MiuiSysImpl", StringMatchType.Contains)
                addUsingString("Illegal slotId ", StringMatchType.Contains)
            }
        }
    }

    private val activateSubIdField by lazy {
        DexKit.findFieldWithCache("roaming_activation_sub_id") {
            matcher {
                declaredClass {
                    addUsingString("subId:", StringMatchType.Contains)
                    addUsingString(CLOUD_CONTROL_LOG, StringMatchType.Contains)
                }
                addReadMethod {
                    addUsingNumber(30000L)
                }
                type = "int"
                modifiers = Modifier.PUBLIC or Modifier.FINAL
            }
        }
    }

    private val activateContextField by lazy {
        DexKit.findFieldWithCache("roaming_activation_context") {
            matcher {
                declaredClass {
                    addUsingString("IOException", StringMatchType.Contains)
                    addUsingString(CLOUD_CONTROL_LOG, StringMatchType.Contains)
                }
                type = "android.content.Context"
                modifiers = Modifier.PROTECTED
            }
        }
    }

    private var radical = false

    override fun onInit() {
        val enabled = ParityPreferences.DISABLE_ROAMING_SIM_ACTIVATION.get()
        radical = ParityPreferences.DISABLE_ROAMING_SIM_ACTIVATION_RADICAL.get()
        updateSelfState(enabled)
        if (enabled) {
            startActivateSimMethod
            startActivateImsMethod
            customGetterMethod
            slotIdGetterMethod
            activateSubIdField
            activateContextField
        }
    }

    override fun onHook() {
        val subIdField = activateSubIdField?.getFieldInstance(classLoader)?.apply {
            isAccessible = true
        }
        val contextField = activateContextField?.getFieldInstance(classLoader)?.apply {
            isAccessible = true
        }
        val customGetter = customGetterMethod?.getMethodInstance(classLoader)?.apply {
            isAccessible = true
        }
        val slotGetter = slotIdGetterMethod?.getMethodInstance(classLoader)?.apply {
            isAccessible = true
        }

        listOfNotNull(startActivateSimMethod, startActivateImsMethod)
            .mapNotNull { method -> runCatching { method.getMethodInstance(classLoader) }.getOrNull() }
            .distinct()
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val target = thisObject ?: return@hook result(proceed())
                    val context = readContext(target, contextField)
                        ?: return@hook result(proceed())
                    val subId = readSubId(target, subIdField)
                        ?: return@hook result(proceed())
                    val slotId = resolveSlotId(subId, customGetter, slotGetter)
                    if (shouldSkipActivation(context, slotId)) {
                        d { "Roaming SIM on slot $slotId (subId $subId), skip activation task." }
                        result(null)
                    } else {
                        result(proceed())
                    }
                }
            }

        hookReceiver("com.xiaomi.activate.ActivationSmsReceiver", "extra_sim_index")
        hookReceiver("com.xiaomi.accountsdk.activate.ActivateStatusReceiver", "extra_sim_index")
        hookReceiver("com.xiaomi.activate.SimStateReceiver", "slot_id")
    }

    private fun readSubId(target: Any, field: Field?): Int? {
        field?.let { return runCatching { it.getInt(target) }.getOrNull() }
        return instanceFields(target.javaClass)
            .asSequence()
            .filter { it.type == Int::class.javaPrimitiveType }
            .mapNotNull { candidate -> runCatching { candidate.getInt(target) }.getOrNull() }
            .firstOrNull { SubscriptionManager.getSlotIndex(it) >= 0 }
    }

    private fun readContext(target: Any, field: Field?): Context? {
        field?.let { return runCatching { it.get(target) as? Context }.getOrNull() }
        return instanceFields(target.javaClass)
            .firstOrNull { Context::class.java.isAssignableFrom(it.type) }
            ?.let { candidate -> runCatching { candidate.get(target) as? Context }.getOrNull() }
    }

    private fun resolveSlotId(subId: Int, customGetter: Method?, slotGetter: Method?): Int {
        val exactSlot = runCatching {
            val getter = customGetter?.invoke(null) ?: return@runCatching -1
            (slotGetter?.invoke(getter, subId) as? Number)?.toInt() ?: -1
        }.getOrDefault(-1)
        if (exactSlot >= 0) return exactSlot
        return SubscriptionManager.getSlotIndex(subId)
    }

    private fun instanceFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    runCatching { field.isAccessible = true }
                    fields += field
                }
            current = current.superclass
        }
        return fields
    }

    private fun hookReceiver(className: String, slotExtra: String) {
        className.toClassOrNull()?.resolve()?.optional(true)?.firstMethodOrNull {
            name = "onReceive"
            parameterCount = 2
        }?.hook {
            val context = getArg(0) as? Context
            val intent = getArg(1) as? Intent
            val slotId = intent?.getIntExtra(slotExtra, -1) ?: -1
            if (context != null && shouldSkipActivation(context, slotId)) {
                d { "Roaming SIM on slot $slotId, skip ${className.substringAfterLast('.')}." }
                result(null)
            } else {
                result(proceed())
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds", "PrivateApi")
    private fun shouldSkipActivation(context: Context, slotId: Int): Boolean {
        if (slotId < 0) return radical
        return runCatching {
            val subscriptionInfo = findSubscriptionInfo(context, slotId) ?: return radical
            val phoneNumber = getFormattedPhoneNumber(context, subscriptionInfo)
            if (!phoneNumber.isNullOrBlank()) {
                return !startsWithAny(phoneNumber, listOf("+86", "86", "0086"))
            }

            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
                ?.createForSubscriptionId(subscriptionInfo.subscriptionId)
                ?: return radical
            val iccid = runCatching { telephonyManager.simSerialNumber }.getOrNull()
            if (!iccid.isNullOrBlank()) {
                return !startsWithAny(iccid, listOf("8986"))
            }

            telephonyManager.serviceState?.roaming ?: radical
        }.getOrDefault(radical)
    }

    @SuppressLint("MissingPermission")
    private fun findSubscriptionInfo(context: Context, slotId: Int): SubscriptionInfo? {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
        return manager.activeSubscriptionInfoList
            ?.firstOrNull { it.simSlotIndex == slotId }
    }

    @SuppressLint("MissingPermission")
    private fun getFormattedPhoneNumber(context: Context, info: SubscriptionInfo): String? {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val rawNumber = runCatching { manager.getPhoneNumber(info.subscriptionId) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            val mccTable = Class.forName("com.android.internal.telephony.MccTable")
            val countryCode = mccTable
                .getDeclaredMethod("countryCodeForMcc", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, info.mccString) as String
            PhoneNumberUtils.formatNumber(rawNumber, countryCode.uppercase(Locale.ROOT))
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: rawNumber
    }

    private fun startsWithAny(value: String, prefixes: Collection<String>): Boolean {
        val normalized = PhoneNumberUtils.normalizeNumber(value)
        return prefixes.any { prefix ->
            value.startsWith(prefix) ||
                normalized.startsWith(PhoneNumberUtils.normalizeNumber(prefix))
        }
    }
}
