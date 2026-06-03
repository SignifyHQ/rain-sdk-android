package com.rain.sdk.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigInteger

/**
 * Covers the precise BigInteger converters added for the rich balance API — exact hex
 * parsing, ERC-20 `decimals()` / `symbol()` decoding, and non-18-decimal raw-amount
 * reconstruction (the inverse used when a provider only exposes a formatted decimal).
 */
class EthereumConverterTest {

    @Test
    fun `parseHexToBigInteger preserves full uint256 precision`() {
        assertThat(EthereumConverter.parseHexToBigInteger("0x0de0b6b3a7640000"))
            .isEqualTo(BigInteger("1000000000000000000"))
        assertThat(EthereumConverter.parseHexToBigInteger("de0b6b3a7640000"))
            .isEqualTo(BigInteger("1000000000000000000"))
        assertThat(EthereumConverter.parseHexToBigInteger("0x")).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.parseHexToBigInteger("0xzz")).isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `parseHexToInt parses decimals responses`() {
        assertThat(EthereumConverter.parseHexToInt("0x" + "6".padStart(64, '0'))).isEqualTo(6)
        assertThat(EthereumConverter.parseHexToInt("0x12")).isEqualTo(18)
        assertThat(EthereumConverter.parseHexToInt("0x")).isEqualTo(0)
    }

    @Test
    fun `parseHexToInt rejects out-of-range values instead of narrowing to a negative Int`() {
        // A hostile/malformed decimals() must never produce a negative decimals (which would
        // flip Balance.decimalAmount from a divide into a multiply). Clamp to 0 instead.
        assertThat(EthereumConverter.parseHexToInt("0xffffffff")).isEqualTo(0)
        assertThat(EthereumConverter.parseHexToInt("0x" + "f".repeat(64))).isEqualTo(0)
    }

    @Test
    fun `decimalStringToBigInteger reconstructs non-18-decimal raw amounts`() {
        // 1.5 USDC at 6 decimals -> 1_500_000
        assertThat(EthereumConverter.decimalStringToBigInteger("1.5", 6))
            .isEqualTo(BigInteger("1500000"))
        // 2 DAI at 18 decimals
        assertThat(EthereumConverter.decimalStringToBigInteger("2", 18))
            .isEqualTo(BigInteger("2000000000000000000"))
        // Truncates extra precision (round-down).
        assertThat(EthereumConverter.decimalStringToBigInteger("1.2345678", 6))
            .isEqualTo(BigInteger("1234567"))
    }

    @Test
    fun `decimalStringToBigInteger returns zero for empty, invalid, or non-positive input`() {
        assertThat(EthereumConverter.decimalStringToBigInteger(null, 6)).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.decimalStringToBigInteger("", 6)).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.decimalStringToBigInteger("not-a-number", 6)).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.decimalStringToBigInteger("-5", 6)).isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `parseHexToString decodes an ABI-encoded symbol`() {
        // [offset=0x20][length=4]["USDC" right-padded to 32 bytes]
        val symbolHex = "0x" +
            "20".padStart(64, '0') +
            "4".padStart(64, '0') +
            "55534443".padEnd(64, '0')
        assertThat(EthereumConverter.parseHexToString(symbolHex)).isEqualTo("USDC")
        // Too short / malformed → null.
        assertThat(EthereumConverter.parseHexToString("0x1234")).isNull()
    }
}
