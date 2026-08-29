package dev.lackluster.mihelper.data.preference

import dev.lackluster.hyperx.ui.preference.core.PreferenceKey

/**
 * Preferences for parity hooks ported from Cemiuiler / HyperCeiler.
 * Kept separate from the upstream preference tree so the port stays easy to review and rebase.
 */
object ParityPreferences {
    val ALLOW_SCREENSHOT = PreferenceKey("parity_allow_screenshot", false)
    val DISABLE_DEVICE_NAME_CHECK = PreferenceKey("parity_disable_device_name_check", false)
    val ALLOW_THIRD_PARTY_THEME = PreferenceKey("parity_allow_third_party_theme", false)
    val HIDE_BLUETOOTH_UNLOCK_TOAST = PreferenceKey("parity_hide_bluetooth_unlock_toast", false)
    val DISABLE_ROAMING_SIM_ACTIVATION = PreferenceKey("parity_disable_roaming_sim_activation", false)
}
