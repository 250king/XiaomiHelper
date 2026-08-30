package dev.lackluster.mihelper.hook.rules.themes

import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.DexKit
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/** Bypass Theme Manager's app-side rights check without globally changing DrmManager. */
object AllowThirdPartyThemeManager : StaticHooker() {
    override val requireDexKit: Boolean = true

    private val rightsChecks by lazy {
        DexKit.findMethodsWithCache("allow_third_party_theme_rights") {
            matcher {
                addUsingString("ThemeManagerTag", StringMatchType.Equals)
                addUsingString("check rights isLegal: ", StringMatchType.StartsWith)
                addUsingString("/system", StringMatchType.Equals)
            }
        }
    }

    override fun onInit() {
        val enabled = ParityPreferences.ALLOW_THIRD_PARTY_THEME.get()
        updateSelfState(enabled)
        if (enabled) rightsChecks
    }

    override fun onHook() {
        rightsChecks
            .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val success = drmSuccess((executable as Method).returnType)
                    if (success != null) result(success) else result(proceed())
                }
            }
    }

    private fun drmSuccess(returnType: Class<*>): Any? {
        if (returnType == Int::class.javaPrimitiveType || returnType == Int::class.javaObjectType) {
            return 0
        }
        return returnType.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == "DRM_SUCCESS" }
    }
}
