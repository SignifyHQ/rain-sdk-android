package com.rain.sdk.internal.error

import timber.log.Timber

/**
 * Centralized error mapping for Rain SDK.
 * 
 * Maps Portal SDK, Web3j, and other third-party errors to standardized RainError types.
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
     * Detects if an error indicates user rejection.
     * Checks for common rejection keywords in error messages.
     */
    private fun isUserRejection(e: Exception): Boolean {
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
