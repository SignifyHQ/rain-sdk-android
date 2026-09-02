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
