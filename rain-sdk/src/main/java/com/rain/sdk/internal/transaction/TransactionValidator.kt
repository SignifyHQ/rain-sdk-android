package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.RainError
import java.math.BigDecimal

/**
 * Validates transaction parameters.
 * 
 * Centralizes all validation logic to ensure consistent error handling
 * and clear validation rules across the SDK.
 */
internal class TransactionValidator {
    
    /**
     * Validates a withdraw collateral request.
     * 
     * @param request The request to validate
     * @throws RainError.InvalidConfig if validation fails
     */
    fun validateWithdrawRequest(request: WithdrawCollateralRequest) {
        // Validate chain ID
        if (request.chainId <= 0) {
            throw RainError.InvalidConfig("Invalid chainId: ${request.chainId}. Must be a positive integer.")
        }
        
        // Validate amount
        if (request.amount <= BigDecimal.ZERO) {
            throw RainError.InvalidConfig("Invalid amount: ${request.amount}. Must be greater than zero.")
        }
        
        // Validate decimals
        if (request.decimals < 0) {
            throw RainError.InvalidConfig("Invalid decimals: ${request.decimals}. Must be non-negative.")
        }
        
        // Additional validations can be added here as needed
        // For example: address format validation, expiry time validation, etc.
    }
}
