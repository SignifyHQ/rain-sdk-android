package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.turnkey.MockTurnkeyClient.StatusFixture
import com.turnkey.types.V1SolanaFailureDetails
import org.junit.Test

/**
 * The FAILED-status classification both poll loops share: only decoded revert details make a failed
 * status a [RainError.TransactionSimulationFailed] (so a withdrawal reads `WithdrawalRevertedByNetwork`);
 * every other failure is a [RainError.ProviderError]; a status that has not failed yields null.
 */
class TurnkeySendFailuresTest {

    private fun classify(fixture: StatusFixture): RainError? =
        TurnkeySendFailures.sendFailure(fixture.toResponse(), fallback = "fallback message")

    @Test
    fun `a broadcasted status is not a failure`() {
        assertThat(classify(StatusFixture.broadcasted("0xaa"))).isNull()
    }

    @Test
    fun `a pending status is not a failure`() {
        assertThat(classify(StatusFixture.pending())).isNull()
    }

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
    fun `solana details with an rpc message are a TransactionSimulationFailed`() {
        assertThat(classify(StatusFixture.solanaRevertedOnChain()))
            .isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `solana details with logs are a TransactionSimulationFailed`() {
        val fixture = StatusFixture(
            txStatus = "TX_STATUS_FAILED",
            solanaFailure = V1SolanaFailureDetails(logs = listOf("Program log: custom program error: 0x1"))
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `solana details with a transaction error are a TransactionSimulationFailed`() {
        val fixture = StatusFixture(
            txStatus = "TX_STATUS_FAILED",
            solanaFailure = V1SolanaFailureDetails(transactionErrorJson = "{\"InstructionError\":[0,\"Custom\"]}")
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `solana details with none of the revert fields stay a ProviderError`() {
        val fixture = StatusFixture(txStatus = "TX_STATUS_FAILED", solanaFailure = V1SolanaFailureDetails(source = "preflight"))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a rejected status with no detail is a ProviderError carrying the fallback`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_REJECTED"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("fallback message")
    }
}
