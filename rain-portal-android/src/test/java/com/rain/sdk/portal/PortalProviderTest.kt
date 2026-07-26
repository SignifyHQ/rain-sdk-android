package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.provider.ProviderContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.portalhq.android.Portal
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Covers PortalProvider resolution wiring, in particular the onPortalCreated escape hatch. */
class PortalProviderTest {

    private fun context(): ProviderContext {
        val context = mockk<ProviderContext>()
        every { context.rpcEndpoints } returns mapOf(43114 to "https://rpc.test")
        every { context.tokenStore } returns mockk(relaxed = true)
        return context
    }

    @Test
    fun `create invokes onPortalCreated with the raw Portal instance`(): Unit = runBlocking {
        val portal = mockk<Portal>()
        val manager = mockk<PortalManager>(relaxed = true)
        every { manager.getPortalInstance() } returns portal

        var received: Portal? = null
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "token"),
            onPortalCreated = { received = it },
            portalManagerFactory = { manager },
        )
        provider.create(context())

        assertThat(received).isSameInstanceAs(portal)
    }

    @Test
    fun `create without a callback still initializes Portal`(): Unit = runBlocking {
        val manager = mockk<PortalManager>(relaxed = true)
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "token"),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )

        provider.create(context())

        verify(exactly = 1) {
            manager.initialize(
                apiKey = "token",
                legacyEthChainId = 43114,
                rpcConfig = mapOf("eip155:43114" to "https://rpc.test"),
                featureFlags = any(),
                autoApprove = true,
            )
        }
        verify(exactly = 0) { manager.getPortalInstance() }
    }
}
