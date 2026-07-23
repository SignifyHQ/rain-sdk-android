package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import io.privy.auth.AuthenticationException
import io.privy.wallet.EmbeddedWalletException
import org.junit.Test

/**
 * Vendor-error classification. The Privy Kotlin SDK carries no structured reason codes, so
 * classification is by exception type + message keywords.
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
        for (message in listOf("User rejected the request", "Request denied", "Signing cancelled")) {
            val mapped = PrivyErrorMapping.mapOrNull(EmbeddedWalletException(message))
            assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
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

    // ---- fallthrough --------------------------------------------------------------

    @Test
    fun `non-Privy exceptions stay unmapped so they bubble raw`() {
        assertThat(PrivyErrorMapping.mapOrNull(IllegalStateException("user rejected"))).isNull()
        assertThat(PrivyErrorMapping.mapOrNull(RuntimeException("insufficient funds"))).isNull()
    }
}
