package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.config.RainConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.portalhq.android.Portal
import io.portalhq.android.api.data.GetAssetsByChainResponse
import io.portalhq.android.api.data.TokenBalance
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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

  @Test
  fun `getNativeBalance returns correct value from portal api assets`() = runBlocking {
    val chainId = 43114
    val expectedEth = 5.234

    // Mock Portal API getAssets response
    val mockResponse = mockk<GetAssetsByChainResponse>()
    every { mockResponse.nativeBalance } returns "5.234"

    coEvery {
      mockPortal.api.getAssets("${PortalNamespace.EIP155.value}:$chainId")
    } returns Result.success(mockResponse)

    sdkManager.initializePortal(
      portalSessionToken = "valid-token",
      rpcEndpoints = mapOf(chainId to "https://rpc.com"),
      chainId = null
    )

    val balance = sdkManager.getNativeBalance(chainId)
    assertThat(balance).isEqualTo(expectedEth)
  }

  @Test
  fun `getNativeBalance falls back to RPC if assets fails`() = runBlocking {
    val chainId = 43114
    val hexBalance = "0x48a27ad571340000" // 5.234 ETH in Wei hex
    val expectedEth = 5.234

    // Mock Portal API getAssets to fail
    coEvery {
      mockPortal.api.getAssets("${PortalNamespace.EIP155.value}:$chainId")
    } returns Result.failure(Exception("Assets not available"))

    // Mock Portal RPC request for eth_getBalance fallback
    val mockResult = mockk<io.portalhq.android.provider.data.PortalProviderResult>()
    val mockRpcResponse = mockk<io.portalhq.android.provider.data.PortalProviderRpcResponse>()
    every { mockResult.result } returns mockRpcResponse
    every { mockRpcResponse.result } returns hexBalance

    coEvery {
      mockPortal.getAddress(PortalNamespace.EIP155)
    } returns "0xAddress"

    coEvery {
      mockPortal.request("${PortalNamespace.EIP155.value}:$chainId", PortalRequestMethod.eth_getBalance, any())
    } returns mockResult

    sdkManager.initializePortal(
      portalSessionToken = "valid-token",
      rpcEndpoints = mapOf(chainId to "https://rpc.com"),
      chainId = null
    )

    val balance = sdkManager.getNativeBalance(chainId)
    assertThat(balance).isEqualTo(expectedEth)
  }

  @Test
  fun `getERC20Balance returns correct value from RPC`() = runBlocking {
    val chainId = 43114
    val tokenAddress = "0xToken"
    val decimals = 6
    val hexBalance = "0x0000000000000000000000000000000000000000000000000000000008f0d180" // 150.0 * 10^6
    val expectedBalance = 150.0

    // Mock Portal RPC request for eth_call
    val mockResult = mockk<io.portalhq.android.provider.data.PortalProviderResult>()
    val mockRpcResponse = mockk<io.portalhq.android.provider.data.PortalProviderRpcResponse>()
    every { mockResult.result } returns mockRpcResponse
    every { mockRpcResponse.result } returns hexBalance

    coEvery {
      mockPortal.getAddress(PortalNamespace.EIP155)
    } returns "0xAddress"

    coEvery {
      mockPortal.request("${PortalNamespace.EIP155.value}:$chainId", PortalRequestMethod.eth_call, any())
    } returns mockResult

    sdkManager.initializePortal(
      portalSessionToken = "valid-token",
      rpcEndpoints = mapOf(chainId to "https://rpc.com"),
      chainId = null
    )

    val balance = sdkManager.getERC20Balance(chainId, tokenAddress, decimals)
    assertThat(balance).isEqualTo(expectedBalance)
  }

  @Test
  fun `getERC20Balance returns 0.0 when RPC fails`() = runBlocking {
    val chainId = 43114
    val tokenAddress = "0xUnknown"

    coEvery {
      mockPortal.getAddress(PortalNamespace.EIP155)
    } returns "0xAddress"

    // Mock Portal RPC request to fail
    coEvery {
      mockPortal.request("${PortalNamespace.EIP155.value}:$chainId", PortalRequestMethod.eth_call, any())
    } throws Exception("RPC Error")

    sdkManager.initializePortal(
      portalSessionToken = "valid-token",
      rpcEndpoints = mapOf(chainId to "https://rpc.com"),
      chainId = null
    )

    val balance = sdkManager.getERC20Balance(chainId, tokenAddress)
    assertThat(balance).isEqualTo(0.0)
  }

  @Test
  fun `getERC20Balances returns map of all tokens`() = runBlocking {
    val chainId = 43114
    
    val mockResponse = mockk<GetAssetsByChainResponse>()
    val asset1 = mockk<TokenBalance>()
    val asset2 = mockk<TokenBalance>()
    
    every { asset1.contractAddress } returns "0x1"
    every { asset1.balance } returns "10.0"
    every { asset2.contractAddress } returns "0x2"
    every { asset2.balance } returns "20.5"
    every { mockResponse.tokenBalances } returns listOf(asset1, asset2)

    coEvery {
      mockPortal.api.getAssets(any())
    } returns Result.success(mockResponse)

    sdkManager.initializePortal(
      portalSessionToken = "valid-token",
      rpcEndpoints = mapOf(chainId to "https://rpc.com")
    )

    val balances = sdkManager.getERC20Balances(chainId)
    assertThat(balances).hasSize(2)
    assertThat(balances["0x1"]).isEqualTo(10.0)
    assertThat(balances["0x2"]).isEqualTo(20.5)
  }

  @Test
  fun `getBalances returns native and erc20 combined`() = runBlocking {
    val chainId = 43114
    
    val mockResponse = mockk<GetAssetsByChainResponse>()
    val asset1 = mockk<TokenBalance>()
    
    every { mockResponse.nativeBalance } returns "1.5"
    every { asset1.contractAddress } returns "0x1"
    every { asset1.balance } returns "10.0"
    every { mockResponse.tokenBalances } returns listOf(asset1)

    coEvery {
      mockPortal.api.getAssets(any())
    } returns Result.success(mockResponse)

    sdkManager.initializePortal(
      portalSessionToken = "valid-token",
      rpcEndpoints = mapOf(chainId to "https://rpc.com")
    )

    val balances = sdkManager.getBalances(chainId)
    assertThat(balances[""]).isEqualTo(1.5)
    assertThat(balances["0x1"]).isEqualTo(10.0)
  }
}
