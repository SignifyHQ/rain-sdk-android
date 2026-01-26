package com.rain.sdk.internal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainError
import com.rain.sdk.internal.config.RainConfig
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
        mockWeb3j = mockk(relaxed = true)
        
        // Inject mock Web3j
        RainTransactionBuilderImpl.web3jFactory = { _ -> mockWeb3j }
        
        RainConfig.clear()
        Web3jProvider.shutDownAll()
    }

    @After
    fun tearDown() {
        unmockkAll()
        RainConfig.clear()
        Web3jProvider.shutDownAll()
        // Reset factory to default
        RainTransactionBuilderImpl.web3jFactory = { url -> Web3jProvider.getOrCreate(url) }
    }

    @Test
    fun `getWithdrawalNonce uses Web3jProvider and returns nonce`() = runBlocking {
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

        val nonce = RainTransactionBuilderImpl.getWithdrawalNonce(rpcUrl, proxy)

        assertThat(nonce).isEqualTo(expectedNonce)
    }
    
    @Test
    fun `buildEIP712Message resolves RPC from RainConfig when missing`() = runBlocking {
        val chainId = 1
        val rpcUrl = "https://mainnet.infura.io"
        
        // Setup RainConfig
        RainConfig.setRpcUrl(chainId, rpcUrl)
        
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
