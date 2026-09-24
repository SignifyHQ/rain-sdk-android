package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.turnkey.MockTurnkeyClient.StatusFixture
import com.turnkey.types.V1RevertChainEntry
import com.turnkey.types.V1SolanaFailureDetails
import org.junit.Test

/**
 * The status classification both poll loops share. Decoded revert details, an EVM revert chain or a
 * Solana `InstructionError`, make a status a [RainError.TransactionSimulationFailed] (so a withdrawal
 * reads `WithdrawalRevertedByNetwork`), whether the transaction failed before inclusion or was included
 * and reverted; a Solana fee or rent shortfall is [RainError.InsufficientFunds]; every other failure is a
 * [RainError.ProviderError]; a status that has not failed yields null. A status that names the transaction
 * carries the hash or signature in the message.
 */
class TurnkeySendFailuresTest {

    private fun classify(fixture: StatusFixture): RainError? =
        TurnkeySendFailures.sendFailure(fixture.toResponse(), fallback = "fallback message")

    private fun solanaFailed(details: V1SolanaFailureDetails, message: String? = null) =
        StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = message, solanaFailure = details)

    // ---- not a failure ------------------------------------------------------------------------

    @Test
    fun `a broadcasted status is not a failure`() {
        assertThat(classify(StatusFixture.broadcasted("0xaa"))).isNull()
    }

    @Test
    fun `a pending status is not a failure`() {
        assertThat(classify(StatusFixture.pending())).isNull()
    }

    @Test
    fun `an included status without an error is not a failure`() {
        assertThat(classify(StatusFixture(txHash = "0xaa", txStatus = "TX_STATUS_INCLUDED"))).isNull()
    }

    // ---- EVM -----------------------------------------------------------------------------------

    @Test
    fun `a bare txError is a ProviderError carrying that text`() {
        val error = classify(StatusFixture.failed(message = "nonce too low"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("nonce too low")
    }

    @Test
    fun `a status whose only detail is error message is a ProviderError carrying that text`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = "rejected by policy"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("rejected by policy")
    }

    @Test
    fun `eth details without a revert chain stay a ProviderError`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = "failed", ethRevertChain = emptyList()))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `an empty top-level revert chain stays a ProviderError`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = "failed", revertChain = emptyList()))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a top-level revert chain is a TransactionSimulationFailed carrying the message`() {
        val error = classify(StatusFixture.revertedOnChain("execution reverted: cooldown"))

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("execution reverted: cooldown")
    }

    @Test
    fun `a revert chain under eth is a TransactionSimulationFailed`() {
        assertThat(classify(StatusFixture.ethRevertedOnChain()))
            .isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `revert details win over a bare txError and the txError text is the cause`() {
        val fixture = StatusFixture(
            txStatus = "TX_STATUS_FAILED",
            txError = "execution reverted",
            errorMessage = "decoded",
            revertChain = listOf(V1RevertChainEntry(displayMessage = "decoded", errorType = "native"))
        )

        val error = classify(fixture)

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("execution reverted")
    }

    @Test
    fun `an included transaction that reverted on chain is a TransactionSimulationFailed naming the hash`() {
        val hash = "0x" + "d".repeat(64)

        val error = classify(StatusFixture.includedButReverted(hash, message = "execution reverted: cooldown"))

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("execution reverted: cooldown (transaction $hash)")
    }

    @Test
    fun `a failed status that names a hash carries it in a ProviderError too`() {
        val error = classify(StatusFixture(txHash = "0xab", txStatus = "TX_STATUS_FAILED", txError = "nonce too low"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("nonce too low (transaction 0xab)")
    }

    // ---- Solana --------------------------------------------------------------------------------

    @Test
    fun `a solana program failure with an rpc message is a TransactionSimulationFailed`() {
        assertThat(classify(StatusFixture.solanaRevertedOnChain()))
            .isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `a solana InstructionError is a TransactionSimulationFailed`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "{\"InstructionError\":[0,\"Custom\"]}"))

        assertThat(classify(fixture)).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `solana logs saying a program failed are a TransactionSimulationFailed`() {
        val fixture = solanaFailed(
            V1SolanaFailureDetails(logs = listOf("Program 11111111111111111111111111111111 failed: custom program error: 0x1"))
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `solana logs without a failure line stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(logs = listOf("Program log: Instruction: Transfer")), message = "failed")

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `solana details with only an rpc message stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(rpcMessage = "Transaction simulation failed: Blockhash not found"))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `an expired solana blockhash stays a ProviderError`() {
        val fixture = solanaFailed(
            V1SolanaFailureDetails(rpcMessage = "Blockhash not found", transactionErrorJson = "\"BlockhashNotFound\"")
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `an already processed solana transaction stays a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "\"AlreadyProcessed\""))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a solana fee shortfall is an InsufficientFunds`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "\"InsufficientFundsForFee\""))

        assertThat(classify(fixture)).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `a solana rent shortfall is an InsufficientFunds`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "{\"InsufficientFundsForRent\":{\"account_index\":1}}"))

        assertThat(classify(fixture)).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `solana details with only an rpc code stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(rpcCode = -32002L))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `solana details with none of the revert fields stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(source = "preflight"))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a solana signature is carried in the message`() {
        val fixture = StatusFixture(
            solanaSignature = "5sig",
            txStatus = "TX_STATUS_FAILED",
            errorMessage = "failed",
            solanaFailure = V1SolanaFailureDetails(transactionErrorJson = "{\"InstructionError\":[0,\"Custom\"]}")
        )

        val error = classify(fixture)

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("failed (transaction 5sig)")
    }

    // ---- fallback ------------------------------------------------------------------------------

    @Test
    fun `a rejected status with no detail is a ProviderError carrying the fallback`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_REJECTED"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("fallback message")
    }
}
