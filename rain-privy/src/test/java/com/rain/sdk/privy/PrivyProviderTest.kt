package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivyProviderTest {

    @Test
    fun `descriptor advertises the privy id and capabilities`() {
        val provider = PrivyProvider(PrivyConfig(appId = "app-id"))
        assertThat(provider.id).isEqualTo(ProviderId.PRIVY)
        assertThat(provider.capabilities).containsExactly(Capability.EXPORT, Capability.RECOVERY)
    }

    @Test
    fun `wallet provider operations are not implemented yet`() {
        val wallet = PrivyWalletProvider()
        assertThat(wallet.id).isEqualTo(ProviderId.PRIVY)
        assertThrows(NotImplementedError::class.java) {
            runBlocking { wallet.getWalletAddress() }
        }
    }
}
