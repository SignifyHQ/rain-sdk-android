package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CollateralWithdrawViewModel(
    private val rainSdk: RainSdk,
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(CollateralWithdrawUiState())
    val state: StateFlow<CollateralWithdrawUiState> = _state.asStateFlow()

    fun loadContractInfo(chain: WalletChain = WalletChain.EVM) {
        SampleLog.i("Withdraw.contract", "loading contract info chain=${chain.displayName}")
        _state.update { it.copy(isLoadingContract = true, errorText = null) }

        viewModelScope.launch {
            try {
                val walletAddress = rainClient.getWalletAddress(chain.chainId)
                SampleLog.d("Withdraw.contract", "wallet address=$walletAddress")

                // Rain provisions one collateral contract per chain family — pick the one
                // matching the active chain (Solana cluster exact, any EVM otherwise).
                val contract = rainSdk.fetchCollateralContracts()
                    .firstOrNull { chain.ownsCollateralContract(it.chainId) }
                if (contract == null) {
                    SampleLog.w("Withdraw.contract", "no collateral contract for ${chain.displayName}")
                    // Clear the previous chain's tokens so a failed switch shows nothing stale.
                    _state.update {
                        it.copy(
                            isLoadingContract = false,
                            availableTokens = emptyList(),
                            selectedTokenIndex = -1,
                            errorText = "No collateral contract on ${chain.displayName}"
                        )
                    }
                    return@launch
                }
                SampleLog.i(
                    "Withdraw.contract",
                    "contract=${contract.proxyAddress} tokens=${contract.tokens.size} chainId=${contract.chainId}"
                )

                // The SDK enriches token name/symbol/decimals from its token store / on-chain
                // reads. Fall back to 6 decimals (these collateral tokens are stablecoins) when
                // enrichment couldn't resolve them — e.g. the SDK wasn't initialized with this
                // contract's chain RPC.
                val tokens = contract.tokens.map { token ->
                    WithdrawTokenOption(
                        name = token.name ?: "Token",
                        symbol = token.symbol ?: "",
                        address = token.address,
                        decimals = token.decimals ?: 6,
                        balance = token.balanceAmount ?: BigDecimal.ZERO
                    )
                }

                _state.update {
                    it.copy(
                        walletAddress = walletAddress,
                        recipientAddress = walletAddress,
                        proxyAddress = contract.proxyAddress,
                        controllerAddress = contract.controllerAddress,
                        chainId = contract.chainId,
                        isSolanaContract = contract.chainId in WalletChain.SOLANA_CHAIN_IDS,
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
                        availableTokens = emptyList(),
                        selectedTokenIndex = -1,
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
     * Builds the withdrawal without broadcasting it, and shows what came back. The EVM case is a
     * complete transaction (from/to/value/data); the Solana case is the serialized unsigned
     * transaction plus the blockhash it was simulated against.
     */
    fun prepareWithdrawal(amountOverride: BigDecimal? = null) {
        runWithdrawFlow(amountOverride, "Withdraw.prepare") { addresses, amountBd, token, adminSig ->
            val prepared = rainClient.prepareWithdrawal(
                chainId = _state.value.chainId,
                addresses = addresses,
                amount = amountBd,
                decimals = token.decimals,
                adminSignature = adminSig
            )

            val summary = when (prepared) {
                is RainPreparedWithdrawal.Evm -> prepared.parameters.let {
                    "EVM transaction\nto: ${it.to}\nvalue: ${it.value}\ndata: ${truncate(it.data)}"
                }
                is RainPreparedWithdrawal.Solana -> prepared.transfer.let {
                    "Solana transaction\nblockhash: ${it.recentBlockhash}\n" +
                        "creates recipient account: ${it.createsRecipientAccount}\n" +
                        "tx: ${truncate(it.transactionHex)}"
                }
            }
            // Nothing was broadcast, so the signature is still good — keep it cached.
            _state.update { it.copy(isWithdrawing = false, preparedWithdrawal = summary) }
        }
    }

    /**
     * Estimates the withdrawal fee without broadcasting. EVM only — the SDK throws on a Solana
     * chain id, which the UI surfaces as-is.
     */
    fun estimateFee(amountOverride: BigDecimal? = null) {
        runWithdrawFlow(amountOverride, "Withdraw.estimate") { addresses, amountBd, token, adminSig ->
            val fee = rainClient.estimateWithdrawalFee(
                chainId = _state.value.chainId,
                addresses = addresses,
                amount = amountBd,
                decimals = token.decimals,
                adminSignature = adminSig
            )
            _state.update {
                it.copy(
                    isWithdrawing = false,
                    estimatedFee = "${fee.stripTrailingZeros().toPlainString()} ${nativeSymbol()}"
                )
            }
        }
    }

    private fun truncate(value: String): String =
        if (value.length > 40) "${value.take(24)}…${value.takeLast(12)}" else value

    private fun nativeSymbol(): String =
        WalletChain.entries.firstOrNull { it.chainId == _state.value.chainId }?.nativeSymbol ?: ""

    /**
     * Executes a collateral withdrawal. Gas estimation is handled internally by the SDK as
     * part of sending, so it isn't surfaced to the caller.
     *
     * @param amountOverride when set (e.g. "Withdraw Maximum"), withdraws this amount instead
     *   of the value typed into the amount field.
     */
    fun executeWithdraw(amountOverride: BigDecimal? = null) {
        runWithdrawFlow(amountOverride, "Withdraw.execute") { addresses, amountBd, token, adminSig ->
            val txHash = rainClient.withdrawCollateral(
                chainId = _state.value.chainId,
                addresses = addresses,
                amount = amountBd,
                decimals = token.decimals,
                adminSignature = adminSig
            )

            SampleLog.i("Withdraw.execute", "success — txHash=$txHash")
            // The signature is consumed by a broadcast; clear it so the next withdraw refetches.
            _state.update {
                it.copy(
                    isWithdrawing = false,
                    withdrawResult = txHash,
                    adminSignature = null,
                    signatureKey = null
                )
            }
        }
    }

    /**
     * Shared prep for every withdrawal-shaped call: validate the amount, resolve (and cache) the
     * admin signature for these exact inputs, then hand the pieces to [action]. Each of the three
     * SDK entry points differs only in what it does with them.
     */
    private fun runWithdrawFlow(
        amountOverride: BigDecimal?,
        tag: String,
        action: suspend (
            addresses: RainWithdrawAddresses,
            amountBd: BigDecimal,
            token: WithdrawTokenOption,
            adminSig: RainAdminSignature
        ) -> Unit
    ) {
        val current = _state.value
        val token = current.selectedToken ?: return
        val rawAmount = amountOverride ?: current.amount.toBigDecimalOrNull()
        if (rawAmount == null || rawAmount.signum() <= 0) {
            _state.update { it.copy(errorText = "Enter a valid amount") }
            return
        }
        // Normalize to the token's precision (round DOWN) so the SDK's scale guard never trips
        // and the signed amount, base units, and on-chain tx all agree.
        val amountBd = rawAmount.setScale(token.decimals, RoundingMode.DOWN)
        if (amountBd.signum() <= 0) {
            _state.update { it.copy(errorText = "Amount is below the token's minimum unit") }
            return
        }
        // UI-side guard: never request more than the token's available balance. BigDecimal
        // end-to-end, so "max" compares exactly — no epsilon needed.
        if (amountBd > token.balance) {
            _state.update {
                it.copy(
                    errorText = "Amount exceeds available balance " +
                        "(${token.balanceDisplay} ${token.symbol})"
                )
            }
            return
        }
        if (current.adminAddress.isBlank()) {
            _state.update { it.copy(errorText = "Contract has no admin address") }
            return
        }

        SampleLog.i(tag, "token=${token.symbol} amount=${amountBd.toPlainString()} to=${current.recipientAddress}")
        _state.update {
            it.copy(
                isWithdrawing = true,
                errorText = null,
                withdrawResult = null,
                preparedWithdrawal = null,
                estimatedFee = null
            )
        }

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
                // Lowercasing normalizes EVM hex addresses only — base58 is case-sensitive.
                val signatureKey = SignatureKey(
                    tokenAddress = if (current.isSolanaContract) token.address else token.address.lowercase(),
                    amountBaseUnits = amountBaseUnits.toString(),
                    recipientAddress = if (current.isSolanaContract) {
                        current.recipientAddress
                    } else {
                        current.recipientAddress.lowercase()
                    }
                )

                val cached = current.adminSignature?.takeIf { current.signatureKey == signatureKey }
                val adminSig = cached ?: run {
                    SampleLog.d("Withdraw.execute", "fetching fresh admin signature")
                    try {
                        rainSdk.fetchAdminSignature(
                            chainId = current.chainId,
                            tokenAddress = signatureKey.tokenAddress,
                            amountBaseUnits = amountBaseUnits,
                            adminAddress = current.adminAddress,
                            recipientAddress = current.recipientAddress
                        )
                    } catch (e: RainError) {
                        SampleLog.e(tag, "fetchAdminSignature failed: ${e.message}", e)
                        throw Exception(friendlySignatureError(e))
                    }
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

                action(addresses, amountBd, token, adminSig)
            } catch (e: Exception) {
                SampleLog.e(tag, "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isWithdrawing = false,
                        errorText = "${e.message}"
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
    private fun friendlySignatureError(error: RainError): String {
        val message = error.message ?: "Failed to get signature"
        return when {
            error is RainError.SignatureNotReady ->
                "Withdrawal signature is not ready yet" +
                    (error.retryAfter?.let { " — retry in ${it}s" } ?: "")
            message.contains("active signature", ignoreCase = true) ->
                "A withdrawal signature is already active for this account. Wait for the previous " +
                    "withdrawal to settle (or its signature to expire) before requesting a new one."
            else -> "Failed to get signature: $message"
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

    /** Full-precision balance for display — plain notation, no trailing zeros. */
    val balanceDisplay: String get() = balance.stripTrailingZeros().toPlainString()
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
    val chainId: Int = 0,
    val isSolanaContract: Boolean = false,
    val availableTokens: List<WithdrawTokenOption> = emptyList(),
    val selectedTokenIndex: Int = -1,
    val amount: String = "",
    val adminSignature: RainAdminSignature? = null,
    val signatureKey: SignatureKey? = null,
    val isLoadingContract: Boolean = false,
    val isWithdrawing: Boolean = false,
    val withdrawResult: String? = null,
    /** Summary of the last `prepareWithdrawal` result — nothing was broadcast. */
    val preparedWithdrawal: String? = null,
    /** Fee from the last `estimateWithdrawalFee` call, in the chain's native token. */
    val estimatedFee: String? = null,
    val errorText: String? = null
) {
    val selectedToken: WithdrawTokenOption?
        get() = availableTokens.getOrNull(selectedTokenIndex)

    /** Parsed amount, or null if the field is blank/non-numeric. */
    private val parsedAmount: BigDecimal?
        get() = amount.toBigDecimalOrNull()

    /** True when the typed amount is positive and within the selected token's balance. */
    val isAmountValid: Boolean
        get() {
            val token = selectedToken ?: return false
            val value = parsedAmount ?: return false
            return value.signum() > 0 && value <= token.balance
        }

    /** True when the typed amount exceeds the selected token's available balance. */
    val isAmountOverBalance: Boolean
        get() {
            val token = selectedToken ?: return false
            val value = parsedAmount ?: return false
            return value > token.balance
        }
}

class CollateralWithdrawViewModelFactory(
    private val rainSdk: RainSdk,
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CollateralWithdrawViewModel::class.java)) {
            return CollateralWithdrawViewModel(rainSdk, rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
