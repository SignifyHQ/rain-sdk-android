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
import java.math.BigDecimal
import java.math.RoundingMode
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

    // A cached admin signature is bound to a specific (token, amount, recipient) via
    // [CollateralWithdrawUiState.signatureKey]. Clearing it on input changes is belt-and-
    // suspenders — executeWithdraw also verifies the key matches before reusing — so a stale
    // signature is never sent for the wrong amount/recipient.
    private fun invalidateSignature(builder: CollateralWithdrawUiState.() -> CollateralWithdrawUiState) {
        _state.update { it.builder().copy(adminSignature = null, signatureKey = null) }
    }

    fun onTokenSelected(index: Int) {
        invalidateSignature { copy(selectedTokenIndex = index, withdrawResult = null, errorText = null) }
    }

    fun onAmountChanged(value: String) {
        invalidateSignature { copy(amount = value, errorText = null) }
    }

    fun onRecipientChanged(value: String) {
        invalidateSignature { copy(recipientAddress = value) }
    }

    /** Withdraw the full available balance of the selected token. */
    fun withdrawMaximum() {
        val current = _state.value
        val token = current.selectedToken ?: return
        executeWithdraw(amountOverride = token.balance)
    }

    /**
     * Executes a collateral withdrawal. Gas estimation is handled internally by the SDK as
     * part of sending (autoSend=true), so it isn't surfaced to the caller.
     *
     * @param amountOverride when set (e.g. "Withdraw Maximum"), withdraws this amount instead
     *   of the value typed into the amount field.
     */
    fun executeWithdraw(amountOverride: Double? = null) {
        val current = _state.value
        val token = current.selectedToken ?: return
        val rawAmount = amountOverride ?: current.amount.toDoubleOrNull()
        if (rawAmount == null || rawAmount <= 0) {
            _state.update { it.copy(errorText = "Enter a valid amount") }
            return
        }
        // Normalize to the token's precision (round DOWN) so:
        //  1. the SDK's scale guard (RainAmountUtils.toBaseUnits throws if scale > decimals)
        //     never trips on a long Double fraction, and
        //  2. the amount we sign for, the base units we send, and the on-chain tx all agree.
        // Rounding down also keeps "Withdraw Maximum" at or below the available balance.
        val amountBd = BigDecimal.valueOf(rawAmount).setScale(token.decimals, RoundingMode.DOWN)
        val amount = amountBd.toDouble()
        if (amount <= 0) {
            _state.update { it.copy(errorText = "Amount is below the token's minimum unit") }
            return
        }
        // UI-side guard: never request more than the token's available balance. A tiny epsilon
        // absorbs floating-point display rounding so an exact "max" amount isn't rejected.
        if (amount > token.balance + 1e-9) {
            _state.update {
                it.copy(
                    errorText = "Amount exceeds available balance " +
                        "(${"%.6f".format(token.balance)} ${token.symbol})"
                )
            }
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
                // Exact base-unit conversion from the SAME normalized BigDecimal the SDK will
                // use — no float overflow / precision loss, and it matches the signed amount.
                val amountBaseUnits = amountBd
                    .multiply(BigDecimal.TEN.pow(token.decimals))
                    .toBigInteger()

                // Only reuse the cached signature when it was issued for THIS exact
                // (token, amount, recipient). "Withdraw Maximum" uses a different amount than a
                // typed value, so a signature cached for the typed amount must NOT be reused —
                // the contract would revert on the mismatch. Re-using matching inputs avoids the
                // "active signature already exists" error on a legitimate retry.
                val signatureKey = SignatureKey(
                    tokenAddress = token.address.lowercase(),
                    amountBaseUnits = amountBaseUnits.toString(),
                    recipientAddress = current.recipientAddress.lowercase()
                )

                val cached = current.adminSignature?.takeIf { current.signatureKey == signatureKey }
                val adminSig = cached ?: run {
                    SampleLog.d("Withdraw.execute", "fetching fresh admin signature")
                    val sigResponse = NetworkClient.fetchAdminSignature(
                        chainId = current.chainId,
                        token = signatureKey.tokenAddress,
                        amount = amountBaseUnits,
                        adminAddress = current.adminAddress,
                        recipientAddress = current.recipientAddress
                    )
                    if (sigResponse.result.isFailure) {
                        val err = sigResponse.result.exceptionOrNull()
                        SampleLog.e("Withdraw.execute", "fetchAdminSignature failed: ${err?.message}", err)
                        throw Exception(friendlySignatureError(err?.message))
                    }
                    val (sigDetails, expiresAt) = sigResponse.result.getOrThrow()
                    RainAdminSignature(
                        salt = sigDetails.salt,
                        signature = sigDetails.data,
                        expiresAt = expiresAt
                    )
                }
                // Cache it (with the inputs it's bound to) so a retry after a transient send
                // failure reuses the same signature instead of triggering
                // "active signature already exists".
                _state.update { it.copy(adminSignature = adminSig, signatureKey = signatureKey) }

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
                        adminSignature = null,
                        signatureKey = null
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

    /**
     * Maps the raw API/contract error to a clearer hint. The Rain API returns "active
     * signature already exists" when a previous withdrawal signature for this user is still
     * pending — re-using inputs reuses the cached signature, but a stale one server-side needs
     * to clear (or settle) first.
     */
    private fun friendlySignatureError(raw: String?): String {
        val message = raw ?: "Failed to get signature"
        return if (message.contains("active signature", ignoreCase = true)) {
            "A withdrawal signature is already active for this account. Wait for the previous " +
                "withdrawal to settle (or its signature to expire) before requesting a new one."
        } else {
            "Failed to get signature: $message"
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

/**
 * Identifies the exact inputs an admin signature was issued for. A cached signature is only
 * reused when the next withdraw targets the same key — preventing a signature minted for a
 * typed amount from being reused by "Withdraw Maximum" (or vice-versa), which would revert.
 */
data class SignatureKey(
    val tokenAddress: String,
    val amountBaseUnits: String,
    val recipientAddress: String
)

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
    val signatureKey: SignatureKey? = null,
    val isLoadingContract: Boolean = false,
    val isWithdrawing: Boolean = false,
    val withdrawResult: String? = null,
    val errorText: String? = null
) {
    val selectedToken: WithdrawTokenOption?
        get() = availableTokens.getOrNull(selectedTokenIndex)

    /** Parsed amount, or null if the field is blank/non-numeric. */
    private val parsedAmount: Double?
        get() = amount.toDoubleOrNull()

    /** True when the typed amount is positive and within the selected token's balance. */
    val isAmountValid: Boolean
        get() {
            val token = selectedToken ?: return false
            val value = parsedAmount ?: return false
            return value > 0 && value <= token.balance + 1e-9
        }

    /** True when the typed amount exceeds the selected token's available balance. */
    val isAmountOverBalance: Boolean
        get() {
            val token = selectedToken ?: return false
            val value = parsedAmount ?: return false
            return value > token.balance + 1e-9
        }
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
