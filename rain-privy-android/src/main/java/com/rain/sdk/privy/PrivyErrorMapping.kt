package com.rain.sdk.privy

import com.rain.sdk.internal.error.RainError
import io.privy.auth.AuthenticationException
import io.privy.network.PrivyApiException
import io.privy.wallet.EmbeddedWalletException

/**
 * Classifies Privy vendor exceptions into specific [RainError] cases at the adapter boundary:
 * - authentication failures (not logged in / invalid or expired JWT) map to [RainError.TokenExpired]
 * - user cancellation / rejection maps to [RainError.UserRejected]
 * - "insufficient ..." node messages map to [RainError.InsufficientFunds]
 * - missing wallet / failed wallet creation maps to [RainError.WalletUnavailable]
 * - any other Privy exception maps to [RainError.ProviderError]
 *
 * The Privy Kotlin SDK carries no structured reason codes on its exceptions
 * ([AuthenticationException] / [EmbeddedWalletException] are message-only), so wallet and RPC
 * failures classify by message keywords.
 *
 * Non-Privy exceptions return `null` and keep bubbling raw, so core's `ErrorMapper` fallbacks
 * (and the tests pinning that behavior) still apply.
 */
internal object PrivyErrorMapping {

    fun mapOrNull(e: Throwable): RainError? = when (e) {
        is AuthenticationException -> mapAuthenticationException(e)
        is EmbeddedWalletException -> mapEmbeddedWalletException(e)
        is PrivyApiException -> mapApiException(e)
        else -> null
    }

    private fun mapAuthenticationException(e: AuthenticationException): RainError {
        val lower = e.message?.lowercase().orEmpty()
        return when {
            // Token-expiry keywords win over rejection keywords: an expired session aborts the
            // in-flight request too ("session expired, request cancelled"), and TokenExpired is
            // the actionable classification there.
            lower.contains("not logged in") ||
                lower.contains("not authenticated") ||
                lower.contains("must be authenticated") ||
                lower.contains("jwt") ||
                lower.contains("unauthorized") ||
                lower.contains("expired") ||
                lower.contains("session") -> RainError.TokenExpired()
            isUserRejection(lower) -> RainError.UserRejected()
            else -> RainError.ProviderError(e)
        }
    }

    private fun mapEmbeddedWalletException(e: EmbeddedWalletException): RainError {
        val lower = e.message?.lowercase().orEmpty()
        return when {
            isUserRejection(lower) -> RainError.UserRejected()
            lower.contains("insufficient") -> RainError.InsufficientFunds()
            lower.contains("not logged in") ||
                lower.contains("not authenticated") ||
                lower.contains("jwt") ||
                lower.contains("unauthorized") -> RainError.TokenExpired()
            // "User doesn't have an embedded wallet.", "Failed to create embedded wallet.",
            // "Primary wallet for entropy is null", ...
            lower.contains("have an embedded wallet") ||
                lower.contains("no wallet") ||
                lower.contains("wallet for entropy is null") ||
                lower.contains("failed to create") ||
                lower.contains("creation failed") -> RainError.WalletUnavailable(
                "Privy: ${e.message}"
            )
            else -> RainError.ProviderError(e)
        }
    }

    private fun mapApiException(e: PrivyApiException): RainError = when (e.statusCode) {
        401, 403 -> RainError.TokenExpired()
        else -> RainError.ProviderError(e)
    }

    private fun isUserRejection(lowerMessage: String): Boolean =
        lowerMessage.contains("reject") ||
            lowerMessage.contains("denied") ||
            lowerMessage.contains("cancel")
}
