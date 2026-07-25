package com.rain.sdk.internal.error

import com.turnkey.core.models.errors.TurnkeyKotlinError
import timber.log.Timber

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

        return when {
            e is TurnkeyKotlinError -> mapTurnkeyError(e)
            isUserRejection(e) -> RainError.UserRejected()
            else -> mapTurnkeyHttpStatus(e) ?: RainError.ProviderError(e)
        }
    }

    /**
     * Maps transaction execution errors to appropriate RainError types.
     *
     * @param e The exception thrown during transaction execution
     * @return Mapped RainError
     */
    fun mapTransactionError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Transaction execution error")

        return when {
            e is TurnkeyKotlinError -> mapTurnkeyError(e)
            isUserRejection(e) -> RainError.UserRejected()
            isInsufficientFunds(e) -> RainError.InsufficientFunds()
            else -> mapTurnkeyHttpStatus(e) ?: RainError.ProviderError(e)
        }
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
     *  - Wrapper errors with an underlying cause → recurse / detect user cancellation
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

        // Recurse into wrapped causes (e.g. FailedToSignRawPayload(underlying))
        val cause = e.cause
        if (cause != null && cause !== e) {
            if (cause is TurnkeyKotlinError) return mapTurnkeyError(cause)
            if (isUserRejection(cause)) return RainError.UserRejected()
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
        val message = e.message ?: return null
        if (!message.startsWith(TURNKEY_HTTP_ERROR_PREFIX)) return null
        val status = TURNKEY_HTTP_STATUS_REGEX.find(message)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        return when (status) {
            401 -> RainError.TokenExpired()
            403 -> RainError.Unauthorized("Turnkey rejected the request: HTTP $status")
            else -> null
        }
    }

    /**
     * Detects if an error indicates user rejection.
     * Checks for common rejection keywords (reject / denied / cancel) in error messages.
     * A bare "user" mention is deliberately not enough:
     * messages like "User doesn't have an embedded wallet" are not rejections.
     */
    private fun isUserRejection(e: Throwable): Boolean {
        return e.message?.let { msg ->
            msg.contains("reject", ignoreCase = true) ||
            msg.contains("denied", ignoreCase = true) ||
            msg.contains("cancel", ignoreCase = true)
        } ?: false
    }

    /**
     * Detects if an error indicates insufficient funds.
     * Checks for insufficient funds keywords in error messages.
     */
    private fun isInsufficientFunds(e: Exception): Boolean {
        return e.message?.contains("insufficient", ignoreCase = true) ?: false
    }

    private companion object {
        const val TURNKEY_HTTP_ERROR_PREFIX = "HTTP error"

        /** Trailing ": <code>" or "Code: <code>" — the two shapes the Turnkey SDK generates. */
        val TURNKEY_HTTP_STATUS_REGEX = Regex("""(?:Code:\s*|:\s*)(\d{3})\s*$""")
    }
}
