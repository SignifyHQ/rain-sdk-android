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
        if (amount.scale() > decimals) {
            throw RainError.InvalidConfig("Amount scale (${amount.scale()}) exceeds token decimals ($decimals)")
        }
        return amount
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigInteger()
    }
}
