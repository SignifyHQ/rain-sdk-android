package com.rain.sdk.internal.error

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.helpers.assumeJdk24
import org.junit.Before
import org.junit.Test

/**
 * Turnkey-error classification tests for [ErrorMapper] — covers each `TurnkeyKotlinError`
 * variant the mapper routes (InvalidSession, InvalidParameter, ClientNotInitialized,
 * FailedToSignRawPayload, FailedToCreateWallet) and the wrapper-recurse paths through
 * `mapSigningError` / `mapTransactionError`.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled to major class version 68
 * (Java 24). See [com.rain.sdk.internal.core.RainSdkManagerTurnkeyTest] for the same pattern.
 *
 * IMPORTANT: This file must NOT reference any Turnkey type in a method/field signature.
 * JUnit calls `Class.getDeclaredMethods()` during discovery, which eagerly resolves
 * parameter/return types — and that would trigger a cascading load of
 * `TurnkeyKotlinError` on JDK 21, failing before `assumeTrue` can skip. All Turnkey
 * objects live inside method bodies and are typed as [Any] / [Throwable] at the field level.
 */
class ErrorMapperTurnkeyTest {

    private val mapper = ErrorMapper()

    @Before
    fun requireJdk24() = assumeJdk24()

    @Test
    fun `mapTurnkeyError InvalidSession returns TokenExpired`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `mapTurnkeyError InvalidParameter returns InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidParameter("bad arg", null)
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `mapTurnkeyError ClientNotInitialized returns InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.ClientNotInitialized()
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with user-cancel cause maps to UserRejected`() {
        val cause = RuntimeException("User cancelled the operation")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapTurnkeyError unknown variant falls through to ProviderError`() {
        // FailedToCreateWallet wraps a plain Throwable and isn't in the InternalError allowlist.
        val cause = RuntimeException("server 500")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToCreateWallet(cause)
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapSigningError on TurnkeyKotlinError routes through mapTurnkeyError`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapper.mapSigningError(turnkeyErr)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `mapTransactionError on TurnkeyKotlinError routes through mapTurnkeyError`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapper.mapTransactionError(turnkeyErr)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }
}
