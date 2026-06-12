package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.Token
import com.rain.sdk.sample.NetworkClient
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CollateralWithdrawViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(CollateralWithdrawUiState())
    val state: StateFlow<CollateralWithdrawUiState> = _state.asStateFlow()

    fun loadContractInfo() {
        SampleLog.i("Withdraw.contract", "loading contract info")
        _state.update { it.copy(isLoadingContract = true, errorText = null) }

        viewModelScope.launch {
            try {
                val walletAddress = rainClient.getWalletAddress()
                SampleLog.d("Withdraw.contract", "wallet address=$walletAddress")

                val contractResponse = NetworkClient.fetchCollateralContract()
                if (contractResponse.result.isFailure) {
                    val err = contractResponse.result.exceptionOrNull()
                    SampleLog.e("Withdraw.contract", "fetchCollateralContract failed: ${err?.message}", err)
                    _state.update {
                        it.copy(
                            isLoadingContract = false,
                            errorText = "Failed to fetch contract: ${err?.message}"
                        )
                    }
                    return@launch
                }

                val contract = contractResponse.result.getOrThrow()
                SampleLog.i(
                    "Withdraw.contract",
                    "contract=${contract.address} tokens=${contract.tokens.size} chainId=${contract.chainId}"
                )

                // Rain's /contracts response omits token decimals/symbol, so resolve them
                // on-chain via the SDK (getBalance carries decimals + symbol). Falls back to 6
                // (these collateral tokens are stablecoins) if the read fails — e.g. the SDK
                // wasn't initialized with this contract's chain RPC.
                val chainIdInt = contract.chainId.toInt()
                val tokens = contract.tokens.map { token ->
                    val meta = runCatching {
                        rainClient.getBalance(chainIdInt, Token.Contract(token.address))
                    }.getOrNull()
                    WithdrawTokenOption(
                        name = meta?.name ?: token.name ?: "Token",
                        symbol = meta?.symbol ?: token.symbol ?: "",
                        address = token.address,
                        decimals = meta?.decimals ?: token.decimals ?: 6,
                        balance = token.balance
                    )
                }

                _state.update {
                    it.copy(
                        walletAddress = walletAddress,
                        recipientAddress = walletAddress,
                        proxyAddress = contract.address,
                        controllerAddress = contract.controllerAddress,
                        chainId = contract.chainId,
                        adminAddress = contract.adminAddresses.firstOrNull() ?: "",
                        availableTokens = tokens,
                        selectedTokenIndex = if (tokens.isNotEmpty()) 0 else -1,
                        isLoadingContract = false
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Withdraw.contract", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isLoadingContract = false,
                        errorText = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    // A cached admin signature is bound to a specific (token, amount, recipient). Any change to
    // those inputs invalidates it — clear the signature + stale estimate so the next withdraw
    // re-fetches a signature for the current selection instead of silently reusing the old one.
    private fun invalidateSignature(builder: CollateralWithdrawUiState.() -> CollateralWithdrawUiState) {
        _state.update { it.builder().copy(adminSignature = null, estimatedGas = null) }
    }

    fun onTokenSelected(index: Int) {
        invalidateSignature { copy(selectedTokenIndex = index, withdrawResult = null, errorText = null) }
    }

    fun onAmountChanged(value: String) {
        invalidateSignature { copy(amount = value) }
    }

    fun onRecipientChanged(value: String) {
        invalidateSignature { copy(recipientAddress = value) }
    }

    fun estimateGas() {
        val current = _state.value
        val token = current.selectedToken ?: return
        val amount = current.amount.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update { it.copy(errorText = "Enter a valid amount") }
            return
        }
        if (current.adminAddress.isBlank()) {
            _state.update { it.copy(errorText = "Contract has no admin address") }
            return
        }

        SampleLog.i(
            "Withdraw.estimate",
            "token=${token.symbol} amount=$amount decimals=${token.decimals} to=${current.recipientAddress}"
        )
        _state.update { it.copy(isEstimating = true, errorText = null, estimatedGas = null) }

        viewModelScope.launch {
            try {
                val amountNative = (amount * Math.pow(10.0, token.decimals.toDouble())).toLong()
                val sigResponse = NetworkClient.fetchAdminSignature(
                    chainId = current.chainId,
                    token = token.address.lowercase(),
                    amount = amountNative,
                    adminAddress = current.adminAddress,
                    recipientAddress = current.recipientAddress
                )

                if (sigResponse.result.isFailure) {
                    val err = sigResponse.result.exceptionOrNull()
                    SampleLog.e("Withdraw.estimate", "fetchAdminSignature failed: ${err?.message}", err)
                    _state.update {
                        it.copy(
                            isEstimating = false,
                            errorText = "Failed to get signature: ${err?.message}"
                        )
                    }
                    return@launch
                }

                val (sigDetails, expiresAt) = sigResponse.result.getOrThrow()
                SampleLog.d("Withdraw.estimate", "got admin signature expiresAt=$expiresAt")

                // Store signature for later use
                _state.update {
                    it.copy(
                        adminSignature = RainAdminSignature(
                            salt = sigDetails.salt,
                            signature = sigDetails.data,
                            expiresAt = expiresAt
                        )
                    )
                }

                // 2. Get withdraw tx data (autoSend=false)
                val addresses = RainWithdrawAddresses(
                    proxyAddress = current.proxyAddress,
                    controllerAddress = current.controllerAddress,
                    tokenAddress = token.address,
                    recipientAddress = current.recipientAddress
                )

                val withdrawResult = rainClient.withdrawCollateral(
                    chainId = current.chainId.toInt(),
                    addresses = addresses,
                    amount = amount,
                    decimals = token.decimals,
                    adminSignature = _state.value.adminSignature!!,
                    autoSend = false
                )

                val txData = withdrawResult.transactionData
                    ?: throw Exception("No transaction data returned")

                // 3. Estimate gas
                val fromAddress = rainClient.getWalletAddress()
                val gas = rainClient.estimateGas(
                    chainId = current.chainId.toInt(),
                    from = fromAddress,
                    to = current.controllerAddress,
                    data = txData
                )

                // Label the fee with the active chain's native gas token (ETH on Base Sepolia,
                // AVAX on Avalanche, …) and render in plain decimal instead of scientific notation.
                val nativeSymbol = WalletChain.entries
                    .firstOrNull { it.chainId == current.chainId.toInt() }?.nativeSymbol ?: "ETH"
                val gasStr = java.math.BigDecimal.valueOf(gas).toPlainString()

                SampleLog.i("Withdraw.estimate", "success — estimatedGas=$gasStr $nativeSymbol")
                _state.update {
                    it.copy(
                        estimatedGas = "$gasStr $nativeSymbol",
                        isEstimating = false
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Withdraw.estimate", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isEstimating = false,
                        errorText = "Gas estimation failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun executeWithdraw() {
        val current = _state.value
        val token = current.selectedToken ?: return
        val amount = current.amount.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update { it.copy(errorText = "Enter a valid amount") }
            return
        }
        if (current.adminAddress.isBlank()) {
            _state.update { it.copy(errorText = "Contract has no admin address") }
            return
        }

        SampleLog.i(
            "Withdraw.execute",
            "token=${token.symbol} amount=$amount to=${current.recipientAddress}"
        )
        _state.update { it.copy(isWithdrawing = true, errorText = null, withdrawResult = null) }

        viewModelScope.launch {
            try {
                val adminSig = current.adminSignature ?: run {
                    SampleLog.d("Withdraw.execute", "fetching fresh admin signature")
                    val amountNative = (amount * Math.pow(10.0, token.decimals.toDouble())).toLong()
                    val sigResponse = NetworkClient.fetchAdminSignature(
                        chainId = current.chainId,
                        token = token.address.lowercase(),
                        amount = amountNative,
                        adminAddress = current.adminAddress,
                        recipientAddress = current.recipientAddress
                    )
                    if (sigResponse.result.isFailure) {
                        val err = sigResponse.result.exceptionOrNull()
                        SampleLog.e("Withdraw.execute", "fetchAdminSignature failed: ${err?.message}", err)
                        throw Exception("Failed to get signature: ${err?.message}")
                    }
                    val (sigDetails, expiresAt) = sigResponse.result.getOrThrow()
                    RainAdminSignature(
                        salt = sigDetails.salt,
                        signature = sigDetails.data,
                        expiresAt = expiresAt
                    )
                }

                val addresses = RainWithdrawAddresses(
                    proxyAddress = current.proxyAddress,
                    controllerAddress = current.controllerAddress,
                    tokenAddress = token.address,
                    recipientAddress = current.recipientAddress
                )

                val result = rainClient.withdrawCollateral(
                    chainId = current.chainId.toInt(),
                    addresses = addresses,
                    amount = amount,
                    decimals = token.decimals,
                    adminSignature = adminSig,
                    autoSend = true
                )

                SampleLog.i("Withdraw.execute", "success — txHash=${result.transactionHash}")
                _state.update {
                    it.copy(
                        isWithdrawing = false,
                        withdrawResult = result.transactionHash ?: "No tx hash returned",
                        adminSignature = null
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Withdraw.execute", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isWithdrawing = false,
                        errorText = "Withdrawal failed: ${e.message}"
                    )
                }
            }
        }
    }
}

data class WithdrawTokenOption(
    val name: String,
    val symbol: String,
    val address: String,
    val decimals: Int,
    val balance: Double
) {
    val displayName: String get() = if (symbol.isNotBlank()) "$name ($symbol)" else name
}

data class CollateralWithdrawUiState(
    val walletAddress: String = "",
    val recipientAddress: String = "",
    val proxyAddress: String = "",
    val controllerAddress: String = "",
    val adminAddress: String = "",
    val chainId: Long = 0,
    val availableTokens: List<WithdrawTokenOption> = emptyList(),
    val selectedTokenIndex: Int = -1,
    val amount: String = "",
    val adminSignature: RainAdminSignature? = null,
    val isLoadingContract: Boolean = false,
    val isEstimating: Boolean = false,
    val isWithdrawing: Boolean = false,
    val estimatedGas: String? = null,
    val withdrawResult: String? = null,
    val errorText: String? = null
) {
    val selectedToken: WithdrawTokenOption?
        get() = availableTokens.getOrNull(selectedTokenIndex)
}

class CollateralWithdrawViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CollateralWithdrawViewModel::class.java)) {
            return CollateralWithdrawViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
