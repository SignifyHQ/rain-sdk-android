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
     * Validates a withdraw collateral request. An unusable chain is a config error; an unusable
     * amount or scale is an amount error.
     *
     * @throws RainError.InvalidConfig for a non-positive chainId
     * @throws RainError.InvalidAmount for a non-positive amount or negative decimals
     */
    fun validateWithdrawRequest(request: WithdrawCollateralRequest) {
        if (request.chainId <= 0) {
            throw RainError.InvalidConfig("Invalid chainId: ${request.chainId}. Must be a positive integer.")
        }

        if (request.amount <= BigDecimal.ZERO) {
            throw RainError.InvalidAmount(
                request.amount.toPlainString(),
                "amount must be greater than zero"
            )
        }

        if (request.decimals < 0) {
            throw RainError.InvalidAmount(
                request.amount.toPlainString(),
                "decimals must be non-negative, got ${request.decimals}"
            )
        }
    }
}
