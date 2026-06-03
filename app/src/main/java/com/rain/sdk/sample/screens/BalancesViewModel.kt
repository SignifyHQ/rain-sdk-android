package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.Token
import com.rain.sdk.sample.NetworkClient
import com.rain.sdk.sample.SampleLog
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
            SampleLog.w("Balances.fetch", "SDK not initialized")
            _state.update { it.copy(errorMessage = "SDK not initialized") }
            return
        }

        SampleLog.i("Balances.fetch", "fetching balances")
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                // The SDK resolves token decimals/symbol itself, so the rich Balance API
                // takes only a Token discriminator (no decimals argument).
                val native = rainClient.getBalance(RainChain.AVALANCHE_TESTNET, Token.Native)
                SampleLog.d("Balances.fetch", "native=${native.formatted} ${native.symbol}")
                val currentState = _state.value

                var erc20: String? = null
                if (currentState.tokenContractAddress.isNotBlank()) {
                    val erc20Balance = rainClient.getBalance(
                        chainId = RainChain.AVALANCHE_TESTNET,
                        token = Token.Contract(currentState.tokenContractAddress)
                    )
                    SampleLog.d(
                        "Balances.fetch",
                        "erc20 token=${currentState.tokenContractAddress} balance=${erc20Balance.formatted}"
                    )
                    erc20 = erc20Balance.formatted
                }

                SampleLog.i("Balances.fetch", "success")
                _state.update {
                    it.copy(
                        nativeBalance = "${native.formatted} ${native.symbol ?: "AVAX"}",
                        erc20Balance = erc20,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Balances.fetch", "failed: ${e.message}", e)
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
            SampleLog.w("Balances.collateral", "SDK not initialized")
            _state.update { it.copy(collateralError = "SDK not initialized") }
            return
        }
        val accessToken = _state.value.accessToken
        if (accessToken.isBlank()) {
            SampleLog.w("Balances.collateral", "access token blank")
            _state.update { it.copy(collateralError = "Access token not available") }
            return
        }

        SampleLog.i("Balances.collateral", "fetching collateral contract")
        _state.update { it.copy(isCollateralLoading = true, collateralError = null) }

        viewModelScope.launch {
            try {
                val contractResponse = NetworkClient.fetchCollateralContract(accessToken)
                if (contractResponse.result.isFailure) {
                    val err = contractResponse.result.exceptionOrNull()
                    SampleLog.e("Balances.collateral", "fetchCollateralContract failed: ${err?.message}", err)
                    _state.update {
                        it.copy(
                            isCollateralLoading = false,
                            collateralError = "Failed to fetch contract: ${err?.message}"
                        )
                    }
                    return@launch
                }

                val contract = contractResponse.result.getOrThrow()
                val tokens = contract.tokens

                val collateralAddress = contract.address
                SampleLog.i(
                    "Balances.collateral",
                    "success — address=$collateralAddress tokens=${tokens.size}"
                )

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
                        collateralWalletAddress = collateralAddress,
                        collateralBalances = collateralBalances,
                        isCollateralLoading = false
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Balances.collateral", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        collateralError = e.message ?: "Unknown error",
                        isCollateralLoading = false
                    )
                }
            }
        }
    }

    fun loadWalletAddresses() {
        if (rainClient.isInitialized) {
            viewModelScope.launch {
                try {
                    val address = rainClient.getAddress()
                    SampleLog.d("Balances.address", "wallet address=$address")
                    _state.update { it.copy(internalWalletAddress = address) }
                } catch (e: Exception) {
                    SampleLog.w("Balances.address", "getAddress failed: ${e.message}", e)
                }
            }
        }
    }
}

fun formatAddress(address: String): String {
    if (address.length <= 10) return address
    return "${address.take(6)}...${address.takeLast(4)}"
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
    val internalWalletAddress: String = "",
    val tokenContractAddress: String = "0x5425890298aed601595a70AB815c96711a31Bc65",
    val tokenDecimals: String = "6",
    val isLoading: Boolean = false,
    val nativeBalance: String? = null,
    val erc20Balance: String? = null,
    val errorMessage: String? = null,
    // Collateral balances section (from API)
    val collateralWalletAddress: String = "",
    val isCollateralLoading: Boolean = false,
    val collateralBalances: List<CollateralTokenBalance> = emptyList(),
    val collateralError: String? = null
)

class BalancesViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BalancesViewModel::class.java)) {
            return BalancesViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
