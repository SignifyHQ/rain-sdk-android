package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

class SolanaConverterTest {

    @Test
    fun `solToLamports scales by 1e9`() {
        assertThat(SolanaConverter.solToLamports(1.0)).isEqualTo(1_000_000_000L)
        assertThat(SolanaConverter.solToLamports(0.5)).isEqualTo(500_000_000L)
        assertThat(SolanaConverter.solToLamports(2.5)).isEqualTo(2_500_000_000L)
        assertThat(SolanaConverter.solToLamports(0.0)).isEqualTo(0L)
    }

    @Test
    fun `solToLamports handles the smallest unit without float drift`() {
        assertThat(SolanaConverter.solToLamports(0.000000001)).isEqualTo(1L)
        // 0.1 is not exactly representable in binary floating point; BigDecimal(string) avoids drift.
        assertThat(SolanaConverter.solToLamports(0.1)).isEqualTo(100_000_000L)
    }

    @Test
    fun `solToLamports truncates below one lamport`() {
        assertThat(SolanaConverter.solToLamports(0.0000000015)).isEqualTo(1L)
    }

    @Test
    fun `solToLamports rejects negative amounts`() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaConverter.solToLamports(-1.0)
        }
    }

    @Test
    fun `lamportsToSol divides by 1e9`() {
        assertThat(SolanaConverter.lamportsToSol(BigInteger.valueOf(2_500_000_000L)).toPlainString())
            .isEqualTo("2.500000000")
        assertThat(SolanaConverter.lamportsToSol(BigInteger.valueOf(1L)).toPlainString())
            .isEqualTo("0.000000001")
    }
}
