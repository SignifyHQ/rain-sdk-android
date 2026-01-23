package com.rain.sdk.internal

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.RainError
import com.rain.sdk.internal.config.RainConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.portalhq.android.Portal
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RainSdkManagerTest {

    private lateinit var sdkManager: RainSdkManager
    private lateinit var mockPortal: Portal

    @Before
    fun setUp() {
        // Reset RainConfig state
        RainConfig.clear()

        sdkManager = spyk(RainSdkManager())
        mockPortal = mockk(relaxed = true)

        // Mock Android classes (URLUtil is static)
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true
        
        // Mock createPortal to avoid real Portal instantiation
        every { 
            sdkManager.createPortal(any(), any(), any(), any(), any()) 
        } returns mockPortal
    }

    @After
    fun tearDown() {
        unmockkAll()
        RainConfig.clear()
    }

    @Test
    fun `initializePortal succeeds with empty token but portal access fails`() {
        sdkManager.initializePortal(
            portalSessionToken = "",
            rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.com")
        )
        
        // Should be initialized (for TxBuilder)
        assertThat(sdkManager.isInitialized).isTrue()
        
        // But portal access should throw exception because Portal is not initialized
        try {
            sdkManager.portal
            fail("Expected RainError.SdkNotInitialized")
        } catch (e: Exception) {
             assertThat(e).isInstanceOf(RainError.SdkNotInitialized::class.java)
        }
    }

    @Test(expected = RainError.InvalidConfig::class)
    fun `initializePortal throws error when rpcEndpoints is empty`() {
        sdkManager.initializePortal(
            portalSessionToken = "token",
            rpcEndpoints = emptyMap()
        )
    }

    @Test(expected = RainError.InvalidConfig::class)
    fun `initializePortal throws error when chainId is negative`() {
        sdkManager.initializePortal(
            portalSessionToken = "token",
            rpcEndpoints = mapOf(-1 to "https://rpc.com")
        )
    }

    @Test(expected = RainError.InvalidConfig::class)
    fun `initializePortal throws error when rpc url is invalid`() {
        every { URLUtil.isValidUrl("invalid-url") } returns false
        
        sdkManager.initializePortal(
            portalSessionToken = "token",
            rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "invalid-url")
        )
    }

    @Test
    fun `portal returns correct address when initialized`() = runBlocking {
        val expectedAddress = "0x1234567890abcdef"
        coEvery { mockPortal.getAddress(PortalNamespace.EIP155) } returns expectedAddress

        sdkManager.initializePortal(
            portalSessionToken = "valid-token",
            rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.com")
        )

        val address = sdkManager.portal.getAddress(PortalNamespace.EIP155)
        assertThat(address).isEqualTo(expectedAddress)
    }
}
