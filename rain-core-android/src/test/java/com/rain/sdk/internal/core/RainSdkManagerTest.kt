package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainSdk
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.RainProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Tests for the modular SDK entry point [RainSdk] — builder validation and provider resolution.
 *
 * Replaces the former `RainSdkManagerTest`, whose subject (the manager's `initializePortal` /
 * `initialize` / `.portal` / `.turnkey` surface) was removed when the manager became vendor-free
 * and provider-bound. RPC/chain-id validation now lives in [RainSdk.Builder] / [ConfigManager],
 * which these tests exercise instead.
 */
class RainSdkBuilderTest {

    /** A minimal host-supplied provider used to drive resolution without any vendor SDK. */
    private class FakeProvider(
        private val walletProvider: WalletProvider
    ) : RainProvider {
        override val id: ProviderId = ProviderId("fake")
        override val capabilities: Set<Capability> = emptySet()
        override suspend fun create(context: ProviderContext): WalletProvider = walletProvider
    }

    @Before
    fun setUp() {
        // RainSdk.provider(...) validates RPC URLs via the Android URLUtil static; stub it.
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `build throws InvalidConfig when no rpcEndpoints configured`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder().build()
        }
    }

    @Test
    fun `build succeeds with zero providers (wallet-agnostic mode)`() {
        // transactionBuilder + Rain API work without any wallet provider.
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .build()

        assertThat(sdk.providerIds).isEmpty()
        assertThat(sdk.transactionBuilder).isNotNull()
    }

    @Test
    fun `resolving a provider on a provider-less SDK throws ProviderNotRegistered`() {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .build()

        assertThrows(RainError.ProviderNotRegistered::class.java) {
            runBlocking { sdk.provider(ProviderId.PORTAL) }
        }
        assertThrows(RainError.ProviderNotRegistered::class.java) {
            runBlocking { sdk.first { true } }
        }
    }

    @Test
    fun `provider resolves a registered provider into a RainClient`(): Unit = runBlocking {
        val stub = object : StubWalletProvider() {
            override val id: ProviderId = ProviderId("fake")
        }
        stub.addressToReturn = "0xb0b0000000000000000000000000000000000000"

        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(stub))
            .build()

        val client = sdk.provider(ProviderId("fake"))

        assertThat(client.providerId.value).isEqualTo("fake")
        assertThat(client.getWalletAddress())
            .isEqualTo("0xb0b0000000000000000000000000000000000000")
    }

    @Test
    fun `provider throws ProviderNotRegistered for an unregistered id`() {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(StubWalletProvider()))
            .build()

        assertThrows(RainError.ProviderNotRegistered::class.java) {
            runBlocking { sdk.provider(ProviderId("missing")) }
        }
    }

    @Test
    fun `reset leaves the SDK usable and re-resolves providers`(): Unit = runBlocking {
        val stub = object : StubWalletProvider() {
            override val id: ProviderId = ProviderId("fake")
        }
        stub.addressToReturn = "0xb0b0000000000000000000000000000000000000"

        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(stub))
            .build()

        val first = sdk.provider(ProviderId("fake"))
        sdk.reset()

        // The chain configuration survives, so the instance stays usable and the next resolve
        // re-runs create(). A rebuild via builder() is only needed to change configuration.
        val second = sdk.provider(ProviderId("fake"))
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.getWalletAddress())
            .isEqualTo("0xb0b0000000000000000000000000000000000000")
    }
}
