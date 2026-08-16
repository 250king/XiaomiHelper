package dev.lackluster.mihelper.app.screen.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.lackluster.hyperx.ui.component.CardDefaults
import dev.lackluster.hyperx.ui.layout.HyperXSheet
import dev.lackluster.hyperx.ui.preference.TextPreference
import dev.lackluster.hyperx.ui.preference.itemPreferenceGroup
import dev.lackluster.mihelper.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RotationSuggestionsSheet(
    show: Boolean,
    onSelect: (RotationSuggestionsMode) -> Unit,
    onDismissRequest: () -> Unit
) {
    val cardColors = CardDefaults.cardColors(
        containerColor = MiuixTheme.colorScheme.secondaryContainer
    )

    HyperXSheet(
        show = show,
        title = stringResource(R.string.android_display_rotation_suggestions),
        onDismissRequest = onDismissRequest
    ) {
        itemPreferenceGroup(
            cardColors = cardColors
        ) {
            TextPreference(
                title = stringResource(R.string.android_display_rotation_suggestions_force_enabled),
                onClick = { onSelect(RotationSuggestionsMode.ForceEnabled) }
            )
            TextPreference(
                title = stringResource(R.string.android_display_rotation_suggestions_force_disabled),
                onClick = { onSelect(RotationSuggestionsMode.ForceDisabled) }
            )
            TextPreference(
                title = stringResource(R.string.android_display_rotation_suggestions_default),
                onClick = { onSelect(RotationSuggestionsMode.Default) }
            )
        }
    }
}
