package com.rain.sdk.internal.transaction

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.network.Web3jProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthCall
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

class RainTransactionBuilderImplTest {

  private lateinit var mockWeb3j: Web3j

  @Before
  fun setUp() {
    // Mock Android classes (URLUtil is static)
    io.mockk.mockkStatic(android.webkit.URLUtil::class)
    io.mockk.every { android.webkit.URLUtil.isValidUrl(any()) } returns true

    mockWeb3j = mockk(relaxed = true)

    // Inject mock Web3j
    RainTransactionBuilderImpl.web3jFactory = { _ -> mockWeb3j }

    RainConfig.reset()
    Web3jProvider.shutDownAll()
  }

  @After
  fun tearDown() {
    unmockkAll()
    RainConfig.reset()
    Web3jProvider.shutDownAll()
    // Reset factory to default
    RainTransactionBuilderImpl.web3jFactory = { url -> Web3jProvider.getOrCreate(url) }
  }


  @Test
  fun `getLatestNonce uses Web3jProvider and returns nonce`() = runBlocking {
    val rpcUrl = "https://rpc.com"
    val proxy = "0x1111111111111111111111111111111111111111"
    val expectedNonce = BigInteger.TEN

    // Mock Web3j ethCall
    val mockEthCall = mockk<Request<*, EthCall>>()
    val mockResponse = EthCall()
    // result for 10 in hex
    mockResponse.result = "0x000000000000000000000000000000000000000000000000000000000000000a"

    every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
    every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

    val nonce = RainTransactionBuilderImpl.getLatestNonce(rpcUrl, proxy)

    assertThat(nonce).isEqualTo(expectedNonce)
  }

  @Test
  fun `getLatestNonce uses real network and returns nonce gt 0`() = runBlocking {
    // Use real network for this test
    RainTransactionBuilderImpl.resetFactory()

    val rpcUrl = "https://avax-fuji.g.alchemy.com/v2/Va-BF3-UynQD0dJvhSTm1"
    val proxy = "0x5a022623280AA5E922A4D9BB3024fA7D70D7e789"

    val nonce = RainTransactionBuilderImpl.getLatestNonce(rpcUrl, proxy)

    println("Nonce: $nonce")
    assertThat(nonce).isGreaterThan(BigInteger.ZERO)
  }

  @Test
  fun `buildEIP712Message resolves RPC from RainConfig when missing`() = runBlocking {
    val chainId = 1
    val rpcUrl = "https://mainnet.infura.io"

    // Setup RainConfig
    RainConfig.getInstance().setRpcUrl(chainId, rpcUrl)

    // Mock Web3j response for nonce call
    val mockEthCall = mockk<Request<*, EthCall>>()
    val mockResponse = EthCall()
    mockResponse.result = "0x0000000000000000000000000000000000000000000000000000000000000000" // 0

    every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
    every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

    val result = RainTransactionBuilderImpl.buildEIP712Message(
      chainId = chainId,
      collateralProxyAddress = "0x1111111111111111111111111111111111111111",
      walletAddress = "0x2222222222222222222222222222222222222222",
      tokenAddress = "0x3333333333333333333333333333333333333333",
      amount = 1.0,
      decimals = 18,
      recipientAddress = "0x4444444444444444444444444444444444444444",
      nonce = null
    )

    assertThat(result).isNotNull()
  }

  @Test
  fun `buildEIP712Message throws InvalidConfig when RPC missing and nonce missing`() = runBlocking {
    val chainId = 999
    // Ensure RainConfig has no RPC for 999

    try {
      RainTransactionBuilderImpl.buildEIP712Message(
        chainId = chainId,
        collateralProxyAddress = "0x1111111111111111111111111111111111111111",
        walletAddress = "0x2222222222222222222222222222222222222222",
        tokenAddress = "0x3333333333333333333333333333333333333333",
        amount = 1.0,
        decimals = 18,
        recipientAddress = "0x4444444444444444444444444444444444444444",
        nonce = null
      )
      org.junit.Assert.fail("Expected RainError.InvalidConfig")
    } catch (e: Exception) {
      assertThat(e).isInstanceOf(RainError.InvalidConfig::class.java)
    }
   }
}
