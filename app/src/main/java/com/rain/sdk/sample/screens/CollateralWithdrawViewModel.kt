package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.sample.NetworkClient
import io.portalhq.android.storage.mobile.PortalNamespace
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CollateralWithdrawViewModel(
    private val rainClient: RainClient
) : ViewModel() {
    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    private val _state = MutableStateFlow(CollateralWithdrawUiState())
    val state: StateFlow<CollateralWithdrawUiState> = _state.asStateFlow()

    fun loadContractInfo(accessToken: String) {
        _state.update { it.copy(isLoadingContract = true, errorText = null) }

        viewModelScope.launch {
            try {
                val walletAddress = rainClient.portal.getAddress(PortalNamespace.EIP155)
                    ?: throw Exception("Wallet address not found")

                val contractResponse = NetworkClient.fetchCollateralContract(accessToken)
                if (contractResponse.result.isFailure) {
                    _state.update {
                        it.copy(
                            isLoadingContract = false,
                            errorText = "Failed to fetch contract: ${contractResponse.result.exceptionOrNull()?.message}"
                        )
                    }
                    return@launch
                }

                val contract = contractResponse.result.getOrThrow()

                val tokens = contract.tokens.map { token ->
                    WithdrawTokenOption(
                        name = token.name ?: "Unknown",
                        symbol = token.symbol ?: "",
                        address = token.address,
                        decimals = token.decimals ?: 18,
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
                        availableTokens = tokens,
                        selectedTokenIndex = if (tokens.isNotEmpty()) 0 else -1,
                        isLoadingContract = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoadingContract = false,
                        errorText = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    fun onTokenSelected(index: Int) {
        _state.update { it.copy(selectedTokenIndex = index) }
    }

    fun onAmountChanged(value: String) {
        _state.update { it.copy(amount = value) }
    }

    fun onRecipientChanged(value: String) {
        _state.update { it.copy(recipientAddress = value) }
    }

    fun estimateGas(accessToken: String) {
        val current = _state.value
        val token = current.selectedToken ?: return
        val amount = current.amount.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _state.update { it.copy(errorText = "Enter a valid amount") }
            return
        }

        _state.update { it.copy(isEstimating = true, errorText = null, estimatedGas = null) }

        viewModelScope.launch {
            try {
                // 1. Fetch admin signature (needed for tx data)
                val amountNative = amount.movePointRight(token.decimals).longValueExact()
                val sigResponse = NetworkClient.fetchAdminSignature(
                    accessToken = accessToken,
                    chainId = current.chainId,
                    token = token.address.lowercase(),
                    amount = amountNative,
                    recipientAddress = current.walletAddress
                )

                if (sigResponse.result.isFailure) {
                    _state.update {
                        it.copy(
                            isEstimating = false,
                            errorText = "Failed to get signature: ${sigResponse.result.exceptionOrNull()?.message}"
                        )
                    }
                    return@launch
                }

                val (sigDetails, expiresAt) = sigResponse.result.getOrThrow()

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
                    chainId = RainChain.AVALANCHE_TESTNET,
                    addresses = addresses,
                    amount = amount,
                    decimals = token.decimals,
                    adminSignature = _state.value.adminSignature!!,
                    autoSend = false
                )

                val txData = withdrawResult.transactionData
                    ?: throw Exception("No transaction data returned")

                // 3. Estimate gas
                val fromAddress = rainClient.getAddress()
                val gas = rainClient.estimateGasDecimal(
                    chainId = RainChain.AVALANCHE_TESTNET,
                    from = fromAddress,
                    to = current.controllerAddress,
                    data = txData
                )

                _state.update {
                    it.copy(
                        estimatedGas = "$gas AVAX",
                        isEstimating = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isEstimating = false,
                        errorText = "Gas estimation failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun executeWithdraw(accessToken: String) {
        val current = _state.value
        val token = current.selectedToken ?: return
        val amount = current.amount.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _state.update { it.copy(errorText = "Enter a valid amount") }
            return
        }

        _state.update { it.copy(isWithdrawing = true, errorText = null, withdrawResult = null) }

        viewModelScope.launch {
            try {
                // Fetch fresh admin signature if not already present
                val adminSig = current.adminSignature ?: run {
                    val amountNative = amount.movePointRight(token.decimals).longValueExact()
                    val sigResponse = NetworkClient.fetchAdminSignature(
                        accessToken = accessToken,
                        chainId = current.chainId,
                        token = token.address.lowercase(),
                        amount = amountNative,
                        recipientAddress = current.walletAddress
                    )
                    if (sigResponse.result.isFailure) {
                        throw Exception("Failed to get signature: ${sigResponse.result.exceptionOrNull()?.message}")
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
                    chainId = RainChain.AVALANCHE_TESTNET,
                    addresses = addresses,
                    amount = amount,
                    decimals = token.decimals,
                    adminSignature = adminSig,
                    autoSend = true
                )

                _state.update {
                    it.copy(
                        isWithdrawing = false,
                        withdrawResult = result.transactionHash ?: "No tx hash returned",
                        adminSignature = null // Clear used signature
                    )
                }
            } catch (e: Exception) {
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
    val balance: BigDecimal
) {
    val displayName: String get() = if (symbol.isNotBlank()) "$name ($symbol)" else name
}

data class CollateralWithdrawUiState(
    val walletAddress: String = "",
    val recipientAddress: String = "",
    val proxyAddress: String = "",
    val controllerAddress: String = "",
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
