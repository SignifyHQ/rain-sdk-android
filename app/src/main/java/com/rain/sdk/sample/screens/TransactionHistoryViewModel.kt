package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TransactionHistoryViewModel(
  private val rainClient: RainClient
) : ViewModel() {

  private val _state = MutableStateFlow(TransactionHistoryUiState())
  val state: StateFlow<TransactionHistoryUiState> = _state.asStateFlow()

  fun fetchTransactions() {
    _state.update { it.copy(isLoading = true, errorText = null) }

    viewModelScope.launch {
      try {
        // Fetch wallet address to determine send/receive direction
        val address = try {
          rainClient.getAddress()
        } catch (e: Exception) {
          null
        }

        val result = rainClient.getTransactions(
          chainId = RainChain.AVALANCHE_TESTNET,
          limit = 20,
          order = RainTransactionOrder.DESC
        )
        _state.update {
          it.copy(
            transactions = result.transactions,
            walletAddress = address,
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

data class TransactionHistoryUiState(
  val transactions: List<RainTransaction> = emptyList(),
  val walletAddress: String? = null,
  val isLoading: Boolean = false,
  val errorText: String? = null
)

class TransactionHistoryViewModelFactory(
  private val rainClient: RainClient
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(TransactionHistoryViewModel::class.java)) {
      return TransactionHistoryViewModel(rainClient) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}
