package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.stamper.Stamper
import com.turnkey.stamper.utils.TurnkeyStamperError
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The stamper the module's own Solana send request signs with: the vendor's key store looked up by the
 * session the vendor's flow shows. Whatever keeps that from producing a stamper is the vendor's
 * dead-session error, which the session coordinator answers with one refresh and retry.
 */
class TurnkeySessionStamperTest {

    @Before
    fun setUp() {
        assumeJdk24()
    }

    @Test
    fun `the session's key selects the stamper`() {
        val stamper = Stamper("02" + "a".repeat(64), "b".repeat(64))
        val asked = mutableListOf<String>()

        val chosen = sessionStamper("02" + "a".repeat(64)) { asked += it; stamper }

        assertThat(chosen).isSameInstanceAs(stamper)
        assertThat(asked).containsExactly("02" + "a".repeat(64))
    }

    @Test
    fun `no session is the vendor's invalid-session error before anything is looked up`() {
        var lookedUp = false

        assertThrows(TurnkeyKotlinError.InvalidSession::class.java) {
            sessionStamper(null) { lookedUp = true; Stamper("02" + "a".repeat(64), "b".repeat(64)) }
        }

        assertThat(lookedUp).isFalse()
    }

    @Test
    fun `a key the vendor's store no longer holds is the vendor's invalid-session error, cause kept`() {
        val missing = IllegalStateException("Key not found")

        val error = assertThrows(TurnkeyKotlinError.InvalidSession::class.java) {
            sessionStamper("02" + "c".repeat(64)) { throw missing }
        }

        assertThat(error.cause).isSameInstanceAs(missing)
    }

    @Test
    fun `a key the vendor's store cannot decrypt is the vendor's invalid-session error, cause kept`() {
        val unreadable = TurnkeyStamperError.KeychainFetchFailed(-1)

        val error = assertThrows(TurnkeyKotlinError.InvalidSession::class.java) {
            sessionStamper("02" + "d".repeat(64)) { throw unreadable }
        }

        assertThat(error.cause).isSameInstanceAs(unreadable)
    }
}
