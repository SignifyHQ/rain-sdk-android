package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test

class RainHexUtilsTest {

    @Test
    fun `hexToBytes decodes with and without 0x prefix`() {
        assertThat(RainHexUtils.hexToBytes("0xdeadbeef"))
            .isEqualTo(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
        assertThat(RainHexUtils.hexToBytes("00ff"))
            .isEqualTo(byteArrayOf(0x00, 0xff.toByte()))
    }

    @Test
    fun `hexToBytes rejects odd-length input`() {
        val ex = assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.hexToBytes("0xabc")
        }
        assertThat(ex.message).contains("odd length")
    }

    @Test
    fun `hexToBytes rejects non-hex characters instead of corrupting bytes`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.hexToBytes("0xzz11")
        }
    }

    @Test
    fun `hexToBytes enforces an expected byte count when given`() {
        // 64 bytes offered where a 65-byte signature is required.
        val ex = assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.hexToBytes("ab".repeat(64), expectedByteCount = 65)
        }
        assertThat(ex.message).contains("expected 65 bytes")

        assertThat(RainHexUtils.hexToBytes("ab".repeat(65), expectedByteCount = 65)).hasLength(65)
    }
}
