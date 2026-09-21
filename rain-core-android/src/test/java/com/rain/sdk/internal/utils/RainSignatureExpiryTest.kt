package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test

/** The one parser both withdrawal paths use for Rain's `expiresAt`. */
class RainSignatureExpiryTest {

    @Test
    fun `unix seconds pass through`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("1784912091")).isEqualTo(1784912091L)
    }

    @Test
    fun `an ISO-8601 instant converts to unix seconds`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T16:54:51Z")).isEqualTo(1784912091L)
    }

    @Test
    fun `fractional seconds are accepted and truncated`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T16:54:51.000Z")).isEqualTo(1784912091L)
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T16:54:51.999Z")).isEqualTo(1784912091L)
    }

    @Test
    fun `a numeric offset converts to the same instant`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T18:54:51+02:00")).isEqualTo(1784912091L)
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T11:54:51-05:00")).isEqualTo(1784912091L)
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds(" 1784912091 ")).isEqualTo(1784912091L)
        assertThat(RainSignatureExpiry.parseEpochSeconds("\t2026-07-24T16:54:51Z\n")).isEqualTo(1784912091L)
    }

    @Test
    fun `anything else is InvalidConfig and names the accepted shapes`() {
        for (bad in listOf("", "   ", "not-a-timestamp", "2026-07-24", "2026-07-24T16:54:51", "1784912091.5")) {
            val error = assertThrows(RainError.InvalidConfig::class.java) { RainSignatureExpiry.parseEpochSeconds(bad) }
            assertThat(error.errorCode.code).isEqualTo("RAIN_102")
            assertThat(error).hasMessageThat().contains("Invalid expiresAt format")
            assertThat(error).hasMessageThat().contains("unix seconds or an ISO-8601 instant")
        }
    }
}
