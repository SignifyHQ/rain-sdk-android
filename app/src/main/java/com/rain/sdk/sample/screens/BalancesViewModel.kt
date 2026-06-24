package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.Token
import com.rain.sdk.sample.NetworkClient
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
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

    fun fetchBalances(chain: WalletChain = WalletChain.EVM) {
        if (!rainClient.isInitialized) {
            SampleLog.w("Balances.fetch", "SDK not initialized")
            _state.update { it.copy(errorMessage = "SDK not initialized") }
            return
        }

        SampleLog.i("Balances.fetch", "fetching balances chain=${chain.displayName}")
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                // The SDK resolves token decimals/symbol itself, so the rich Balance API
                // takes only a Token discriminator (no decimals argument).
                val native = rainClient.getBalance(chain.chainId, Token.Native)
                SampleLog.d("Balances.fetch", "native=${native.formatted} ${native.symbol}")

                // ERC-20 contract balances are an EVM concept; skip for Solana (SPL unsupported).
                var discoveredErc20Balances: List<WalletTokenBalance> = emptyList()
                if (!chain.isSolana) {
                    // Discover every non-zero ERC-20 the wallet holds. Each Balance already
                    // carries the resolved symbol / name / decimals (from the SDK's registry
                    // or an on-chain read), so the user picks a token instead of typing a
                    // contract address + decimals.
                    discoveredErc20Balances = rainClient.getTokenBalances(chain.chainId)
                        .mapNotNull { balance ->
                            (balance.token as? Token.Contract)?.let { contract ->
                                WalletTokenBalance(
                                    address = contract.address,
                                    symbol = balance.symbol,
                                    name = balance.name,
                                    decimals = balance.decimals,
                                    balance = balance.decimalAmount.toDouble()
                                )
                            }
                        }
                        .filter { it.balance > 0.0 }
                }

                SampleLog.i("Balances.fetch", "success — discovered ${discoveredErc20Balances.size} ERC-20(s)")
                _state.update {
                    it.copy(
                        nativeBalance = "${native.formatted} ${native.symbol ?: chain.nativeSymbol}",
                        walletTokenBalances = discoveredErc20Balances,
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
        if (!NetworkClient.isConfigured) {
            SampleLog.w("Balances.collateral", "NetworkClient not configured")
            _state.update { it.copy(collateralError = "Rain Api-Key and User ID required") }
            return
        }

        SampleLog.i("Balances.collateral", "fetching collateral contract")
        _state.update { it.copy(isCollateralLoading = true, collateralError = null) }

        viewModelScope.launch {
            try {
                val contractResponse = NetworkClient.fetchCollateralContract()
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

    fun loadWalletAddresses(chain: WalletChain = WalletChain.EVM) {
        if (rainClient.isInitialized) {
            viewModelScope.launch {
                try {
                    val address = rainClient.getWalletAddress(chain.chainId)
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
    // Manual query section
    val internalWalletAddress: String = "",
    val isLoading: Boolean = false,
    val nativeBalance: String? = null,
    val walletTokenBalances: List<WalletTokenBalance> = emptyList(),
    val errorMessage: String? = null,
    // Collateral balances section (from API)
    val collateralWalletAddress: String = "",
    val isCollateralLoading: Boolean = false,
    val collateralBalances: List<CollateralTokenBalance> = emptyList(),
    val collateralError: String? = null
)

data class WalletTokenBalance(
    val address: String,
    val symbol: String? = null,
    val name: String? = null,
    val decimals: Int = 18,
    val balance: Double
) {
    val displayAddress: String
        get() = if (address.length > 12) "${address.take(6)}...${address.takeLast(4)}" else address

    /** "USD Coin (USDC)", or just the symbol/address when name/symbol are missing. */
    val displayName: String
        get() = when {
            !name.isNullOrBlank() && !symbol.isNullOrBlank() -> "$name ($symbol)"
            !symbol.isNullOrBlank() -> symbol
            !name.isNullOrBlank() -> name
            else -> displayAddress
        }
}

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
