package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BalancesViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(BalancesUiState())
    val state: StateFlow<BalancesUiState> = _state.asStateFlow()

    fun setAccessToken(token: String) {
        _state.update { it.copy(accessToken = token) }
    }

    fun onTokenContractAddressChanged(value: String) {
        _state.update { it.copy(tokenContractAddress = value) }
    }

    fun onTokenDecimalsChanged(value: String) {
        _state.update { it.copy(tokenDecimals = value) }
    }

    fun fetchBalances() {
        if (!rainClient.isInitialized) {
            _state.update { it.copy(errorMessage = "SDK not initialized") }
            return
        }

        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val native = rainClient.getNativeBalance(RainChain.AVALANCHE_TESTNET)
                val currentState = _state.value

                var erc20: String? = null
                if (currentState.tokenContractAddress.isNotBlank()) {
                    val decimals = currentState.tokenDecimals.toIntOrNull() ?: 18
                    val erc20Balance = rainClient.getERC20Balance(
                        chainId = RainChain.AVALANCHE_TESTNET,
                        tokenAddress = currentState.tokenContractAddress,
                        decimals = decimals
                    )
                    erc20 = "$erc20Balance"
                }

                _state.update {
                    it.copy(
                        nativeBalance = "$native AVAX",
                        erc20Balance = erc20,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = e.message ?: "Unknown error",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun fetchCollateralBalances() {
        if (!rainClient.isInitialized) {
            _state.update { it.copy(collateralError = "SDK not initialized") }
            return
        }
        val accessToken = _state.value.accessToken
        if (accessToken.isBlank()) {
            _state.update { it.copy(collateralError = "Access token not available") }
            return
        }

        _state.update { it.copy(isCollateralLoading = true, collateralError = null) }

        viewModelScope.launch {
            try {
                val contractResponse = NetworkClient.fetchCollateralContract(accessToken)
                if (contractResponse.result.isFailure) {
                    _state.update {
                        it.copy(
                            isCollateralLoading = false,
                            collateralError = "Failed to fetch contract: ${contractResponse.result.exceptionOrNull()?.message}"
                        )
                    }
                    return@launch
                }

                val contract = contractResponse.result.getOrThrow()
                val tokens = contract.tokens

                // Collateral balances come from the API, not on-chain.
                // Tokens are deposited into the smart contract, so the user's
                // wallet won't hold them — same as root app's CollateralContract.cryptoAssets.
                val collateralBalances = tokens.map { token ->
                    CollateralTokenBalance(
                        symbol = token.symbol ?: token.name ?: "Unknown",
                        name = token.name ?: "",
                        address = token.address,
                        decimals = token.decimals ?: 18,
                        balance = token.balance,
                        exchangeRate = token.exchangeRate
                    )
                }

                _state.update {
                    it.copy(
                        collateralBalances = collateralBalances,
                        isCollateralLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        collateralError = e.message ?: "Unknown error",
                        isCollateralLoading = false
                    )
                }
            }
        }
    }
}

data class CollateralTokenBalance(
    val symbol: String,
    val name: String,
    val address: String,
    val decimals: Int,
    val balance: Double,
    val exchangeRate: Double
) {
    val displayAddress: String
        get() = if (address.length > 12) "${address.take(6)}...${address.takeLast(4)}" else address

    val usdValue: Double
        get() = balance * exchangeRate
}

data class BalancesUiState(
    val accessToken: String = "",
    // Manual query section
    val tokenContractAddress: String = "0x5425890298aed601595a70AB815c96711a31Bc65",
    val tokenDecimals: String = "6",
    val isLoading: Boolean = false,
    val nativeBalance: String? = null,
    val erc20Balance: String? = null,
    val errorMessage: String? = null,
    // Collateral balances section (from API)
    val isCollateralLoading: Boolean = false,
    val collateralBalances: List<CollateralTokenBalance> = emptyList(),
    val collateralError: String? = null
)

class BalancesViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BalancesViewModel::class.java)) {
            return BalancesViewModel(RainSdk.getInstance().client) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
