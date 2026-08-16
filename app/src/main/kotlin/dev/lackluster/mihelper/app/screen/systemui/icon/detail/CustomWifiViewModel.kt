package dev.lackluster.mihelper.app.screen.systemui.icon.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lackluster.mihelper.app.repository.WifiSignalRepository
import dev.lackluster.mihelper.app.utils.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CustomWifiViewModel(
    private val wifiSignalRepo: WifiSignalRepository,
) : ViewModel() {
    private val _screenState = MutableStateFlow(IconDetailPageState())
    val screenState = _screenState.asStateFlow()

    val configState = wifiSignalRepo.configState
    val pictures = wifiSignalRepo.pictures
    val segmentCount = wifiSignalRepo.segmentCount

    fun dismissErrorDialog() {
        _screenState.update { it.copy(errorDialogMessage = null) }
    }

    fun handleSvgFileUri(uri: Uri) {
        viewModelScope.launch {
            _screenState.update { it.copy(isLoading = true) }
            wifiSignalRepo.importSvgFromUri(uri)
                .onSuccess { _screenState.update { it.copy(isLoading = false, errorDialogMessage = null) } }
                .onFailure { error ->
                    _screenState.update {
                        it.copy(
                            isLoading = false,
                            errorDialogMessage = (error.message ?: "Invalid Wi-Fi SVG").toUiText(),
                        )
                    }
                }
        }
    }
}
