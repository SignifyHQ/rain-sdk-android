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
    fun `mapSigningError returns UserRejected when message contains reject`() {
        val mapped = mapper.mapSigningError(RuntimeException("User rejected the request"))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapSigningError returns UserRejected when message contains denied`() {
        val mapped = mapper.mapSigningError(RuntimeException("request denied"))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapSigningError returns UserRejected when message contains cancel`() {
        val mapped = mapper.mapSigningError(RuntimeException("Operation cancelled"))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapSigningError does not treat a bare user mention as rejection`() {
        // "user" alone is not a rejection keyword — this message is a wallet-availability
        // failure, not the user declining anything.
        val mapped = mapper.mapSigningError(RuntimeException("User doesn't have an embedded wallet"))
        assertThat(mapped).isNotInstanceOf(RainError.UserRejected::class.java)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapSigningError returns InsufficientFunds when message contains insufficient`() {
        val mapped = mapper.mapSigningError(RuntimeException("insufficient funds for transfer"))
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapSigningError prefers user-rejection over insufficient-funds heuristic`() {
        val mapped = mapper.mapSigningError(
            RuntimeException("User rejected: insufficient funds warning shown")
        )
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

    // ---- Turnkey HTTP status -------------------------------------------------------
    //
    // The Turnkey Kotlin SDK throws a plain RuntimeException carrying the status only in the
    // message, in two generated shapes. Both must classify to the same RainError.

    @Test
    fun `a 401 from the query path maps to TokenExpired`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 401")
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.TokenExpired::class.java)
        assertThat(mapper.mapSigningError(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 401 from the activity path maps to TokenExpired`() {
        val e = RuntimeException("HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\nError: {}\nCode: 401")
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 403 maps to Unauthorized`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 403")
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.Unauthorized::class.java)
    }

    @Test
    fun `a 401 whose body contains rejection keywords still maps to TokenExpired`() {
        // The status is the reliable signal; "session expired, request cancelled" is a session
        // problem, and hosts branch on TokenExpired to re-authenticate.
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: session expired, request cancelled\nCode: 401"
        )
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.TokenExpired::class.java)
        assertThat(mapper.mapSigningError(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 403 whose body says permission denied still maps to Unauthorized`() {
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: permission denied\nCode: 403"
        )
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.Unauthorized::class.java)
    }

    @Test
    fun `an unclassified HTTP status still falls through to the keyword checks`() {
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: user rejected the signing request\nCode: 400"
        )
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `other HTTP statuses stay ProviderError`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 500")
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a non-HTTP message ending in digits is not treated as a status`() {
        val e = RuntimeException("Something failed for wallet 401")
        assertThat(mapper.mapTransactionError(e)).isInstanceOf(RainError.ProviderError::class.java)
    }
}
