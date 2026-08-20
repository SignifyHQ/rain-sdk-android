package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.Token
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BalancesViewModel(
    private val rainSdk: RainSdk,
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

                // Discover every non-zero token the wallet holds — ERC-20s on EVM, SPL tokens
                // on Solana (sourced from Turnkey's balance API). Each Balance already carries
                // the resolved symbol / name / decimals, so the user picks a token instead of
                // typing a contract or mint address plus decimals.
                val discoveredTokenBalances = rainClient.getTokenBalances(chain.chainId)
                    .mapNotNull { balance ->
                        (balance.token as? Token.Contract)?.let { contract ->
                            WalletTokenBalance(
                                address = contract.address,
                                symbol = balance.symbol,
                                name = balance.name,
                                decimals = balance.decimals,
                                balance = balance.decimalAmount
                            )
                        }
                    }
                    .filter { it.balance.signum() > 0 }

                SampleLog.i(
                    "Balances.fetch",
                    "success — discovered ${discoveredTokenBalances.size} ${chain.tokenStandard} token(s)"
                )
                _state.update {
                    it.copy(
                        nativeBalance = "${native.formatted} ${native.symbol ?: chain.nativeSymbol}",
                        walletTokenBalances = discoveredTokenBalances,
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

    fun fetchCollateralBalances(chain: WalletChain = WalletChain.EVM) {
        if (!rainClient.isInitialized) {
            SampleLog.w("Balances.collateral", "SDK not initialized")
            _state.update { it.copy(collateralError = "SDK not initialized") }
            return
        }
        if (!rainSdk.isRainApiConfigured) {
            SampleLog.w("Balances.collateral", "Rain API not configured")
            _state.update { it.copy(collateralError = "Rain Api-Key and User ID required") }
            return
        }

        SampleLog.i("Balances.collateral", "fetching collateral contract chain=${chain.displayName}")
        _state.update { it.copy(isCollateralLoading = true, collateralError = null) }

        viewModelScope.launch {
            try {
                // Rain provisions one collateral contract per chain family — pick the one
                // matching the active chain (Solana cluster exact, any EVM otherwise).
                val contract = rainSdk.fetchCollateralContracts()
                    .firstOrNull { chain.ownsCollateralContract(it.chainId) }
                if (contract == null) {
                    SampleLog.w("Balances.collateral", "no collateral contract for ${chain.displayName}")
                    _state.update {
                        it.copy(
                            isCollateralLoading = false,
                            collateralError = "No collateral contract on ${chain.displayName}"
                        )
                    }
                    return@launch
                }
                val tokens = contract.tokens

                val collateralAddress = contract.proxyAddress
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
                        balance = token.balanceAmount ?: BigDecimal.ZERO,
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
    val balance: BigDecimal,
    val exchangeRate: Double
) {
    val displayAddress: String
        get() = if (address.length > 12) "${address.take(6)}...${address.takeLast(4)}" else address

    val usdValue: BigDecimal
        get() = balance.multiply(exchangeRate.toBigDecimal())
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
    val balance: BigDecimal
) {
    val displayAddress: String
        get() = if (address.length > 12) "${address.take(6)}...${address.takeLast(4)}" else address

    /** "USD Coin (USDC)", or just the symbol/address when name/symbol are missing. */
    val displayName: String
        get() = when {
            !name.isNullOrBlank() && !symbol.isNullOrBlank() -> "$name ($symbol)"
            !symbol.isNullOrBlank() -> symbol
            !name.isNullOrBlank() -> name
            else -> "Unnamed token"
        }

    /**
     * The unit to print after an amount: the symbol when known, otherwise the truncated address.
     * Never blank, so a balance always says which token it is — an SPL mint has no on-chain
     * symbol, so an unregistered one is identified by its mint.
     */
    val displayUnit: String
        get() = symbol?.takeIf { it.isNotBlank() } ?: displayAddress

    /** The balance rendered exactly at the token's own scale, trailing zeros stripped. */
    val formattedBalance: String
        get() = balance.stripTrailingZeros().toPlainString()
}

class BalancesViewModelFactory(
    private val rainSdk: RainSdk,
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BalancesViewModel::class.java)) {
            return BalancesViewModel(rainSdk, rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
