package com.rain.sdk.sample.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.NetworkClient
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
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

    fun fetchWalletInfo(accessToken: String, chain: WalletChain = WalletChain.EVM) {
        SampleLog.i("WalletInfo", "fetching wallet info chain=${chain.displayName}")
        _state.update {
            it.copy(
                isLoading = true,
                errorText = null,
                // Clear stale data when switching chains so the previous wallet doesn't linger.
                portalAddress = "",
                portalQrBitmap = null,
                collateralAddress = "",
                collateralQrBitmap = null
            )
        }

        viewModelScope.launch {
            try {
                val walletAddress = rainClient.getAddress(chain.chainId)
                SampleLog.d("WalletInfo", "wallet address=$walletAddress")
                val walletQr = rainClient.generateAddressQRCode(walletAddress)

                _state.update {
                    it.copy(
                        portalAddress = walletAddress,
                        portalQrBitmap = walletQr
                    )
                }

                // Rain collateral is an EVM-only concept; skip it for Solana.
                if (chain.isSolana) {
                    _state.update { it.copy(isLoading = false) }
                    return@launch
                }

                val contractResponse = NetworkClient.fetchCollateralContract(accessToken)
                if (contractResponse.result.isFailure) {
                    val err = contractResponse.result.exceptionOrNull()
                    SampleLog.e("WalletInfo", "fetchCollateralContract failed: ${err?.message}", err)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorText = "Failed to fetch collateral: ${err?.message}"
                        )
                    }
                    return@launch
                }

                val contract = contractResponse.result.getOrThrow()
                SampleLog.d("WalletInfo", "collateral address=${contract.address}")
                val collateralQr = rainClient.generateAddressQRCode(contract.address)

                SampleLog.i("WalletInfo", "success")
                _state.update {
                    it.copy(
                        collateralAddress = contract.address,
                        collateralQrBitmap = collateralQr,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("WalletInfo", "failed: ${e.message}", e)
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
