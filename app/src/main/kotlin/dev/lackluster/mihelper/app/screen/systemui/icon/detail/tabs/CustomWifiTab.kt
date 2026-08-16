package dev.lackluster.mihelper.app.screen.systemui.icon.detail.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import dev.lackluster.hyperx.ui.component.ImageIcon
import dev.lackluster.hyperx.ui.preference.EditTextPreference
import dev.lackluster.hyperx.ui.preference.SeekBarPreference
import dev.lackluster.hyperx.ui.preference.TextPreference
import dev.lackluster.hyperx.ui.preference.itemPreferenceGroup
import dev.lackluster.mihelper.R
import dev.lackluster.mihelper.app.repository.CustomWifiSignalState
import dev.lackluster.mihelper.app.widget.preference.DropDownOption
import dev.lackluster.mihelper.app.widget.preference.DropDownPreference
import dev.lackluster.mihelper.data.preference.Preferences

private val visibilityOptions = listOf(
    DropDownOption(0, R.string.icon_tuner_hide_selection_default),
    DropDownOption(1, R.string.icon_tuner_hide_selection_show_all),
    DropDownOption(2, R.string.icon_tuner_hide_selection_show_statusbar),
    DropDownOption(3, R.string.icon_tuner_hide_selection_show_qs),
    DropDownOption(4, R.string.icon_tuner_hide_selection_hidden),
)

private val customWifiSignalStyleOptions = listOf(
    DropDownOption(0, R.string.icon_detail_stacked_signal_style_miui),
    DropDownOption(1, R.string.icon_detail_stacked_signal_style_ios),
    DropDownOption(3, R.string.icon_detail_stacked_signal_style_ios27),
    DropDownOption(4, R.string.icon_detail_wifi_signal_style_hyperos2),
    DropDownOption(2, R.string.icon_detail_stacked_signal_style_custom),
)

fun LazyListScope.customWifiTabContent(
    isVisible: Boolean,
    state: CustomWifiSignalState,
    onImportSvg: () -> Unit,
) {
    if (!isVisible) return

    itemPreferenceGroup(
        key = "CUSTOM_WIFI_SIGNAL"
    ) {
        DropDownPreference(
            key = Preferences.SystemUI.StatusBar.CustomWifi.CUSTOM_WIFI_ICON,
            icon = ImageIcon(R.drawable.ic_stat_sys_wifi),
            title = stringResource(R.string.icon_tuner_custom_wifi),
            summary = stringResource(R.string.icon_tuner_custom_wifi_tips),
            options = visibilityOptions,
        )
        DropDownPreference(
            key = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SVG_STYLE,
            title = stringResource(R.string.icon_detail_wifi_signal_style),
            options = customWifiSignalStyleOptions,
        )
        AnimatedVisibility(state.svgStyle == 2) {
            TextPreference(
                title = stringResource(R.string.icon_detail_wifi_signal_style_custom_file),
                summary = state.customSvgName.ifBlank {
                    stringResource(R.string.icon_detail_stacked_signal_style_val_file)
                },
                onClick = onImportSvg,
            )
        }
        SeekBarPreference(
            key = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_FG,
            title = stringResource(R.string.icon_detail_stacked_signal_alpha_fg),
            min = 0f,
            max = 1f,
        )
        SeekBarPreference(
            key = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_ALPHA_BG,
            title = stringResource(R.string.icon_detail_stacked_signal_alpha_bg),
            min = 0f,
            max = 1f,
        )
        SeekBarPreference(
            key = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_SCALE,
            title = stringResource(R.string.icon_detail_stacked_signal_scale),
            min = 0.5f,
            max = 1.5f,
        )
        EditTextPreference(
            key = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_START,
            title = stringResource(R.string.icon_detail_wifi_padding_start),
            isValueValid = { it >= 0f },
        )
        EditTextPreference(
            key = Preferences.SystemUI.StatusBar.CustomWifi.SIGNAL_PADDING_END,
            title = stringResource(R.string.icon_detail_wifi_padding_end),
            isValueValid = { it >= 0f },
        )
    }
}
