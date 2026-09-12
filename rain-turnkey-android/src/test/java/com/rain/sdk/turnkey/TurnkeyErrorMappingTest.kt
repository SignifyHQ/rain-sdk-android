package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.error.RainErrorCode
import org.junit.Before
import org.junit.Test

/**
 * Turnkey-error classification tests for [TurnkeyErrorMapping] — covers each `TurnkeyKotlinError`
 * variant it routes (InvalidSession, InvalidParameter, ClientNotInitialized,
 * FailedToSignRawPayload, FailedToCreateWallet, FailedToVerifyOtp, FailedToInitOtp), the
 * wrapper-recurse paths, and the HTTP statuses the vendor reports as plain exceptions.
 *
 * These assert the mapping itself. That the session coordinator applies [TurnkeyErrorMapping.map]
 * to every failure leaving the adapter is covered by [TurnkeySessionCoordinatorTest]; that core
 * passes the result through untouched is covered by `com.rain.sdk.RainSdkAdapterErrorPassthroughTest`.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled to major class version 68
 * (Java 24). See [TurnkeyWalletFlowTest] for the same pattern.
 *
 * IMPORTANT: This file must NOT reference any Turnkey type in a method/field signature.
 * JUnit calls `Class.getDeclaredMethods()` during discovery, which eagerly resolves
 * parameter/return types — and that would trigger a cascading load of
 * `TurnkeyKotlinError` on JDK 21, failing before `assumeTrue` can skip. All Turnkey
 * objects live inside method bodies and are typed as [Any] / [Throwable] at the field level.
 */
class TurnkeyErrorMappingTest {

    private val mapping = TurnkeyErrorMapping

    @Before
    fun requireJdk24() = assumeJdk24()

    @Test
    fun `mapTurnkeyError InvalidSession returns TokenExpired`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `mapTurnkeyError InvalidParameter returns InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidParameter("bad arg", null)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `mapTurnkeyError ClientNotInitialized returns InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.ClientNotInitialized()
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with user-cancel cause maps to UserRejected`() {
        val cause = RuntimeException("User cancelled the operation")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with an insufficient-funds cause maps to InsufficientFunds`() {
        val cause = RuntimeException("insufficient funds for gas * price + value")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with a single-word cause stays ProviderError`() {
        val cause = RuntimeException("Transaction cancelled")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapping.mapTurnkeyError(error)
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
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `mapTurnkeyError unknown variant falls through to ProviderError`() {
        // FailedToCreateWallet wraps a plain Throwable and isn't in the InternalError allowlist.
        val cause = RuntimeException("server 500")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToCreateWallet(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `classify routes a typed error through mapTurnkeyError`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapping.classify(turnkeyErr)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `classify declines a throwable that is not a Turnkey failure`() {
        val mapped = mapping.classify(RuntimeException("something else entirely"))
        assertThat(mapped).isNull()
    }

    @Test
    fun `map floors a throwable nothing recognizes at ProviderError`() {
        val e = RuntimeException("something else entirely")
        val mapped = mapping.map(e)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(e)
    }

    @Test
    fun `map passes a RainError through untouched`() {
        val raised = RainError.InvalidConfig("already mapped")
        assertThat(mapping.map(raised)).isSameInstanceAs(raised)
    }

    @Test
    fun `classify is total for a Turnkey error whose prose reads like a rejection`() {
        // A cause-less vendor error naming no status: mapTurnkeyError still owns it, so core's
        // prose heuristics never see "user rejected" and cannot reclassify it as UserRejected.
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError
            .InvalidResponse("user rejected the request")
        assertThat(mapping.classify(turnkeyErr)).isInstanceOf(RainError.InternalError::class.java)
        // The same holds at map(), which is what the coordinator applies at the boundary.
        assertThat(mapping.map(turnkeyErr)).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `map lets a 401 status beat rejection prose in the same message`() {
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: user rejected the signing request\nCode: 401"
        )
        assertThat(mapping.map(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `the classifier carries the error code, not just the error class`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        assertThat(mapping.classify(turnkeyErr)?.errorCode).isEqualTo(RainErrorCode.TOKEN_EXPIRED)
    }

    // ---------- managed auth: rejected login codes (RAIN_203) ----------

    @Test
    fun `a rejected login code maps to InvalidLoginCode not TokenExpired`() {
        // The auth proxy answers the verify call with a 4xx and the vendor wraps it twice on the
        // login-or-signup path. A 401 here is a bad code, not a dead session: no session exists yet.
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 401")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.INVALID_LOGIN_CODE)
    }

    @Test
    fun `every refused verify status maps to InvalidLoginCode`() {
        listOf(400, 401, 403).forEach { status ->
            val http = RuntimeException("HTTP error from /v1/otp_verify_v2: $status")
            val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
            val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
            assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.InvalidLoginCode::class.java)
        }
    }

    @Test
    fun `a proxy 500 on verify is not a rejected code`() {
        // The Kotlin SDK discards the response body, so no embedded upstream status can be
        // recovered from a proxy-wrapped rejection: it stays a provider error and the controller
        // keeps the challenge instead (see isLoginCodeVerifyFailure).
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 500")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        assertThat(mapping.mapTurnkeyError(verify)).isInstanceOf(RainError.ProviderError::class.java)
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
        assertThat(mapping.isLoginCodeVerifyFailure(verify)).isTrue()
        assertThat(mapping.isLoginCodeVerifyFailure(wrapped)).isTrue()
        assertThat(mapping.isLoginCodeVerifyFailure(spent)).isFalse()
        assertThat(mapping.isLoginCodeVerifyFailure(RuntimeException("boom"))).isFalse()
    }

    @Test
    fun `a verify failure without an HTTP status is not a rejected code`() {
        // verifyOtp wraps every failure; a dropped connection must not read as "retype your code".
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(
            java.io.IOException("unreachable")
        )
        val mapped = mapping.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a rate-limited verify is not a rejected code`() {
        // 429 is "too many attempts" and the repo classifies it as transient elsewhere.
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 429")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        val mapped = mapping.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a verify failure wrapping InvalidResponse still maps to InternalError`() {
        val invalid = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidResponse("no verification token")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(invalid)
        val mapped = mapping.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    // ---------- managed auth: failed code requests (send path) ----------

    @Test
    fun `a failed code request maps to ProviderError for every status`() {
        // No session exists while a code is being requested, so an init 401 or 403 is not an
        // expired session or a permission problem. The reason (channel disabled, undeliverable
        // number, rate limit) is in the response body the Kotlin SDK drops; the status survives.
        listOf(400, 401, 403, 429, 500).forEach { status ->
            val http = RuntimeException("HTTP error from /v1/otp_init_v2: $status")
            val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(http)
            val mapped = mapping.mapTurnkeyError(error)
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
            assertThat(mapped).hasMessageThat().contains(status.toString())
        }
    }

    @Test
    fun `a failed code request without an HTTP status maps to ProviderError`() {
        val dropped = java.io.IOException("unreachable")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(dropped)
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a failed code request wrapping a vendor setup error keeps that classification`() {
        // The vendor rewraps its own errors on the init path; a client that is not initialized is a
        // setup problem (InternalError), not a request the user can retry.
        val setup = com.turnkey.core.models.errors.TurnkeyKotlinError.ClientNotInitialized()
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(setup)
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `a code request that wraps a cancellation while the caller is active is a ProviderError`() {
        // The controller checks the caller's own cancellation before mapping; a vendor wrapper
        // around someone else's cancellation is a vendor failure, not a user rejection.
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(
            java.util.concurrent.CancellationException("inner job")
        )
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `the status parser reads the init path like the verify path`() {
        val init = RuntimeException("HTTP error from /v1/otp_init_v2: 400")
        val verify = RuntimeException("HTTP error from /v1/otp_verify_v2: 401")
        assertThat(mapping.turnkeyHttpStatus(init)).isEqualTo(400)
        assertThat(mapping.turnkeyHttpStatus(verify)).isEqualTo(401)
    }

    @Test
    fun `mapAuthError never hands the throwable to the log`() {
        // The auth proxy echoes the contact a code was sent to, so the only log line on this path
        // carries the error code and the class name. Captured through a planted tree: Timber folds
        // a throwable's stack trace into the message it hands the tree.
        val seen = StringBuilder()
        val throwables = mutableListOf<Throwable>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                seen.append(message).append('\n')
                if (t != null) throwables += t
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val http = RuntimeException("HTTP error from /v1/otp_init_v2 for someone@example.com: 401")
            // The wrapped shape the vendor produces, and the bare status-carrying shape.
            mapping.mapAuthError(com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(http))
            mapping.mapAuthError(http)
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertThat(seen.toString()).contains("Authentication error")
        assertThat(seen.toString()).contains(RainErrorCode.TOKEN_EXPIRED.code) // the bare 401 parsed
        assertThat(seen.toString()).doesNotContain("example.com")
        assertThat(throwables).isEmpty()
    }

    @Test
    fun `mapAuthError routes a failed code request to ProviderError`() {
        val http = RuntimeException("HTTP error from /v1/otp_init_v2: 401")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(http)
        assertThat(mapping.mapAuthError(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapAuthError on TurnkeyKotlinError routes through mapTurnkeyError`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapping.mapAuthError(turnkeyErr)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    // ---------- HTTP statuses the vendor reports as plain exceptions ----------
    //
    // The Turnkey Kotlin SDK throws a plain RuntimeException carrying the status only in the
    // message, in two generated shapes. Both must classify to the same RainError. These moved
    // here from core's ErrorMapperTest when the vendor knowledge left core; the composed cases
    // go through map(), which is what the session coordinator applies at the boundary.

    @Test
    fun `a 401 from the query path maps to TokenExpired`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 401")
        assertThat(mapping.classify(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 401 from the activity path maps to TokenExpired`() {
        val e = RuntimeException("HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\nError: {}\nCode: 401")
        assertThat(mapping.classify(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 403 maps to Unauthorized`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 403")
        assertThat(mapping.classify(e)).isInstanceOf(RainError.Unauthorized::class.java)
    }

    @Test
    fun `a 401 whose body contains rejection keywords still maps to TokenExpired`() {
        // The status is the reliable signal; "session expired, request cancelled" is a session
        // problem, and hosts branch on TokenExpired to re-authenticate.
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: session expired, request cancelled\nCode: 401"
        )
        assertThat(mapping.classify(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 403 whose body says permission denied still maps to Unauthorized`() {
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: permission denied\nCode: 403"
        )
        assertThat(mapping.classify(e)).isInstanceOf(RainError.Unauthorized::class.java)
    }

    @Test
    fun `an unclassified HTTP status falls through to the prose checks`() {
        // 400 is not a session or permission verdict, so classify() declines and map() reads the
        // body: the user declined, and that is what the host must see.
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: user rejected the signing request\nCode: 400"
        )
        assertThat(mapping.classify(e)).isNull()
        assertThat(mapping.map(e)).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `other HTTP statuses stay ProviderError`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 500")
        assertThat(mapping.classify(e)).isNull()
        assertThat(mapping.map(e)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a non-HTTP message ending in digits is not treated as a status`() {
        val e = RuntimeException("Something failed for wallet 401")
        assertThat(mapping.turnkeyHttpStatus(e)).isNull()
        assertThat(mapping.map(e)).isInstanceOf(RainError.ProviderError::class.java)
    }
}
