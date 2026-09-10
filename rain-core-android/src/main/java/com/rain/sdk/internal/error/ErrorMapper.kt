package com.rain.sdk.internal.error

import com.turnkey.core.models.errors.TurnkeyKotlinError
import timber.log.Timber
import java.util.concurrent.CancellationException

/**
 * Centralized error mapping for Rain SDK.
 *
 * Maps Portal SDK, Turnkey SDK, Web3j, and other third-party errors to standardized [RainError] types.
 * Provides consistent error detection and handling across the SDK.
 */
internal class ErrorMapper {

    /**
     * Maps signing-related errors to appropriate RainError types.
     *
     * @param e The exception thrown during signing operation
     * @return Mapped RainError
     */
    fun mapSigningError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Signing error")
        return classify(e)
    }

    /**
     * Maps transaction execution errors to appropriate RainError types.
     *
     * @param e The exception thrown during transaction execution
     * @return Mapped RainError
     */
    fun mapTransactionError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Transaction execution error")
        return classify(e)
    }

    /**
     * Maps a vendor failure raised while a provider materializes its wallet, so credentials that
     * are wrong at init time surface through the same error contract as every later call.
     */
    fun mapProviderInitError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Provider initialization error")
        return classify(e)
    }

    /**
     * Maps a vendor failure raised during managed authentication — sending or confirming a login
     * code, provisioning accounts, logging out. Same classification as every other entrypoint;
     * only the log line differs, so an OTP failure is not reported as a signing error.
     */
    fun mapAuthError(e: Exception): RainError {
        val mapped = classify(e)
        // No throwable in this log line: an auth-proxy failure can echo the user's contact address.
        Timber.e("Rain SDK: Authentication error %s (%s)", mapped.errorCode.code, e.javaClass.simpleName)
        return mapped
    }

    /**
     * Typed signals win over prose heuristics: an HTTP 401 whose body happens to say
     * "session expired, request cancelled" is a session problem, not a user rejection, and
     * hosts branch on TokenExpired/Unauthorized to decide whether to re-authenticate. The
     * prose check is best-effort English vendor text and runs last.
     */
    private fun classify(e: Exception): RainError {
        if (e is TurnkeyKotlinError) return mapTurnkeyError(e)
        mapTurnkeyHttpStatus(e)?.let { return it }
        return classifyVendorProse(e) ?: RainError.ProviderError(e)
    }

    /**
     * Maps general Portal errors to RainError.
     *
     * @param e The exception thrown by Portal SDK
     * @return Mapped RainError
     */
    fun mapPortalError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Portal error")
        return RainError.ProviderError(e)
    }

    /**
     * Maps Turnkey SDK errors to RainError:
     *  - InvalidSession → TokenExpired
     *  - Config/setup-style errors (missing rpId, missing config param, client not initialized,
     *    invalid parameter / message / refresh TTL / response, OAuth state mismatch, key already
     *    exists / not found) → InternalError
     *  - Wrapper errors with an underlying cause → recurse / classify the cause's vendor prose
     *  - Everything else → ProviderError
     */
    fun mapTurnkeyError(e: TurnkeyKotlinError): RainError {
        Timber.e(e, "Rain SDK: Turnkey error")

        when (e) {
            is TurnkeyKotlinError.InvalidSession -> return RainError.TokenExpired()

            // Config / setup-time errors that indicate misuse rather than provider failure.
            is TurnkeyKotlinError.InvalidRefreshTTL,
            is TurnkeyKotlinError.ClientNotInitialized,
            is TurnkeyKotlinError.InvalidParameter,
            is TurnkeyKotlinError.InvalidResponse,
            is TurnkeyKotlinError.InvalidMessage,
            is TurnkeyKotlinError.MissingRpId,
            is TurnkeyKotlinError.MissingConfigParam,
            is TurnkeyKotlinError.OAuthStateMismatch,
            is TurnkeyKotlinError.KeyAlreadyExists,
            is TurnkeyKotlinError.KeyNotFound -> return RainError.InternalError("Turnkey: ${e.message}", e)

            // A rejected one-time login code: the auth proxy answers the verify call with a 4xx.
            // Gated on the status because the vendor wraps *every* failure of verifyOtp in this
            // type — timeouts, crypto errors, its own InvalidResponse — and 408/429 are transient,
            // not "retype your code". Everything else falls through to the cause inspection below.
            // A 401 is claimed here rather than left to the TokenExpired mapping: during code
            // verification there is no session yet, so it can only mean the code was refused.
            // Known gap: the proxy has also been seen wrapping a rejection in an HTTP 500 whose JSON
            // body carries the real status (the iOS SDK reads it); the Kotlin SDK discards that body
            // before Rain sees it, so a wrapped rejection stays a ProviderError here. The managed
            // controller keeps the challenge for every verify-step failure so the user can still
            // retype — see isLoginCodeVerifyFailure.
            is TurnkeyKotlinError.FailedToVerifyOtp ->
                if (e.cause.let(::turnkeyHttpStatus) in REJECTED_LOGIN_CODE_STATUSES) {
                    return RainError.InvalidLoginCode()
                }

            else -> Unit // fall through to cause inspection
        }

        // Recurse into wrapped causes (e.g. FailedToSignRawPayload(underlying)). The status
        // check precedes the prose check here too, for the same reason as classify().
        val cause = e.cause
        if (cause != null && cause !== e) {
            if (cause is TurnkeyKotlinError) return mapTurnkeyError(cause)
            mapTurnkeyHttpStatus(cause)?.let { return it }
            classifyVendorProse(cause)?.let { return it }
        }

        return RainError.ProviderError(e)
    }

    /**
     * Maps a Turnkey API HTTP failure to [RainError.TokenExpired] (401) or
     * [RainError.Unauthorized] (403).
     *
     * The Kotlin SDK throws a plain `RuntimeException` and carries the status only inside the
     * message, in one of two generated shapes:
     *   "HTTP error from <path>: <code>"
     *   "HTTP error calling <activityType> request\nError: <body>\nCode: <code>"
     * so the status has to be parsed out. Returns `null` for anything that is not a recognizable
     * HTTP failure, leaving the caller's fallback in place. Replace this with a typed check once
     * the Turnkey SDK exposes the status code.
     */
    private fun mapTurnkeyHttpStatus(e: Throwable): RainError? {
        return when (val status = turnkeyHttpStatus(e) ?: return null) {
            401 -> RainError.TokenExpired()
            403 -> RainError.Unauthorized("Turnkey rejected the request: HTTP $status")
            else -> null
        }
    }

    /**
     * Classifies untyped vendor prose by the shared two-word standard in
     * [VendorErrorClassifier]; null when the text matches neither rejection nor funds shortfall.
     */
    private fun classifyVendorProse(e: Throwable): RainError? {
        // Coroutine cancellation is not a wallet-UI rejection, and its type name says "cancel".
        if (e is CancellationException) return null
        return VendorErrorClassifier.fromVendorError(e)
    }

    internal companion object {
        const val TURNKEY_HTTP_ERROR_PREFIX = "HTTP error"

        /** Auth-proxy statuses that mean the login code itself was refused (not 408/429/5xx). */
        val REJECTED_LOGIN_CODE_STATUSES: Set<Int> = setOf(400, 401, 403)

        /**
         * True when [e] or any exception in its cause chain is the vendor's verify-step failure —
         * the one-time code was still being checked when the call failed. Only that step leaves a
         * code worth retrying; the account lookup, login and session steps that follow run once
         * the code was accepted, so a failure there has already spent it.
         */
        fun isLoginCodeVerifyFailure(e: Throwable): Boolean =
            generateSequence(e) { current -> current.cause?.takeIf { it !== current } }
                .take(MAX_CAUSE_DEPTH)
                .any { it is TurnkeyKotlinError.FailedToVerifyOtp }

        /** Bounds the cause walk so a cyclic cause chain cannot spin it. */
        private const val MAX_CAUSE_DEPTH = 8

        /** Trailing ": <code>" or "Code: <code>" — the two shapes the Turnkey SDK generates. */
        val TURNKEY_HTTP_STATUS_REGEX = Regex("""(?:Code:\s*|:\s*)(\d{3})\s*$""")

        /**
         * The HTTP status carried in a Turnkey SDK failure message, or null when the throwable
         * is not a recognizable Turnkey HTTP failure. Shared with the session coordinator's
         * retry classification.
         */
        fun turnkeyHttpStatus(e: Throwable): Int? {
            val message = e.message ?: return null
            if (!message.startsWith(TURNKEY_HTTP_ERROR_PREFIX)) return null
            return TURNKEY_HTTP_STATUS_REGEX.find(message)
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
        }
    }
}
