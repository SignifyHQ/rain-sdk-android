package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.SampleEnvironment
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Auth Pull prerequisite: approve Rain's operator to spend USDC from this wallet, and
 * inspect the allowance before and after.
 *
 * The pull itself is Rain's — once the allowance is in place, an authorization moves the funds
 * with no further app involvement.
 */
class AuthPullViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(AuthPullUiState())
    val state: StateFlow<AuthPullUiState> = _state.asStateFlow()

    private var seededChain: WalletChain? = null
    private var job: Job? = null

    fun onOperatorChanged(value: String) = _state.update { it.copy(operatorAddress = value) }
    fun onTokenAddressChanged(value: String) = _state.update { it.copy(tokenAddress = value) }
    fun onAmountChanged(value: String) = _state.update { it.copy(amount = value) }
    fun onUnlimitedChanged(value: Boolean) = _state.update { it.copy(isUnlimited = value) }

    /**
     * Seeds the form for [chain] and reads the current allowance. Token and operator are
     * environment-specific, so switching chains re-fills them. No-op when already seeded.
     */
    fun onChainChanged(chain: WalletChain) {
        if (chain == seededChain) return
        seededChain = chain
        job?.cancel()
        _state.update {
            it.copy(
                operatorAddress = chain.defaultAuthPullOperator,
                tokenAddress = chain.defaultTokenAddress,
                allowanceText = null,
                isUnlimitedAllowance = false,
                isRevokedAllowance = false,
                estimatedFee = null,
                txHash = null,
                errorText = null,
                isApproving = false,
                // The cancelled read rethrows before clearing this, so reset it here or the
                // spinner sticks.
                isLoadingAllowance = false
            )
        }
        if (chain.supportsAuthPull) refreshAllowance(chain)
    }

    /**
     * Reads `allowance(wallet, operator)` on chain. Cheap and free — call it on entry, and again
     * after an approval is mined.
     */
    fun refreshAllowance(chain: WalletChain) {
        val current = _state.value
        if (!validate(chain, current)) return

        _state.update { it.copy(isLoadingAllowance = true, errorText = null) }

        job = viewModelScope.launch {
            try {
                val allowance = rainClient.getTokenAllowance(
                    chainId = chain.chainId,
                    contractAddress = current.tokenAddress,
                    spender = current.operatorAddress
                )
                SampleLog.i(
                    "AuthPull.allowance",
                    "raw=${allowance.rawAmount} decimals=${allowance.decimals} " +
                        "unlimited=${allowance.isUnlimited}"
                )
                _state.update {
                    it.copy(
                        isLoadingAllowance = false,
                        isUnlimitedAllowance = allowance.isUnlimited,
                        isRevokedAllowance = allowance.isZero,
                        // An unlimited allowance formats to ~1.16e71 USDC, which is noise —
                        // label it instead.
                        allowanceText =
                            if (allowance.isUnlimited) "Unlimited" else allowance.formatted
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("AuthPull.allowance", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isLoadingAllowance = false,
                        allowanceText = null,
                        isUnlimitedAllowance = false,
                        isRevokedAllowance = false,
                        errorText = "Could not read allowance: ${e.message}"
                    )
                }
            }
        }
    }

    /** Prices the approval without broadcasting it or prompting for a signature. */
    fun estimateFee(chain: WalletChain) {
        val current = _state.value
        if (!validate(chain, current)) return
        val amount = when (val selection = resolvedAmount(current)) {
            AmountSelection.Invalid -> return
            AmountSelection.Unlimited -> null
            is AmountSelection.Exact -> selection.value
        }

        _state.update { it.copy(errorText = null, estimatedFee = null) }

        job = viewModelScope.launch {
            try {
                val fee = rainClient.estimateApprovalFee(
                    chainId = chain.chainId,
                    contractAddress = current.tokenAddress,
                    spender = current.operatorAddress,
                    amount = amount
                )
                // Plain notation: a fee is small enough that BigDecimal's toString would render it
                // as 2.15172E-7.
                val display = "${fee.stripTrailingZeros().toPlainString()} ${chain.nativeSymbol}"
                SampleLog.i("AuthPull.fee", "estimate=$display")
                _state.update { it.copy(estimatedFee = display) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("AuthPull.fee", "failed: ${e.message}", e)
                _state.update { it.copy(errorText = "Fee estimate failed: ${e.message}") }
            }
        }
    }

    /**
     * Submits the approval, then re-reads the allowance so the screen reflects what actually
     * landed rather than what was requested.
     */
    fun approve(chain: WalletChain) {
        val current = _state.value
        if (!validate(chain, current)) return
        val amount = when (val selection = resolvedAmount(current)) {
            AmountSelection.Invalid -> return
            AmountSelection.Unlimited -> null
            is AmountSelection.Exact -> selection.value
        }

        SampleLog.i(
            "AuthPull.approve",
            "token=${current.tokenAddress} spender=${current.operatorAddress} " +
                "amount=${amount?.toPlainString() ?: "unlimited"}"
        )
        _state.update { it.copy(isApproving = true, errorText = null, txHash = null) }

        job = viewModelScope.launch {
            try {
                val result = rainClient.approveTokenAllowance(
                    chainId = chain.chainId,
                    contractAddress = current.tokenAddress,
                    spender = current.operatorAddress,
                    amount = amount
                )
                SampleLog.i("AuthPull.approve", "success txHash=${result.transactionHash}")
                _state.update { it.copy(isApproving = false, txHash = result.transactionHash) }
                // The allowance only changes once the transaction is mined, so this read may
                // still show the old value on a slow chain — refresh again.
                refreshAllowance(chain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("AuthPull.approve", "failed: ${e.message}", e)
                _state.update {
                    it.copy(isApproving = false, errorText = "Approval failed: ${e.message}")
                }
            }
        }
    }

    /** Revokes by approving zero — the same call, with an explicit zero amount. */
    fun revoke(chain: WalletChain) {
        _state.update { it.copy(isUnlimited = false, amount = "0") }
        approve(chain)
    }

    // ---- helpers ----------------------------------------------------------------

    /**
     * What to approve. Kotlin has no nested nullability — `BigDecimal??` collapses to
     * `BigDecimal?` — so "unlimited" and "invalid input" need distinct cases rather than two
     * flavours of null.
     */
    private sealed interface AmountSelection {
        object Invalid : AmountSelection
        object Unlimited : AmountSelection
        data class Exact(val value: BigDecimal) : AmountSelection
    }

    private fun resolvedAmount(current: AuthPullUiState): AmountSelection {
        if (current.isUnlimited) return AmountSelection.Unlimited
        val parsed = runCatching { BigDecimal(current.amount) }.getOrNull()
        if (parsed == null || parsed.signum() < 0) {
            _state.update { it.copy(errorText = "Invalid amount") }
            return AmountSelection.Invalid
        }
        return AmountSelection.Exact(parsed)
    }

    private fun validate(chain: WalletChain, current: AuthPullUiState): Boolean {
        if (!chain.supportsAuthPull) {
            _state.update { it.copy(errorText = "Auth Pull is not available on ${chain.displayName}") }
            return false
        }
        if (!chain.isValidAddress(current.tokenAddress)) {
            _state.update {
                it.copy(errorText = "Token address is not a valid ${chain.displayName} address")
            }
            return false
        }
        if (!chain.isValidAddress(current.operatorAddress)) {
            _state.update {
                it.copy(errorText = "Operator address is not a valid ${chain.displayName} address")
            }
            return false
        }
        return true
    }
}

/** Form and result state for the Auth Pull screen. */
data class AuthPullUiState(
    val operatorAddress: String = SampleEnvironment.authPullOperator,
    val tokenAddress: String = WalletChain.firstAuthPullChain?.defaultTokenAddress ?: "",
    val amount: String = "250",
    val isUnlimited: Boolean = true,
    val allowanceText: String? = null,
    val isUnlimitedAllowance: Boolean = false,
    /** Nothing is approved: either never approved, or revoked. */
    val isRevokedAllowance: Boolean = false,
    val isLoadingAllowance: Boolean = false,
    val isApproving: Boolean = false,
    val estimatedFee: String? = null,
    val txHash: String? = null,
    val errorText: String? = null
)

class AuthPullViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthPullViewModel::class.java)) {
            return AuthPullViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
