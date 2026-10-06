package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.privy.auth.AuthenticationException
import io.privy.network.PrivyApiException
import io.privy.wallet.EmbeddedWalletException
import org.junit.Test

/**
 * Vendor-error classification. The Privy Kotlin SDK carries no structured reason codes, so
 * classification is by exception type + message, with rejection and funds-shortfall prose held
 * to core's two-word standard: a lone "denied" or "cancelled" does not classify.
 */
class PrivyErrorMappingTest {

    // ---- authentication ------------------------------------------------------------

    @Test
    fun `not-logged-in authentication failure maps to TokenExpired`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("User must be authenticated before calling refresh.")
        )
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `invalid JWT authentication failure maps to TokenExpired`() {
        val mapped = PrivyErrorMapping.mapOrNull(AuthenticationException("Invalid JWT provided"))
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `expired session that also mentions cancellation maps to TokenExpired, not UserRejected`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("session expired, request cancelled")
        )
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `cancelled authentication maps to UserRejected`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("Passkey prompt cancelled by user")
        )
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `other authentication failures map to ProviderError`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("App URL scheme must be provided")
        )
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- embedded wallet -------------------------------------------------------------

    @Test
    fun `user rejection during a wallet request maps to UserRejected`() {
        for (message in listOf(
            "User rejected the request",
            "Request denied by user",
            "Signing cancelled by the user",
            "MetaMask Tx Signature: User denied transaction signature.",
            "RPC Error: code 4001"
        )) {
            val mapped = PrivyErrorMapping.mapOrNull(EmbeddedWalletException(message))
            assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
        }
    }

    @Test
    fun `a single rejection word during a wallet request is not a UserRejected`() {
        // Same standard as core: "Transaction cancelled" is a chain outcome, not the user
        // declining a prompt.
        for (message in listOf("Request denied", "Signing cancelled", "Rejected: nonce too low")) {
            val mapped = PrivyErrorMapping.mapOrNull(EmbeddedWalletException(message))
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        }
    }

    @Test
    fun `insufficient-funds wallet failure maps to InsufficientFunds`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("RPC error: insufficient funds for gas * price + value")
        )
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `missing wallet maps to WalletUnavailable`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("User doesn't have an embedded wallet.")
        )
        assertThat(mapped).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `wallet creation failure maps to WalletUnavailable`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("Failed to create embedded wallet.")
        )
        assertThat(mapped).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `other wallet failures map to ProviderError`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("RPC response missing result")
        )
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- API ----------------------------------------------------------------------

    @Test
    fun `a 400 from the wallet RPC that names a funds shortfall maps to InsufficientFunds`() {
        // Privy's answer to 0.001 SOL from a wallet holding 0 SOL on 2026-10-02 (beta QA PV-SEND-01).
        val mapped = PrivyErrorMapping.mapOrNull(
            apiException(
                400,
                "Error broadcasting transaction with message: Error: Transaction simulation failed: " +
                    "Attempt to debit an account but found no record of a prior credit."
            )
        )
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `a 400 with any other message maps to ProviderError, a relayed rejection marker included`() {
        // A server wallet never prompts, so "code 4001" in relayed node text is not the user.
        for (message in listOf("Invalid transaction encoding", "RPC Error: code 4001", "Request denied by user")) {
            val failure = apiException(400, message)
            val mapped = PrivyErrorMapping.mapOrNull(failure)
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
            assertThat(mapped!!.cause).isSameInstanceAs(failure)
        }
    }

    @Test
    fun `a failure with no HTTP status, and a 5xx, map to ProviderError with the cause kept`() {
        // privy-core 0.15.0 reports a request that got no answer, and a 5xx, as a status-less
        // PrivyApiException with the client's exception as the cause.
        for (failure in listOf(
            PrivyApiException(null, null, "Something went wrong", IllegalStateException("no answer")),
            PrivyApiException(null, null, "Something went wrong", IllegalStateException("503 Service Unavailable")),
            apiException(502, "Bad Gateway"),
        )) {
            val mapped = PrivyErrorMapping.mapOrNull(failure)
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
            assertThat(mapped!!.cause).isSameInstanceAs(failure)
        }
    }

    @Test
    fun `a 401 maps to TokenExpired whatever its message says`() {
        val mapped = PrivyErrorMapping.mapOrNull(apiException(401, "insufficient funds"))
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    private fun apiException(status: Int, message: String) =
        PrivyApiException(status, null, message, RuntimeException(message))

    // ---- fallthrough --------------------------------------------------------------

    @Test
    fun `non-Privy exceptions stay unmapped so they bubble raw`() {
        assertThat(PrivyErrorMapping.mapOrNull(IllegalStateException("user rejected"))).isNull()
        assertThat(PrivyErrorMapping.mapOrNull(RuntimeException("insufficient funds"))).isNull()
    }
}
