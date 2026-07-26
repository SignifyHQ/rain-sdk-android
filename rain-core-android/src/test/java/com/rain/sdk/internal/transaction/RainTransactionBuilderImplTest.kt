package com.rain.sdk.internal.transaction

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.models.RainWithdrawAddresses
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
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

class RainTransactionBuilderImplTest {

  private companion object {
    const val CHAIN_ID = 1
    const val RPC_URL = "https://rpc.com"
  }

  private lateinit var mockWeb3j: Web3j

  /** Builder over the mocked Web3j, configured for [CHAIN_ID] only. */
  private lateinit var builder: RainTransactionBuilderImpl

  @Before
  fun setUp() {
    // Mock Android classes (URLUtil is static)
    io.mockk.mockkStatic(android.webkit.URLUtil::class)
    io.mockk.every { android.webkit.URLUtil.isValidUrl(any()) } returns true

    mockWeb3j = mockk(relaxed = true)
    builder = RainTransactionBuilderImpl(mapOf(CHAIN_ID to RPC_URL)) { mockWeb3j }

    Web3jProvider.shutDownAll()
  }

  @After
  fun tearDown() {
    unmockkAll()
    Web3jProvider.shutDownAll()
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

    val nonce = builder.getLatestNonce(CHAIN_ID, proxy)

    assertThat(nonce).isEqualTo(expectedNonce)
  }

  @Test
  fun `getLatestNonce throws instead of defaulting to zero on an undecodable response`() = runBlocking {
    val mockEthCall = mockk<Request<*, EthCall>>()
    val mockResponse = EthCall()
    mockResponse.result = "0x"

    every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
    every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

    try {
      builder.getLatestNonce(CHAIN_ID, "0x1111111111111111111111111111111111111111")
      org.junit.Assert.fail("Expected RainError.InternalError")
    } catch (e: Exception) {
      assertThat(e).isInstanceOf(RainError.InternalError::class.java)
    }
  }

  @Test
  fun `isCollateralAdmin returns true when the contract says so`() = runBlocking {
    val mockEthCall = mockk<Request<*, EthCall>>()
    val mockResponse = EthCall()
    mockResponse.result = "0x0000000000000000000000000000000000000000000000000000000000000001"

    every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
    every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

    val result = builder.isCollateralAdmin(
      chainId = CHAIN_ID,
      proxyAddress = "0x1111111111111111111111111111111111111111",
      walletAddress = "0x2222222222222222222222222222222222222222"
    )

    assertThat(result).isTrue()
  }

  @Test
  fun `isCollateralAdmin returns false when the wallet is not an admin`() = runBlocking {
    val mockEthCall = mockk<Request<*, EthCall>>()
    val mockResponse = EthCall()
    mockResponse.result = "0x0000000000000000000000000000000000000000000000000000000000000000"

    every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
    every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

    val result = builder.isCollateralAdmin(
      chainId = CHAIN_ID,
      proxyAddress = "0x1111111111111111111111111111111111111111",
      walletAddress = "0x2222222222222222222222222222222222222222"
    )

    assertThat(result).isFalse()
  }

  @Test
  fun `isCollateralAdmin returns null when the call reverts`() = runBlocking {
    val mockEthCall = mockk<Request<*, EthCall>>()
    val mockResponse = EthCall()
    mockResponse.error = org.web3j.protocol.core.Response.Error(3, "execution reverted")

    every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
    every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

    val result = builder.isCollateralAdmin(
      chainId = CHAIN_ID,
      proxyAddress = "0x1111111111111111111111111111111111111111",
      walletAddress = "0x2222222222222222222222222222222222222222"
    )

    assertThat(result).isNull()
  }

  @Test
  fun `isCollateralAdmin returns null when the RPC fails`() = runBlocking {
    every { mockWeb3j.ethCall(any(), any()) } throws RuntimeException("connection reset")

    val result = builder.isCollateralAdmin(
      chainId = CHAIN_ID,
      proxyAddress = "0x1111111111111111111111111111111111111111",
      walletAddress = "0x2222222222222222222222222222222222222222"
    )

    assertThat(result).isNull()
  }

  @Test
  fun `getLatestNonce uses real network and returns nonce gt 0`() = runBlocking {
    val fujiChainId = 43113
    val proxy = "0x5a022623280AA5E922A4D9BB3024fA7D70D7e789"

    // Real network for this test: a builder with no Web3j override.
    val liveBuilder = RainTransactionBuilderImpl(
      mapOf(fujiChainId to "https://avax-fuji.g.alchemy.com/v2/Va-BF3-UynQD0dJvhSTm1")
    )

    val nonce = liveBuilder.getLatestNonce(fujiChainId, proxy)

    println("Nonce: $nonce")
    assertThat(nonce).isGreaterThan(BigInteger.ZERO)
  }

  @Test
  fun `buildEIP712Message resolves the configured RPC when nonce is omitted`() = runBlocking {
    val chainId = CHAIN_ID

    // Mock Web3j response for nonce call
    val mockEthCall = mockk<Request<*, EthCall>>()
    val mockResponse = EthCall()
    mockResponse.result = "0x0000000000000000000000000000000000000000000000000000000000000000" // 0

    every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
    every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

    val addresses = RainWithdrawAddresses(
      proxyAddress = "0x1111111111111111111111111111111111111111",
      controllerAddress = "0x5555555555555555555555555555555555555555",
      tokenAddress = "0x3333333333333333333333333333333333333333",
      recipientAddress = "0x4444444444444444444444444444444444444444"
    )

    val result = builder.buildEIP712Message(
      chainId = chainId,
      walletAddress = "0x2222222222222222222222222222222222222222",
      addresses = addresses,
      amount = BigDecimal("1.0"),
      decimals = 18,
      nonce = null
    )

    assertThat(result).isNotNull()
  }

  @Test
  fun `buildEIP712Message throws InvalidConfig when RPC missing and nonce missing`() = runBlocking {
    // 999 is not in the builder's endpoint map.
    val chainId = 999

    try {
      val addresses = RainWithdrawAddresses(
        proxyAddress = "0x1111111111111111111111111111111111111111",
        controllerAddress = "0x5555555555555555555555555555555555555555",
        tokenAddress = "0x3333333333333333333333333333333333333333",
        recipientAddress = "0x4444444444444444444444444444444444444444"
      )

      builder.buildEIP712Message(
        chainId = chainId,
        addresses = addresses,
        walletAddress = "0x2222222222222222222222222222222222222222",
        amount = BigDecimal("1.0"),
        decimals = 18,
        nonce = null
      )
      org.junit.Assert.fail("Expected RainError.InvalidConfig")
    } catch (e: Exception) {
      assertThat(e).isInstanceOf(RainError.InvalidConfig::class.java)
    }
   }
}
