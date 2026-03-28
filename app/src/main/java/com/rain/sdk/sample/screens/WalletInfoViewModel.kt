package com.rain.sdk.sample.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.NetworkClient
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalletInfoViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(WalletInfoUiState())
    val state: StateFlow<WalletInfoUiState> = _state.asStateFlow()

    fun fetchWalletInfo(accessToken: String) {
        _state.update { it.copy(isLoading = true, errorText = null) }

        viewModelScope.launch {
            try {
                // 1. Fetch Portal Address (user's wallet)
                val portalAddress = rainClient.portal.getAddress(PortalNamespace.EIP155)
                    ?: throw Exception("Portal address not found")
                val portalQr = rainClient.generateAddressQRCode(portalAddress)

                _state.update {
                    it.copy(
                        portalAddress = portalAddress,
                        portalQrBitmap = portalQr
                    )
                }

                // 2. Fetch Collateral Contract (deposit address)
                val contractResponse = NetworkClient.fetchCollateralContract(accessToken)
                if (contractResponse.result.isFailure) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorText = "Failed to fetch collateral: ${contractResponse.result.exceptionOrNull()?.message}"
                        )
                    }
                    return@launch
                }

                val contract = contractResponse.result.getOrThrow()
                val collateralQr = rainClient.generateAddressQRCode(contract.address)

                _state.update {
                    it.copy(
                        collateralAddress = contract.address,
                        collateralQrBitmap = collateralQr,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorText = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }
}

data class WalletInfoUiState(
    val portalAddress: String = "",
    val portalQrBitmap: Bitmap? = null,
    val collateralAddress: String = "",
    val collateralQrBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val errorText: String? = null
) {
    fun isAddressValid(address: String): Boolean {
        if (address.isBlank()) return false
        return address.startsWith("0x")
                && address.length == 42
                && address.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

class WalletInfoViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WalletInfoViewModel::class.java)) {
            return WalletInfoViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
