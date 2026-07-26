package com.rain.sdk.internal.utils

import com.rain.sdk.internal.error.RainError
import java.math.BigDecimal
import java.math.BigInteger

internal object RainAmountUtils {
    /**
     * Converts a decimal amount to BigInteger (Wei/Base Units) with precision safety.
     * Throws an error if the amount has more decimal places than the token allows.
     */
    fun toBaseUnits(amount: BigDecimal, decimals: Int): BigInteger {
        // A negative amount must never reach ABI encoding: uint256 two's complement would turn
        // it into an astronomically large transfer.
        if (amount.signum() < 0) {
            throw RainError.InvalidAmount(
                amount = amount.toPlainString(),
                reason = "amount must not be negative"
            )
        }
        if (amount.scale() > decimals) {
            throw RainError.InvalidAmount(
                amount = amount.toPlainString(),
                reason = "amount scale (${amount.scale()}) exceeds token decimals ($decimals)"
            )
        }
        return amount
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigInteger()
    }
}
