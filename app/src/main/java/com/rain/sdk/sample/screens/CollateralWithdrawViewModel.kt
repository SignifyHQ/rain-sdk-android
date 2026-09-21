package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.sample.CollateralContract
import com.rain.sdk.sample.RainApiError
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.WithdrawalSignatureRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class CollateralWithdrawViewModel(
    private val session: RainSession,
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

                // From the demo's own Rain API client, exact chain first (see
                // WalletChain.collateralContract); a host makes this call from its backend.
                val contract = session.fetchCollateralContract(chain)
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

                val tokens = withdrawTokens(contract)

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
                        selectedTokenIndex = defaultWithdrawSelection(tokens),
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
        runWithdrawFlow(amountOverride, "Withdraw.prepare") { addresses, amountBd, decimals, adminSig ->
            val prepared = rainClient.prepareWithdrawal(
                chainId = _state.value.chainId,
                addresses = addresses,
                amount = amountBd,
                decimals = decimals,
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
        runWithdrawFlow(amountOverride, "Withdraw.estimate") { addresses, amountBd, decimals, adminSig ->
            val fee = rainClient.estimateWithdrawalFee(
                chainId = _state.value.chainId,
                addresses = addresses,
                amount = amountBd,
                decimals = decimals,
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
        runWithdrawFlow(amountOverride, "Withdraw.execute") { addresses, amountBd, decimals, adminSig ->
            val txHash = rainClient.withdrawCollateral(
                chainId = _state.value.chainId,
                addresses = addresses,
                amount = amountBd,
                decimals = decimals,
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
     * The screen's rows. Name, symbol and decimals come from RainSdk.tokenMetadata through
     * RainSession.fetchCollateralContract. A token whose decimals the SDK could not establish keeps
     * null here, and the screen disables its money actions rather than scaling by a guess; each such
     * token is logged once, so a greyed-out row can be explained from logcat.
     */
    private fun withdrawTokens(contract: CollateralContract): List<WithdrawTokenOption> {
        val tokens = contract.tokens.map { token ->
            WithdrawTokenOption(
                name = token.name ?: "Token",
                symbol = token.symbol ?: "",
                address = token.address,
                decimals = token.decimals,
                balance = token.balanceAmount ?: BigDecimal.ZERO
            )
        }
        tokens.filter { it.decimals == null }.forEach { token ->
            SampleLog.w("Withdraw.contract", "decimals unresolved for ${token.address}; withdrawal disabled")
        }
        return tokens
    }

    /**
     * Shared prep for every withdrawal-shaped call: refuse a token whose decimals are unknown,
     * validate the amount, resolve (and cache) the admin signature for these exact inputs, then hand
     * the pieces to [action]. Each of the three SDK entry points differs only in what it does with them.
     */
    private fun runWithdrawFlow(
        amountOverride: BigDecimal?,
        tag: String,
        action: suspend (
            addresses: RainWithdrawAddresses,
            amountBd: BigDecimal,
            decimals: Int,
            adminSig: RainAdminSignature
        ) -> Unit
    ) {
        val current = _state.value
        val token = current.selectedToken ?: return
        // Never scale by a guess: the screen disables these actions, and this guards the model too.
        val decimals = token.decimals
        if (decimals == null) {
            _state.update { it.copy(errorText = current.decimalsUnavailableText) }
            return
        }
        val rawAmount = amountOverride ?: current.amount.toBigDecimalOrNull()
        if (rawAmount == null || rawAmount.signum() <= 0) {
            _state.update { it.copy(errorText = "Enter a valid amount") }
            return
        }
        // Normalize to the token's precision (round DOWN) so the SDK's scale guard never trips
        // and the signed amount, base units, and on-chain tx all agree.
        val amountBd = rawAmount.setScale(decimals, RoundingMode.DOWN)
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
                    .multiply(BigDecimal.TEN.pow(decimals))
                    .toBigInteger()

                val adminSig = adminSignatureFor(current, token, amountBaseUnits, tag)

                val addresses = RainWithdrawAddresses(
                    proxyAddress = current.proxyAddress,
                    controllerAddress = current.controllerAddress,
                    tokenAddress = token.address,
                    recipientAddress = current.recipientAddress
                )

                action(addresses, amountBd, decimals, adminSig)
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
     * Rain's admin signature for exactly these inputs. The cached signature is reused only when it
     * was issued for the same (token, amount, recipient): "Withdraw maximum" sends a different amount
     * than a typed value, and the contract reverts on a mismatch. Otherwise a fresh one comes from the
     * demo's own Rain API client (a host fetches it from its backend) and is cached with the inputs it
     * is bound to, so a retry after a transient send failure reuses it instead of hitting
     * "active signature already exists".
     */
    private suspend fun adminSignatureFor(
        current: CollateralWithdrawUiState,
        token: WithdrawTokenOption,
        amountBaseUnits: BigInteger,
        tag: String,
    ): RainAdminSignature {
        // Lowercasing normalizes EVM hex addresses only; base58 is case-sensitive.
        val signatureKey = SignatureKey(
            chainId = current.chainId,
            tokenAddress = if (current.isSolanaContract) token.address else token.address.lowercase(),
            amountBaseUnits = amountBaseUnits.toString(),
            recipientAddress = if (current.isSolanaContract) current.recipientAddress else current.recipientAddress.lowercase(),
        )
        val cached = current.adminSignature?.takeIf { current.signatureKey == signatureKey }
        val adminSig = cached ?: run {
            SampleLog.d("Withdraw.execute", "fetching fresh admin signature")
            try {
                session.requireRainApi().fetchAdminSignature(
                    WithdrawalSignatureRequest(
                        chainId = current.chainId,
                        tokenAddress = signatureKey.tokenAddress,
                        amountBaseUnits = amountBaseUnits,
                        adminAddress = current.adminAddress,
                        recipientAddress = current.recipientAddress,
                    )
                )
            } catch (e: RainApiError) {
                SampleLog.e(tag, "fetchAdminSignature failed: ${e.message}", e)
                throw Exception(friendlySignatureError(e))
            }
        }
        _state.update { it.copy(adminSignature = adminSig, signatureKey = signatureKey) }
        return adminSig
    }

    /**
     * Maps the raw API/contract error to a clearer hint. The Rain API returns "active
     * signature already exists" when a previous withdrawal signature for this user is still
     * pending — re-using inputs reuses the cached signature, but a stale one server-side needs
     * to clear (or settle) first.
     */
    private fun friendlySignatureError(error: RainApiError): String = when {
        error is RainApiError.SignatureNotReady ->
            "Withdrawal signature is not ready yet" + error.retryAfter?.let { ", retry in ${it}s" }.orEmpty()
        error is RainApiError.Http && error.body?.contains("active signature", ignoreCase = true) == true ->
            "A withdrawal signature is already active for this account. Wait for the previous " +
                "withdrawal to settle (or its signature to expire) before requesting a new one."
        else -> "Failed to get signature: ${error.message}"
    }
}

/**
 * The row the screen opens on: the first token whose decimals the SDK established, or none. A token
 * without decimals stays listed with its money actions disabled, so opening on it would only show a
 * disabled form; opening on none is the honest answer when no token is withdrawable.
 */
internal fun defaultWithdrawSelection(tokens: List<WithdrawTokenOption>): Int =
    tokens.indexOfFirst { it.decimals != null }

data class WithdrawTokenOption(
    val name: String,
    val symbol: String,
    val address: String,
    /** Null when the SDK could not establish the decimals; every money action is disabled then. */
    val decimals: Int?,
    val balance: BigDecimal
) {
    val displayName: String get() = if (symbol.isNotBlank()) "$name ($symbol)" else name

    /** Full-precision balance for display, grouped, never fewer than two decimals. */
    val balanceDisplay: String get() = formatBalance(balance)
}

/**
 * Identifies the exact inputs an admin signature was issued for: chain, token, amount and
 * recipient. A cached signature is only reused when the next withdraw targets the same key, so a
 * signature minted for a typed amount is never reused by "Withdraw maximum" (or the reverse), and
 * one minted on one chain is never reused for the same token address on another; both would revert.
 */
data class SignatureKey(
    val chainId: Int,
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

    /** False while the selected token's decimals are unknown; every money action stays disabled then. */
    val selectedTokenDecimalsKnown: Boolean
        get() = selectedToken?.decimals != null

    /** Why the money actions are disabled for the selected token, or null when they are not. */
    val decimalsUnavailableText: String?
        get() = selectedToken?.takeIf { it.decimals == null }?.let { token ->
            "Decimals for ${token.symbol.ifBlank { token.address }} could not be resolved on " +
                "${WalletChain.chainLabel(chainId)}: the SDK needs an RPC endpoint for this chain and either the " +
                "token's on-chain metadata or a registered entry. Money actions are disabled for this token."
        }

    /** Parsed amount, or null if the field is blank/non-numeric. */
    private val parsedAmount: BigDecimal?
        get() = amount.toBigDecimalOrNull()

    /** True when the typed amount is positive and within the selected token's balance. */
    val isAmountValid: Boolean
        get() {
            val token = selectedToken ?: return false
            val value = parsedAmount ?: return false
            return token.decimals != null && value.signum() > 0 && value <= token.balance
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
    private val session: RainSession,
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CollateralWithdrawViewModel::class.java)) {
            return CollateralWithdrawViewModel(session, rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
