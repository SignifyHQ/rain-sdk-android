package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.turnkey.types.TGetSendTransactionStatusResponse
import com.turnkey.types.V1TxError

/**
 * Classifies a terminal send status. A status carrying decoded revert details, an EVM revert chain
 * (top level or under `error.eth`) or Solana failure details, is the contract or program refusing
 * the transaction: the fact a self-paid dry run would have caught before signing, which a sponsored
 * send skips, so it maps to [RainError.TransactionSimulationFailed] and the withdrawal path yields
 * `WithdrawalRevertedByNetwork`. Any other failure, a bare `txError` (the vendor's "error encountered
 * when broadcasting or confirming the transaction") or `error.eth` without a chain, is a
 * [RainError.ProviderError]: it says the send failed, not that the chain executed and rejected it.
 */
internal object TurnkeySendFailures {

    /** Null when [status] is not a failure; otherwise the error the poll loop throws. */
    fun sendFailure(status: TGetSendTransactionStatusResponse, fallback: String): RainError? {
        val normalized = status.txStatus.uppercase()
        val failed = normalized.contains("FAILED") ||
            normalized.contains("REJECTED") ||
            status.txError != null ||
            status.error?.message != null
        if (!failed) return null
        val message = status.txError ?: status.error?.message ?: fallback
        return if (carriesOnChainRevert(status.error)) {
            RainError.TransactionSimulationFailed(IllegalStateException(message))
        } else {
            RainError.ProviderError(IllegalStateException(message))
        }
    }

    private fun carriesOnChainRevert(error: V1TxError?): Boolean {
        if (error == null) return false
        val evmRevert = !error.revertChain.isNullOrEmpty() || !error.eth?.revertChain.isNullOrEmpty()
        val solana = error.solana
        val solanaRevert = solana != null &&
            (solana.transactionErrorJson != null || solana.rpcMessage != null || !solana.logs.isNullOrEmpty())
        return evmRevert || solanaRevert
    }
}
