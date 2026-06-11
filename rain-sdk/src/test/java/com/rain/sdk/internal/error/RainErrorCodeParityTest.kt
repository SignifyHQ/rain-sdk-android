package com.rain.sdk.internal.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the error-code map shared with the iOS SDK (see its ErrorMappingTests).
 * A failure here means the platforms have drifted — fix the code, not the test.
 */
class RainErrorCodeParityTest {

    @Test
    fun `error codes match the cross-platform map`() {
        val expected = mapOf(
            RainErrorCode.SDK_NOT_INITIALIZED to "RAIN_101",
            RainErrorCode.INVALID_CONFIG to "RAIN_102",
            RainErrorCode.INVALID_RPC_URL to "RAIN_103",
            RainErrorCode.TOKEN_EXPIRED to "RAIN_201",
            RainErrorCode.UNAUTHORIZED to "RAIN_202",
            RainErrorCode.NETWORK_ERROR to "RAIN_301",
            RainErrorCode.USER_REJECTED to "RAIN_401",
            RainErrorCode.INSUFFICIENT_FUNDS to "RAIN_402",
            RainErrorCode.TRANSACTION_SIMULATION_FAILED to "RAIN_403",
            RainErrorCode.WALLET_UNAVAILABLE to "RAIN_404",
            RainErrorCode.WITHDRAWAL_REVERTED_BY_NETWORK to "RAIN_405",
            RainErrorCode.PROVIDER_ERROR to "RAIN_501",
            RainErrorCode.INTERNAL_LOGIC_ERROR to "RAIN_502",
        )
        expected.forEach { (code, value) -> assertThat(code.code).isEqualTo(value) }
        assertThat(RainErrorCode.entries).hasSize(expected.size)
    }
}
