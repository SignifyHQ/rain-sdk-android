package com.rain.sdk.wallet

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.turnkey.LoginContact
import com.rain.sdk.turnkey.TurnkeyAuthState
import com.rain.sdk.turnkey.TurnkeySessionPolicy
import com.rain.sdk.turnkey.TurnkeySessionState
import org.junit.Assert.assertThrows
import org.junit.Test

/** Every neutral type maps one-to-one onto its backing type, and value semantics hold. */
class RainWalletMappingTest {

    @Test
    fun `the default policy maps onto the backing defaults`() {
        // The backing policy is a data class, so this one assertion pins all six defaults.
        assertThat(RainWalletSessionPolicy().toBacking()).isEqualTo(TurnkeySessionPolicy())
    }

    @Test
    fun `a customised policy maps every field`() {
        val backing = RainWalletSessionPolicy(
            refreshBufferSeconds = 30,
            autoRefresh = false,
            refreshExpirationSeconds = 1_200,
            maxTransientRetries = 5,
            initialRetryDelayMs = 1_000,
            maxRetryDelayMs = 8_000,
        ).toBacking()

        assertThat(backing.refreshBufferSeconds).isEqualTo(30L)
        assertThat(backing.autoRefresh).isFalse()
        assertThat(backing.refreshExpirationSeconds).isEqualTo("1200")
        assertThat(backing.maxTransientRetries).isEqualTo(5)
        assertThat(backing.initialRetryDelayMs).isEqualTo(1_000L)
        assertThat(backing.maxRetryDelayMs).isEqualTo(8_000L)
    }

    @Test
    fun `out-of-range policy values are refused at construction, naming the value`() {
        fun refused(build: () -> RainWalletSessionPolicy): String =
            assertThrows(IllegalArgumentException::class.java) { build() }.message.orEmpty()

        assertThat(refused { RainWalletSessionPolicy(refreshBufferSeconds = -1) })
            .contains("refreshBufferSeconds must be >= 0, was -1")
        assertThat(refused { RainWalletSessionPolicy(refreshExpirationSeconds = 0) })
            .contains("refreshExpirationSeconds must be > 0, was 0")
        assertThat(refused { RainWalletSessionPolicy(maxTransientRetries = -1) })
            .contains("maxTransientRetries must be >= 0, was -1")
        assertThat(refused { RainWalletSessionPolicy(initialRetryDelayMs = -1) })
            .contains("initialRetryDelayMs must be >= 0, was -1")
        assertThat(refused { RainWalletSessionPolicy(initialRetryDelayMs = 5_000, maxRetryDelayMs = 4_000) })
            .contains("maxRetryDelayMs must be >= initialRetryDelayMs, was 4000 vs 5000")
    }

    @Test
    fun `session states map one-to-one and carry the expiry`() {
        assertThat(TurnkeySessionState.Loading.toRainWallet()).isEqualTo(RainWalletSessionState.Loading)
        assertThat(TurnkeySessionState.Active(42.5).toRainWallet()).isEqualTo(RainWalletSessionState.Active(42.5))
        assertThat(TurnkeySessionState.Expired.toRainWallet()).isEqualTo(RainWalletSessionState.Expired)
        assertThat(TurnkeySessionState.Unauthenticated.toRainWallet())
            .isEqualTo(RainWalletSessionState.Unauthenticated)
    }

    @Test
    fun `an active state compares by expiry`() {
        assertThat(RainWalletSessionState.Active(1.0)).isEqualTo(RainWalletSessionState.Active(1.0))
        assertThat(RainWalletSessionState.Active(1.0)).isNotEqualTo(RainWalletSessionState.Active(2.0))
        assertThat(RainWalletSessionState.Active(1.0).hashCode())
            .isEqualTo(RainWalletSessionState.Active(1.0).hashCode())
        assertThat(RainWalletSessionState.Active(1.0).toString()).contains("1.0")
    }

    @Test
    fun `auth states map one-to-one`() {
        assertThat(TurnkeyAuthState.Loading.toRainWallet()).isEqualTo(RainWalletAuthState.Loading)
        assertThat(TurnkeyAuthState.Authenticated.toRainWallet()).isEqualTo(RainWalletAuthState.Authenticated)
        assertThat(TurnkeyAuthState.Unauthenticated.toRainWallet()).isEqualTo(RainWalletAuthState.Unauthenticated)
    }

    @Test
    fun `contacts map to the matching backing contact with the value unchanged`() {
        assertThat(RainWalletContact.Email("User@Example.com").toBacking())
            .isEqualTo(LoginContact.Email("User@Example.com"))
        assertThat(RainWalletContact.Sms("+1 (555) 123-4567").toBacking())
            .isEqualTo(LoginContact.Sms("+1 (555) 123-4567"))
    }

    @Test
    fun `contacts compare by value and hide it in toString`() {
        assertThat(RainWalletContact.Email("a@b.c")).isEqualTo(RainWalletContact.Email("a@b.c"))
        assertThat(RainWalletContact.Email("a@b.c").hashCode()).isEqualTo(RainWalletContact.Email("a@b.c").hashCode())
        assertThat(RainWalletContact.Email("a@b.c")).isNotEqualTo(RainWalletContact.Sms("a@b.c"))
        assertThat(RainWalletContact.Email("a@b.c").hashCode()).isNotEqualTo(RainWalletContact.Sms("a@b.c").hashCode())
        assertThat(RainWalletContact.Email("a@b.c").toString()).doesNotContain("a@b.c")
        assertThat(RainWalletContact.Sms("+15551234567").toString()).doesNotContain("555")
    }

    @Test
    fun `every key account maps to the backing family of the same name`() {
        // Totality over the wallet enum; the exhaustive `when` in toBacking() already guarantees
        // no wallet constant is unmapped, so this pins that none is crossed.
        assertThat(RainWalletKeyAccount.entries.map { it.toBacking().name })
            .containsExactlyElementsIn(RainWalletKeyAccount.entries.map { it.name })
            .inOrder()
    }

    @Test
    fun `the policy prints every field`() {
        assertThat(RainWalletSessionPolicy().toString()).isEqualTo(
            "RainWalletSessionPolicy(refreshBufferSeconds=60, autoRefresh=true, refreshExpirationSeconds=null, " +
                "maxTransientRetries=2, initialRetryDelayMs=500, maxRetryDelayMs=4000)"
        )
    }

    @Test
    fun `the config prints every field and the hook as set or null`() {
        assertThat(RainWalletConfig().toString()).isEqualTo(
            "RainWalletConfig(sessionPolicy=${RainWalletSessionPolicy()}, " +
                "onSessionExpired=null, sponsorGas=true)"
        )
        assertThat(RainWalletConfig(onSessionExpired = {}).toString()).contains("onSessionExpired=set")
    }
}
