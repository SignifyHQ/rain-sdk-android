package com.rain.sdk.internal.abi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Byte-level tests for the shared ERC-20 calldata encoder. All three wallet adapters
 * (Portal, Privy, Turnkey) send this exact calldata, so a regression here breaks
 * `sendToken` on every provider at once.
 */
class Erc20AbiTest {

    private val recipient = "0x7f5c764cbc14f9669b88837ca1490cca17c31607"

    @Test
    fun `encodeTransfer produces selector plus padded address and amount`() {
        val data = Erc20Abi.encodeTransfer(recipient, BigInteger.valueOf(1_000_000))

        assertThat(data).isEqualTo(
            "0xa9059cbb" +
                "0000000000000000000000007f5c764cbc14f9669b88837ca1490cca17c31607" +
                "00000000000000000000000000000000000000000000000000000000000f4240"
        )
    }

    @Test
    fun `decimal overload converts amount to base units before encoding`() {
        // 1.5 USDC at 6 decimals = 1_500_000 base units
        val fromDecimal = Erc20Abi.encodeTransfer(recipient, BigDecimal("1.5"), 6)
        val fromBaseUnits = Erc20Abi.encodeTransfer(recipient, BigInteger.valueOf(1_500_000))

        assertThat(fromDecimal).isEqualTo(fromBaseUnits)
    }

    @Test
    fun `encodeTransfer handles zero amount`() {
        val data = Erc20Abi.encodeTransfer(recipient, BigInteger.ZERO)

        assertThat(data).isEqualTo(
            "0xa9059cbb" +
                "0000000000000000000000007f5c764cbc14f9669b88837ca1490cca17c31607" +
                "0000000000000000000000000000000000000000000000000000000000000000"
        )
    }

    @Test
    fun `encodeTransfer handles uint256 max`() {
        val max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE)
        val data = Erc20Abi.encodeTransfer(recipient, max)

        assertThat(data).isEqualTo(
            "0xa9059cbb" +
                "0000000000000000000000007f5c764cbc14f9669b88837ca1490cca17c31607" +
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        )
    }
}
