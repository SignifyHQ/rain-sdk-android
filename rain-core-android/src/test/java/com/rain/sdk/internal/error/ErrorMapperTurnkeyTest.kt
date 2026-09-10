package com.rain.sdk.internal.error

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.helpers.assumeJdk24
import org.junit.Before
import org.junit.Test

/**
 * Turnkey-error classification tests for [ErrorMapper] — covers each `TurnkeyKotlinError`
 * variant the mapper routes (InvalidSession, InvalidParameter, ClientNotInitialized,
 * FailedToSignRawPayload, FailedToCreateWallet, FailedToVerifyOtp) and the wrapper-recurse paths
 * through `mapSigningError` / `mapTransactionError`.
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
    fun `mapTurnkeyError FailedToSignRawPayload with an insufficient-funds cause maps to InsufficientFunds`() {
        val cause = RuntimeException("insufficient funds for gas * price + value")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with a single-word cause stays ProviderError`() {
        val cause = RuntimeException("Transaction cancelled")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapTurnkeyError wrapper with an HTTP 401 cause maps to TokenExpired not UserRejected`() {
        // The wrapped cause carries a status and a rejection keyword; the status wins.
        val cause = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: session expired, request cancelled\nCode: 401"
        )
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
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

    // ---------- managed auth: rejected login codes (RAIN_203) ----------

    @Test
    fun `a rejected login code maps to InvalidLoginCode not TokenExpired`() {
        // The auth proxy answers the verify call with a 4xx and the vendor wraps it twice on the
        // login-or-signup path. A 401 here is a bad code, not a dead session: no session exists yet.
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 401")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
        val mapped = mapper.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.INVALID_LOGIN_CODE)
    }

    @Test
    fun `every refused verify status maps to InvalidLoginCode`() {
        listOf(400, 401, 403).forEach { status ->
            val http = RuntimeException("HTTP error from /v1/otp_verify_v2: $status")
            val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
            val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
            assertThat(mapper.mapTurnkeyError(error)).isInstanceOf(RainError.InvalidLoginCode::class.java)
        }
    }

    @Test
    fun `a proxy 500 on verify is not a rejected code`() {
        // The Kotlin SDK discards the response body, so no embedded upstream status can be
        // recovered from a proxy-wrapped rejection: it stays a provider error and the controller
        // keeps the challenge instead (see isLoginCodeVerifyFailure).
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 500")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        assertThat(mapper.mapTurnkeyError(verify)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `isLoginCodeVerifyFailure spots the verify step anywhere in the cause chain`() {
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(
            RuntimeException("HTTP error from /v1/otp_verify_v2: 500")
        )
        val wrapped = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
        val spent = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
            com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToCreateSession(RuntimeException("io"))
        )
        assertThat(ErrorMapper.isLoginCodeVerifyFailure(verify)).isTrue()
        assertThat(ErrorMapper.isLoginCodeVerifyFailure(wrapped)).isTrue()
        assertThat(ErrorMapper.isLoginCodeVerifyFailure(spent)).isFalse()
        assertThat(ErrorMapper.isLoginCodeVerifyFailure(RuntimeException("boom"))).isFalse()
    }

    @Test
    fun `a verify failure without an HTTP status is not a rejected code`() {
        // verifyOtp wraps every failure; a dropped connection must not read as "retype your code".
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(
            java.io.IOException("unreachable")
        )
        val mapped = mapper.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a rate-limited verify is not a rejected code`() {
        // 429 is "too many attempts" and the repo classifies it as transient elsewhere.
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 429")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        val mapped = mapper.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a verify failure wrapping InvalidResponse still maps to InternalError`() {
        val invalid = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidResponse("no verification token")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(invalid)
        val mapped = mapper.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `mapAuthError on TurnkeyKotlinError routes through mapTurnkeyError`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapper.mapAuthError(turnkeyErr)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }
}
