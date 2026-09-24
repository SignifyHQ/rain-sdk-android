package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.turnkey.types.TGetSendTransactionStatusResponse
import com.turnkey.types.V1SolanaFailureDetails
import com.turnkey.types.V1TxError

/**
 * Classifies a send status. A status has failed when its `txStatus` says so, when it carries a
 * `txError`, or when it carries a structured `error` naming anything at all: the backend attaches
 * one only when the send failed, with or without a message, so an included transaction whose error
 * holds only Solana details is a failure even though the status also names its signature.
 *
 * A Solana fee or rent shortfall is [RainError.InsufficientFunds], checked first because the runtime
 * may log a failed program for it too. A status carrying decoded revert details is the contract or
 * program refusing the transaction, the fact a self-paid dry run would have caught before signing,
 * which a sponsored send skips: an EVM revert chain (top level or under `error.eth`), whether the
 * transaction failed before inclusion or was included and reverted on chain, or a Solana
 * `InstructionError` or the runtime's own `Program <id> failed` log line. It maps to
 * [RainError.TransactionSimulationFailed], carrying the hash or signature on `transactionId` when
 * the status names one, and the withdrawal path yields `WithdrawalRevertedByNetwork`. Any other
 * failure, a bare `txError` (the vendor's "error encountered when broadcasting or confirming the
 * transaction"), `error.eth` without a chain, or Solana details such as an expired blockhash, is a
 * [RainError.ProviderError]: it says the send failed, not that the chain executed and rejected it.
 * The message carries the vendor's text, capped like the Rain API client caps error bodies, and the
 * transaction id when there is one, so a host can look the transaction up.
 */
internal object TurnkeySendFailures {

    /** Vendor prose reaches hosts' logs through the cause; bound it as the Rain API client bounds error bodies. */
    private const val MAX_VENDOR_MESSAGE_LENGTH = 300

    /** The runtime's own failure line, `Program <base58 id> failed: ...`; a program's own log mentioning "failed" does not count. */
    private val PROGRAM_FAILED_LOG = Regex("^Program [1-9A-HJ-NP-Za-km-z]{32,44} failed")

    /** Null when [status] is not a failure; otherwise the error the poll loop throws. */
    fun sendFailure(status: TGetSendTransactionStatusResponse, fallback: String): RainError? {
        val normalized = status.txStatus.uppercase()
        val error = status.error
        val failed = normalized.contains("FAILED") ||
            normalized.contains("REJECTED") ||
            status.txError != null ||
            carriesDetail(error)
        if (!failed) return null
        val transactionId = status.eth?.txHash?.takeIf { it.isNotEmpty() }
            ?: status.solana?.signature?.takeIf { it.isNotEmpty() }
        val message = (status.txError ?: error?.message ?: fallback).take(MAX_VENDOR_MESSAGE_LENGTH) +
            transactionId?.let { " (transaction $it)" }.orEmpty()
        return when {
            isSolanaFundsShortfall(error?.solana) -> RainError.InsufficientFunds()
            carriesOnChainRevert(error) ->
                RainError.TransactionSimulationFailed(IllegalStateException(message), transactionId)
            else -> RainError.ProviderError(IllegalStateException(message))
        }
    }

    /** A structured error naming anything: the backend attaches one only when the send failed. */
    private fun carriesDetail(error: V1TxError?): Boolean =
        error != null &&
            (error.message != null || error.eth != null || error.solana != null || !error.revertChain.isNullOrEmpty())

    private fun carriesOnChainRevert(error: V1TxError?): Boolean {
        if (error == null) return false
        val evmRevert = !error.revertChain.isNullOrEmpty() || !error.eth?.revertChain.isNullOrEmpty()
        return evmRevert || isSolanaProgramFailure(error.solana)
    }

    /** A Solana program refused the transaction: an `InstructionError`, or the runtime's failed-program line. */
    private fun isSolanaProgramFailure(solana: V1SolanaFailureDetails?): Boolean {
        if (solana == null) return false
        val instructionError = solana.transactionErrorJson?.contains("InstructionError") == true
        val failedLog = solana.logs.orEmpty().any { PROGRAM_FAILED_LOG.containsMatchIn(it) }
        return instructionError || failedLog
    }

    /** The sender cannot pay the fee or the rent: a shortfall, not a program refusal. */
    private fun isSolanaFundsShortfall(solana: V1SolanaFailureDetails?): Boolean {
        val json = solana?.transactionErrorJson ?: return false
        return json.contains("InsufficientFundsForFee") || json.contains("InsufficientFundsForRent")
    }
}
