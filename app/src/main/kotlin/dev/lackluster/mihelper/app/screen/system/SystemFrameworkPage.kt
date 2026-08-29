package dev.lackluster.mihelper.app.screen.system

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.lackluster.hyperx.core.utils.toDecimalString
import dev.lackluster.hyperx.ui.dialog.AlertDialog
import dev.lackluster.hyperx.ui.dialog.AlertDialogMode
import dev.lackluster.hyperx.ui.layout.HyperXPage
import dev.lackluster.hyperx.ui.preference.EditTextPreference
import dev.lackluster.hyperx.ui.preference.ItemPosition
import dev.lackluster.hyperx.ui.preference.SwitchPreference
import dev.lackluster.hyperx.ui.preference.TextPreference
import dev.lackluster.hyperx.ui.preference.itemPreferenceGroup
import dev.lackluster.mihelper.R
import dev.lackluster.mihelper.app.component.RebootActionItem
import dev.lackluster.mihelper.app.screen.system.SystemFrameworkAction.*
import dev.lackluster.hyperx.ui.preference.core.LocalPreferenceActions
import dev.lackluster.mihelper.app.state.UiText
import dev.lackluster.mihelper.app.utils.showToast
import dev.lackluster.mihelper.app.utils.toUiText
import dev.lackluster.mihelper.data.Scope
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.data.preference.Preferences
import org.koin.androidx.compose.koinViewModel

sealed interface SystemFrameworkUIAction {
    data class ShowToast(val message: UiText, val long: Boolean = false) : SystemFrameworkUIAction
    data class UpdateFontScale(val newValue: String) : SystemFrameworkUIAction
    data class UpdateRotationSuggestions(val mode: RotationSuggestionsMode) : SystemFrameworkUIAction
    object OpenFontScaleSheet : SystemFrameworkUIAction
    object OpenRotationSuggestionsSheet : SystemFrameworkUIAction
    object OpenFontSetting : SystemFrameworkUIAction
}

@Composable
fun SystemFrameworkPage(
    viewModel: SystemFrameworkViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val appSettingsActions = LocalPreferenceActions.current
    val context = LocalContext.current

    val fontScaleSheetVisibility = remember { mutableStateOf(false) }
    val rotationSuggestionsSheetVisibility = remember { mutableStateOf(false) }
    val errorMsg = remember { mutableStateOf<UiText?>(null) }

    val isFontScaleOn = remember {
        mutableStateOf(appSettingsActions.get(Preferences.System.ENABLE_FONT_SCALE))
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            errorMsg.value = event
        }
    }

    val onAction: (SystemFrameworkUIAction) -> Unit = { action ->
        when (action) {
            is SystemFrameworkUIAction.ShowToast -> {
                context.showToast(action.message.asString(context), action.long)
            }
            is SystemFrameworkUIAction.UpdateFontScale -> {
                val newScale = action.newValue.toFloatOrNull()
                if (newScale != null && newScale in 0.5f..2.5f) {
                    viewModel.handleAction(UpdateFontScale(newScale))
                } else {
                    errorMsg.value = R.string.android_display_temp_font_scale_fail_msg.toUiText()
                }
            }
            is SystemFrameworkUIAction.UpdateRotationSuggestions -> {
                viewModel.handleAction(UpdateRotationSuggestions(action.mode))
            }
            SystemFrameworkUIAction.OpenFontScaleSheet -> {
                fontScaleSheetVisibility.value = true
            }
            SystemFrameworkUIAction.OpenRotationSuggestionsSheet -> {
                rotationSuggestionsSheetVisibility.value = true
            }
            SystemFrameworkUIAction.OpenFontSetting -> {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setClassName(
                            "com.android.settings",
                            $$"com.android.settings.Settings$PageLayoutActivity"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    SystemFrameworkPageContent(
        state = state,
        isFontScaleOn = isFontScaleOn.value,
        onAction = onAction
    )

    AlertDialog(
        visible = errorMsg.value != null,
        onDismissRequest = { errorMsg.value = null },
        title = stringResource(R.string.dialog_error),
        message = errorMsg.value?.asString(),
        mode = AlertDialogMode.Positive
    )

    FontScaleSheet(
        show = fontScaleSheetVisibility.value,
        onAction = onAction,
        onDismissRequest = {
            fontScaleSheetVisibility.value = false
            isFontScaleOn.value = appSettingsActions.get(Preferences.System.ENABLE_FONT_SCALE)
        }
    )

    RotationSuggestionsSheet(
        show = rotationSuggestionsSheetVisibility.value,
        onSelect = { mode ->
            onAction(SystemFrameworkUIAction.UpdateRotationSuggestions(mode))
            rotationSuggestionsSheetVisibility.value = false
        },
        onDismissRequest = { rotationSuggestionsSheetVisibility.value = false }
    )
}

@Composable
private fun SystemFrameworkPageContent(
    state: SystemFrameworkState,
    isFontScaleOn: Boolean,
    onAction: (SystemFrameworkUIAction) -> Unit
) {
    HyperXPage(
        title = stringResource(R.string.page_android),
        actions = {
            RebootActionItem(
                appName = stringResource(R.string.scope_android),
                appPkg = arrayOf(Scope.SYSTEM),
            )
        }
    ) {
        itemPreferenceGroup(
            titleRes = R.string.ui_title_android_display,
            position = ItemPosition.First
        ) {
            EditTextPreference(
                title = stringResource(R.string.android_display_temp_font_scale),
                summary = stringResource(R.string.android_display_temp_font_scale_tips),
                text = state.currentFontScale.toDecimalString(),
                dialogMessage = stringResource(R.string.android_display_temp_font_scale_msg),
                onTextChange = { onAction(SystemFrameworkUIAction.UpdateFontScale(it)) }
            )
            TextPreference(
                title = stringResource(R.string.android_display_font_scale),
                summary = stringResource(R.string.android_display_font_scale_tips),
                value = stringResource(if (isFontScaleOn) R.string.common_on else R.string.common_off),
                onClick = { onAction(SystemFrameworkUIAction.OpenFontScaleSheet) }
            )
            TextPreference(
                title = stringResource(R.string.android_display_rotation_suggestions),
                summary = stringResource(R.string.android_display_rotation_suggestions_tips),
                onClick = { onAction(SystemFrameworkUIAction.OpenRotationSuggestionsSheet) }
            )
        }

        itemPreferenceGroup(
            titleRes = R.string.ui_title_android_freeform,
            position = ItemPosition.Middle
        ) {
            SwitchPreference(
                key = Preferences.System.DISABLE_FREEFORM_RESTRICT,
                title = stringResource(R.string.android_freeform_restriction),
                summary = stringResource(R.string.android_freeform_restriction_tips),
            )
            SwitchPreference(
                key = Preferences.System.ALLOW_MORE_FREEFORM,
                title = stringResource(R.string.android_freeform_allow_more),
                summary = stringResource(R.string.android_freeform_allow_more_tips),
            )
        }

        itemPreferenceGroup(
            titleRes = R.string.ui_title_android_others,
            position = ItemPosition.Last
        ) {
            SwitchPreference(
                key = Preferences.System.DISABLE_FORCE_DARK_WHITELIST,
                title = stringResource(R.string.android_others_force_dark),
                summary = stringResource(R.string.android_others_force_dark_tips),
            )
            SwitchPreference(
                key = ParityPreferences.ALLOW_SCREENSHOT,
                title = "Allow screenshots in secure apps",
                summary = "Bypass FLAG_SECURE in the Android window manager."
            )
            SwitchPreference(
                key = ParityPreferences.DISABLE_DEVICE_NAME_CHECK,
                title = "Disable device-name sensitive-word check",
                summary = "Applies to device, hotspot and Bluetooth name validation in Settings."
            )
            SwitchPreference(
                key = ParityPreferences.ALLOW_THIRD_PARTY_THEME,
                title = "Allow third-party themes",
                summary = "Treat theme packages as legal only while the system ThemeReceiver validates them."
            )
            SwitchPreference(
                key = ParityPreferences.HIDE_BLUETOOTH_UNLOCK_TOAST,
                title = "Hide Bluetooth unlock toast",
                summary = "Suppress only the SystemUI toast shown after a Bluetooth-device unlock."
            )
            SwitchPreference(
                key = ParityPreferences.DISABLE_ROAMING_SIM_ACTIVATION,
                title = "Disable SIM activation while roaming",
                summary = "Skip Xiaomi activation receivers for a mainland-China SIM when it is roaming."
            )
            SwitchPreference(
                key = ParityPreferences.REDIRECT_XIAOMI_BROWSER,
                title = "Use default browser for Xiaomi links",
                summary = "Prevent HyperOS components from forcing web links into Xiaomi Browser or its Market download page."
            )
            SwitchPreference(
                key = ParityPreferences.FIX_HYPEROS_PASSKEY,
                title = "Fix HyperOS passkeys",
                summary = "Restore Credential Manager/passkey integration on CN HyperOS. Preinstalled Google Basic Services must be enabled."
            )
        }
    }
}
