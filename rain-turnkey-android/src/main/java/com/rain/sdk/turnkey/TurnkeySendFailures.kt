package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.turnkey.types.TGetSendTransactionStatusResponse
import com.turnkey.types.V1SolanaFailureDetails
import com.turnkey.types.V1TxError

/**
 * Classifies a send status. A status carrying decoded revert details is the contract or program
 * refusing the transaction, the fact a self-paid dry run would have caught before signing, which a
 * sponsored send skips: an EVM revert chain (top level or under `error.eth`), whether the transaction
 * failed before inclusion or was included and reverted on chain, or a Solana `InstructionError` (or a
 * program log saying a program failed). It maps to [RainError.TransactionSimulationFailed] and the
 * withdrawal path yields `WithdrawalRevertedByNetwork`. A Solana fee or rent shortfall is
 * [RainError.InsufficientFunds]. Any other failure, a bare `txError` (the vendor's "error encountered
 * when broadcasting or confirming the transaction"), `error.eth` without a chain, or Solana details
 * such as an expired blockhash, is a [RainError.ProviderError]: it says the send failed, not that the
 * chain executed and rejected it. When the status names the transaction (an EVM hash or a Solana
 * signature) the message carries it, so a host can look the transaction up.
 */
internal object TurnkeySendFailures {

    /** Null when [status] is not a failure; otherwise the error the poll loop throws. */
    fun sendFailure(status: TGetSendTransactionStatusResponse, fallback: String): RainError? {
        val normalized = status.txStatus.uppercase()
        val error = status.error
        val reverted = carriesOnChainRevert(error)
        val failed = normalized.contains("FAILED") ||
            normalized.contains("REJECTED") ||
            status.txError != null ||
            error?.message != null ||
            reverted
        if (!failed) return null
        val transactionId = status.eth?.txHash?.takeIf { it.isNotEmpty() }
            ?: status.solana?.signature?.takeIf { it.isNotEmpty() }
        val message = (status.txError ?: error?.message ?: fallback) +
            transactionId?.let { " (transaction $it)" }.orEmpty()
        return when {
            reverted -> RainError.TransactionSimulationFailed(IllegalStateException(message))
            isSolanaFundsShortfall(error?.solana) -> RainError.InsufficientFunds()
            else -> RainError.ProviderError(IllegalStateException(message))
        }
    }

    private fun carriesOnChainRevert(error: V1TxError?): Boolean {
        if (error == null) return false
        val evmRevert = !error.revertChain.isNullOrEmpty() || !error.eth?.revertChain.isNullOrEmpty()
        return evmRevert || isSolanaProgramFailure(error.solana)
    }

    /** A Solana program refused the transaction: an `InstructionError`, or a log line saying a program failed. */
    private fun isSolanaProgramFailure(solana: V1SolanaFailureDetails?): Boolean {
        if (solana == null) return false
        val instructionError = solana.transactionErrorJson?.contains("InstructionError") == true
        val failedLog = solana.logs.orEmpty().any { it.contains("failed", ignoreCase = true) }
        return instructionError || failedLog
    }

    /** The sender cannot pay the fee or the rent: a shortfall, not a program refusal. */
    private fun isSolanaFundsShortfall(solana: V1SolanaFailureDetails?): Boolean {
        val json = solana?.transactionErrorJson ?: return false
        return json.contains("InsufficientFundsForFee") || json.contains("InsufficientFundsForRent")
    }
}
