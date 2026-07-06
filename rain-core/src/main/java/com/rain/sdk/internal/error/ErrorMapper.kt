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
            else -> RainError.ProviderError(e)
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
            else -> RainError.ProviderError(e)
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
     * Detects if an error indicates user rejection.
     * Checks for common rejection keywords in error messages.
     */
    private fun isUserRejection(e: Throwable): Boolean {
        return e.message?.let { msg ->
            msg.contains("user", ignoreCase = true) ||
            msg.contains("reject", ignoreCase = true) ||
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
}
