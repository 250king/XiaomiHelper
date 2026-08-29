package dev.lackluster.mihelper.data.preference

import dev.lackluster.hyperx.ui.preference.core.PreferenceKey

/**
 * Preferences for parity hooks ported from Cemiuiler / HyperCeiler and focused standalone modules.
 * Kept separate from the upstream preference tree so the port stays easy to review and rebase.
 */
object ParityPreferences {
    val ALLOW_SCREENSHOT = PreferenceKey("parity_allow_screenshot", false)
    val DISABLE_DEVICE_NAME_CHECK = PreferenceKey("parity_disable_device_name_check", false)
    val ALLOW_THIRD_PARTY_THEME = PreferenceKey("parity_allow_third_party_theme", false)
    val HIDE_BLUETOOTH_UNLOCK_TOAST = PreferenceKey("parity_hide_bluetooth_unlock_toast", false)
    val DISABLE_ROAMING_SIM_ACTIVATION = PreferenceKey("parity_disable_roaming_sim_activation", false)
    val DISABLE_ROAMING_SIM_ACTIVATION_RADICAL = PreferenceKey("parity_disable_roaming_sim_activation_radical", false)
    val HIDE_CLIPBOARD_USAGE_TOAST = PreferenceKey("parity_hide_clipboard_usage_toast", false)
    val DISABLE_APP_LINK_VERIFY = PreferenceKey("parity_disable_app_link_verify", false)
    val REDIRECT_XIAOMI_BROWSER = PreferenceKey("parity_redirect_xiaomi_browser", false)
    val FIX_HYPEROS_PASSKEY = PreferenceKey("parity_fix_hyperos_passkey", false)
}
