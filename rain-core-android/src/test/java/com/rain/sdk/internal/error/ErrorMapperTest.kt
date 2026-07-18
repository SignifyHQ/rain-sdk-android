package com.rain.sdk.internal.error

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.helpers.assumeJdk24
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [ErrorMapper] — covers the non-Turnkey classification paths (signing
 * vs transaction, keyword-based user-reject / insufficient-funds detection, Portal-style
 * provider mapping).
 *
 * Gated on JDK 24+: `ErrorMapper` references `com.turnkey...TurnkeyKotlinError` in its
 * `is TurnkeyKotlinError` branch (class file version 68), and that reference is resolved
 * the moment a method body in `ErrorMapper` runs. The gate matches the pattern used by
 * [com.rain.sdk.internal.core.RainSdkManagerTurnkeyTest].
 */
class ErrorMapperTest {

    private val mapper = ErrorMapper()

    @Before
    fun requireJdk24() = assumeJdk24()

    // ---- mapSigningError -----------------------------------------------------------

    @Test
    fun `mapSigningError returns UserRejected when message contains user`() {
        val mapped = mapper.mapSigningError(RuntimeException("User cancelled passkey prompt"))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapSigningError returns UserRejected when message contains reject`() {
        val mapped = mapper.mapSigningError(RuntimeException("Request was rejected"))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapSigningError returns UserRejected when message contains cancel`() {
        val mapped = mapper.mapSigningError(RuntimeException("Operation cancelled"))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapSigningError wraps generic exception as ProviderError`() {
        val cause = IllegalStateException("boom")
        val mapped = mapper.mapSigningError(cause)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `mapSigningError wraps exception with null message as ProviderError`() {
        val mapped = mapper.mapSigningError(IOException())
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- mapTransactionError -------------------------------------------------------

    @Test
    fun `mapTransactionError returns InsufficientFunds when message contains insufficient`() {
        val mapped = mapper.mapTransactionError(RuntimeException("Insufficient funds for gas"))
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapTransactionError matches insufficient case-insensitively`() {
        val mapped = mapper.mapTransactionError(RuntimeException("INSUFFICIENT balance"))
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapTransactionError prefers user-rejection over insufficient-funds heuristic`() {
        // Order of checks in ErrorMapper.mapTransactionError: user rejection first,
        // then insufficient. This protects "user cancelled" UX paths.
        val mapped = mapper.mapTransactionError(
            RuntimeException("User cancelled the transaction")
        )
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapTransactionError wraps unknown error as ProviderError`() {
        val cause = IllegalArgumentException("RPC error -32603")
        val mapped = mapper.mapTransactionError(cause)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(cause)
    }

    // ---- mapPortalError ------------------------------------------------------------

    @Test
    fun `mapPortalError always wraps as ProviderError preserving cause`() {
        val cause = RuntimeException("Portal: SESSION_EXPIRED")
        val mapped = mapper.mapPortalError(cause)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(cause)
    }
}
