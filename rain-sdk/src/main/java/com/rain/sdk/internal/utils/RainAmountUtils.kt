package com.rain.sdk.internal.utils

import com.rain.sdk.internal.error.RainError
import java.math.BigDecimal
import java.math.BigInteger

internal object RainAmountUtils {
    /**
     * Converts a decimal amount (Double) to BigInteger (Wei/Base Units) with precision safety.
     * Throws an error if the amount has more decimal places than the token allows.
     */
    fun toBaseUnits(amount: Double, decimals: Int): BigInteger {
        val amountBd = BigDecimal.valueOf(amount)
        if (amountBd.scale() > decimals) {
            throw RainError.InvalidConfig("Amount scale (${amountBd.scale()}) exceeds token decimals ($decimals)")
        }
        return amountBd
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigInteger()
    }
}
