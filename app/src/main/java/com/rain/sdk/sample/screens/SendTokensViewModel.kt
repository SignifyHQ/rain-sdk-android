package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SendTokensViewModel(
    private val rainClient: RainClient
) : ViewModel() {
    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    private val _state = MutableStateFlow(SendTokensUiState())
    val state: StateFlow<SendTokensUiState> = _state.asStateFlow()

    fun onRecipientChanged(value: String) {
        _state.update { it.copy(recipientAddress = value) }
    }

    fun onAmountChanged(value: String) {
        _state.update { it.copy(amount = value) }
    }

    fun onContractAddressChanged(value: String) {
        _state.update { it.copy(contractAddress = value) }
    }

    fun onSendModeChanged(isErc20: Boolean) {
        _state.update { it.copy(isErc20Mode = isErc20, txHash = null, errorText = null) }
    }

    fun sendNativeToken(chain: WalletChain = WalletChain.EVM) {
        val current = _state.value
        val amount = current.amount.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _state.update { it.copy(errorText = "Invalid amount") }
            return
        }
        if (current.recipientAddress.isBlank()) {
            _state.update { it.copy(errorText = "Recipient address is required") }
            return
        }
        if (!chain.isValidAddress(current.recipientAddress)) {
            _state.update { it.copy(errorText = "Recipient is not a valid ${chain.nativeSymbol} address") }
            return
        }

        SampleLog.i("Send.native", "to=${current.recipientAddress} amount=$amount ${chain.nativeSymbol}")
        _state.update { it.copy(isSending = true, errorText = null, txHash = null) }

        viewModelScope.launch {
            try {
                val result = rainClient.sendNativeToken(
                    chainId = chain.chainId,
                    toAddress = current.recipientAddress,
                    amount = amount
                )
                SampleLog.i("Send.native", "success txHash=${result.transactionHash}")
                _state.update {
                    it.copy(
                        isSending = false,
                        txHash = result.transactionHash
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Send.native", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isSending = false,
                        errorText = "Send failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun sendErc20Token(chain: WalletChain = WalletChain.EVM) {
        val current = _state.value
        val amount = current.amount.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _state.update { it.copy(errorText = "Invalid amount") }
            return
        }
        if (current.contractAddress.isBlank()) {
            _state.update { it.copy(errorText = "Contract address is required") }
            return
        }
        if (current.recipientAddress.isBlank()) {
            _state.update { it.copy(errorText = "Recipient address is required") }
            return
        }

        SampleLog.i(
            "Send.erc20",
            "contract=${current.contractAddress} to=${current.recipientAddress} amount=$amount (decimals resolved by SDK)"
        )
        _state.update { it.copy(isSending = true, errorText = null, txHash = null) }

        viewModelScope.launch {
            try {
                // Decimals are resolved by the SDK (token registry or on-chain decimals()),
                // so the caller no longer has to supply them.
                val result = rainClient.sendToken(
                    chainId = chain.chainId,
                    contractAddress = current.contractAddress,
                    toAddress = current.recipientAddress,
                    amount = amount
                )
                SampleLog.i("Send.erc20", "success txHash=${result.transactionHash}")
                _state.update {
                    it.copy(
                        isSending = false,
                        txHash = result.transactionHash
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Send.erc20", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isSending = false,
                        errorText = "Send failed: ${e.message}"
                    )
                }
            }
        }
    }
}

data class SendTokensUiState(
    val isErc20Mode: Boolean = false,
    val recipientAddress: String = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff",
    val amount: String = "0.001",
    val contractAddress: String = "0x5425890298aed601595a70AB815c96711a31Bc65",
    val isSending: Boolean = false,
    val txHash: String? = null,
    val errorText: String? = null
)

class SendTokensViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SendTokensViewModel::class.java)) {
            return SendTokensViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
