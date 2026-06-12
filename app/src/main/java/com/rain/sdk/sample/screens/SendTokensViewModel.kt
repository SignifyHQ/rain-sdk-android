package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainClient
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

    fun onDecimalsChanged(value: String) {
        _state.update { it.copy(decimals = value) }
    }

    fun onSendModeChanged(isErc20: Boolean) {
        _state.update { it.copy(isErc20Mode = isErc20, txHash = null, errorText = null) }
    }

    fun sendNativeToken() {
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

        _state.update { it.copy(isSending = true, errorText = null, txHash = null) }

        viewModelScope.launch {
            try {
                val result = rainClient.sendNativeToken(
                    chainId = RainChain.AVALANCHE_TESTNET,
                    toAddress = current.recipientAddress,
                    amount = amount
                )
                _state.update {
                    it.copy(
                        isSending = false,
                        txHash = result.transactionHash
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSending = false,
                        errorText = "Send failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun sendErc20Token() {
        val current = _state.value
        val amount = current.amount.toBigDecimalOrNull()
        val decimalsInt = current.decimals.toIntOrNull() ?: 6
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

        _state.update { it.copy(isSending = true, errorText = null, txHash = null) }

        viewModelScope.launch {
            try {
                val result = rainClient.sendToken(
                    chainId = RainChain.AVALANCHE_TESTNET,
                    contractAddress = current.contractAddress,
                    toAddress = current.recipientAddress,
                    amount = amount,
                    decimals = decimalsInt
                )
                _state.update {
                    it.copy(
                        isSending = false,
                        txHash = result.transactionHash
                    )
                }
            } catch (e: Exception) {
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
    val decimals: String = "6",
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
