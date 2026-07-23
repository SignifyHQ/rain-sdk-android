package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertThrows
import org.junit.Test

class RainAmountUtilsTest {

    @Test
    fun `toBaseUnits scales by token decimals`() {
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("1.5"), 6))
            .isEqualTo(BigInteger("1500000"))
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("0.000001"), 6))
            .isEqualTo(BigInteger.ONE)
    }

    @Test
    fun `toBaseUnits throws InvalidAmount for over-precision amounts`() {
        // 7 decimal places on a 6-decimal token: more precision than the token can represent.
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            RainAmountUtils.toBaseUnits(BigDecimal("1.2345678"), 6)
        }
        assertThat(ex.amount).isEqualTo("1.2345678")
        assertThat(ex.message).contains("decimals")
    }
}
