package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.helpers.StubWalletProvider
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
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// NOTE: The balance/RPC tests that previously lived here referenced Portal SDK types
// (GetAssetsByChainResponse, TokenBalance, PortalRequestMethod) and a `getBalances`
// method that no longer match the current Portal SDK 8.0.0 surface / RainSdkManager API.
// They were broken before the Turnkey integration landed; they have been temporarily
// removed and need migration. The init-and-portal-property tests below remain valid.

class RainSdkManagerTest {

  private lateinit var sdkManager: RainSdkManager
  private lateinit var mockPortal: Portal
  private lateinit var mockPortalManager: PortalManager

  @Before
  fun setUp() {
    // Reset RainConfig state
    RainConfig.reset()

    // Mock Android classes (URLUtil is static)
    mockkStatic(URLUtil::class)
    every { URLUtil.isValidUrl(any()) } returns true

    // Create mock Portal and PortalManager
    mockPortal = mockk(relaxed = true)
    mockPortalManager = spyk(PortalManager())

    // Mock PortalManager's createPortal to avoid real Portal instantiation
    every {
      mockPortalManager.createPortal(any(), any(), any(), any(), any())
    } returns mockPortal

    // Create SdkManager with mocked PortalManager
    sdkManager = RainSdkManager(
      portalManager = mockPortalManager
    )
  }

  @After
  fun tearDown() {
    unmockkAll()
    RainConfig.reset()
  }

  @Test
  fun `initializePortal succeeds with empty token but portal access fails`() {
    sdkManager.initializePortal(
      portalSessionToken = "",
      rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.com"),
      chainId = null
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
      rpcEndpoints = emptyMap(),
      chainId = null
    )
  }

  @Test(expected = RainError.InvalidConfig::class)
  fun `initializePortal throws error when chainId is negative`() {
    sdkManager.initializePortal(
      portalSessionToken = "token",
      rpcEndpoints = mapOf(-1 to "https://rpc.com"),
      chainId = null
    )
  }

  @Test(expected = RainError.InvalidConfig::class)
  fun `initializePortal throws error when rpc url is invalid`() {
    every { URLUtil.isValidUrl("invalid-url") } returns false

    sdkManager.initializePortal(
      portalSessionToken = "token",
      rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "invalid-url"),
      chainId = null
    )
  }

  @Test
  fun `portal returns correct address when initialized`() = runBlocking {
    val expectedAddress = "0x1234567890abcdef"
    coEvery { mockPortal.getAddress(PortalNamespace.EIP155) } returns expectedAddress

    sdkManager.initializePortal(
      portalSessionToken = "valid-token",
      rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.com"),
      chainId = null
    )

    val address = sdkManager.portal.getAddress(PortalNamespace.EIP155)
    assertThat(address).isEqualTo(expectedAddress)
  }

  // ---- additional init edge cases ----

  @Test
  fun `isInitialized starts false on a fresh manager`() {
    val fresh = RainSdkManager()
    assertThat(fresh.isInitialized).isFalse()
  }

  @Test
  fun `portal getter throws SdkNotInitialized before any init`() {
    val fresh = RainSdkManager()
    assertThrows(RainError.SdkNotInitialized::class.java) { fresh.portal }
  }

  @Test
  fun `turnkey getter throws SdkNotInitialized before any init`() {
    val fresh = RainSdkManager()
    assertThrows(RainError.SdkNotInitialized::class.java) { fresh.turnkey }
  }

  @Test
  fun `initializePortal with multiple chainIds succeeds and stores all RPCs`() {
    sdkManager.initializePortal(
      portalSessionToken = "token",
      rpcEndpoints = mapOf(
        RainChain.AVALANCHE_MAINNET to "https://avax.rpc",
        1 to "https://eth.rpc",
        137 to "https://polygon.rpc"
      ),
      chainId = 1
    )

    assertThat(sdkManager.isInitialized).isTrue()
  }

  @Test
  fun `initializePortal throws InvalidConfig when chainId is zero`() {
    assertThrows(RainError.InvalidConfig::class.java) {
      sdkManager.initializePortal(
        portalSessionToken = "token",
        rpcEndpoints = mapOf(0 to "https://rpc.com"),
        chainId = null
      )
    }
  }

  @Test
  fun `initializePortal throws InvalidConfig when ANY chainId is invalid`() {
    // Validation must fail even when ONE chain is bad alongside a valid one.
    assertThrows(RainError.InvalidConfig::class.java) {
      sdkManager.initializePortal(
        portalSessionToken = "token",
        rpcEndpoints = mapOf(
          1 to "https://eth.rpc",
          -2 to "https://invalid.rpc"
        ),
        chainId = null
      )
    }
  }

  @Test
  fun `initializePortal throws InvalidConfig when ANY rpc url is invalid`() {
    every { URLUtil.isValidUrl("not-a-url") } returns false

    assertThrows(RainError.InvalidConfig::class.java) {
      sdkManager.initializePortal(
        portalSessionToken = "token",
        rpcEndpoints = mapOf(
          1 to "https://eth.rpc",
          137 to "not-a-url"
        ),
        chainId = null
      )
    }
  }

  @Test
  fun `failed initializePortal leaves manager uninitialized`() {
    runCatching {
      sdkManager.initializePortal(
        portalSessionToken = "token",
        rpcEndpoints = emptyMap(),
        chainId = null
      )
    }
    assertThat(sdkManager.isInitialized).isFalse()
  }

  @Test
  fun `initializePortal twice succeeds and resets Portal instance`() {
    sdkManager.initializePortal(
      portalSessionToken = "first-token",
      rpcEndpoints = mapOf(1 to "https://eth.rpc"),
      chainId = 1
    )
    sdkManager.initializePortal(
      portalSessionToken = "second-token",
      rpcEndpoints = mapOf(137 to "https://polygon.rpc"),
      chainId = 137
    )
    assertThat(sdkManager.isInitialized).isTrue()
  }

  // ---- wallet-agnostic initialize (bring-your-own-provider) --------------------

  @Test
  fun `initialize then setWalletProvider enables wallet calls without Portal or Turnkey`(): Unit = runBlocking {
    sdkManager.initialize(
      rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.com")
    )
    assertThat(sdkManager.isInitialized).isTrue()

    val stub = StubWalletProvider()
    stub.addressToReturn = "0xb0b0000000000000000000000000000000000000"
    sdkManager.setWalletProvider(stub)

    assertThat(sdkManager.getWalletAddress())
      .isEqualTo("0xb0b0000000000000000000000000000000000000")
  }

  @Test(expected = RainError.InvalidConfig::class)
  fun `initialize throws InvalidConfig when rpcEndpoints is empty`() {
    sdkManager.initialize(rpcEndpoints = emptyMap())
  }

  @Test
  fun `initialize clears any previously installed wallet provider`() {
    sdkManager.setWalletProvider(StubWalletProvider())
    sdkManager.initialize(
      rpcEndpoints = mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.com")
    )

    // Wallet-agnostic mode starts with no provider; calls fail until one is installed.
    assertThrows(RainError.SdkNotInitialized::class.java) {
      runBlocking { sdkManager.getWalletAddress() }
    }
  }

  @Test
  fun `initialize builds a token store so sendToken resolves registry decimals without the caller`(): Unit = runBlocking {
    // Chain 1 (Ethereum) is configured; USDC is a registry token with decimals = 6.
    sdkManager.initialize(rpcEndpoints = mapOf(1 to "https://rpc.com"))
    val stub = StubWalletProvider()
    sdkManager.setWalletProvider(stub)

    // USDC contract address from TokenRegistry (decimals 6). Caller omits decimals.
    sdkManager.sendToken(
      chainId = 1,
      contractAddress = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
      toAddress = "0xfedcbafedcbafedcbafedcbafedcbafedcbafedc",
      amount = 1.0,
      decimals = null
    )

    // Resolved from the registry (6), NOT the default 18 — proving the BYO-provider path
    // has a working token store.
    assertThat(stub.sendTokenCalls.single().decimals).isEqualTo(6)
  }
}
