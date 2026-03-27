package com.rain.sdk.internal.provider

import com.rain.sdk.internal.core.PortalManager
import com.rain.sdk.utils.EthereumConverter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigInteger
import kotlin.math.pow

class PortalWalletProviderTest {

    private lateinit var portalManager: PortalManager
    private lateinit var portalWalletProvider: PortalWalletProvider

    @Before
    fun setUp() {
        portalManager = mockk()
        portalWalletProvider = PortalWalletProvider(portalManager)
    }

    @Test
    fun `sendNativeToken should call portalManager sendTransaction with correct params`() = runBlocking {
        // Given
        val chainId = 43114
        val fromAddress = "0x1234567890123456789012345678901234567890"
        val toAddress = "0x0987654321098765432109876543210987654321"
        val amountInEth = 1.5
        val expectedValueWeiHex = EthereumConverter.convertEthToWeiHex(amountInEth)
        val expectedTxHash = "0xHash"

        coEvery { portalManager.getAddress() } returns fromAddress
        coEvery { 
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = toAddress,
                data = "0x",
                value = expectedValueWeiHex
            )
        } returns expectedTxHash

        // When
        val result = portalWalletProvider.sendNativeToken(chainId, toAddress, amountInEth)

        // Then
        assertEquals(expectedTxHash, result)
        coVerify { 
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = toAddress,
                data = "0x",
                value = expectedValueWeiHex
            )
        }
    }

    @Test
    fun `sendToken should call portalManager sendTransaction with ABI encoded data`() = runBlocking {
        // Given
        val chainId = 43114
        val fromAddress = "0x1234567890123456789012345678901234567890"
        val toAddress = "0x0987654321098765432109876543210987654321"
        val contractAddress = "0x1111111111111111111111111111111111111111"
        val amount = 100.0
        val decimals = 6
        val expectedTxHash = "0xERC20Hash"
        
        // Calculate expected data
        val tokenAmount = amount.toBigDecimal()
            .multiply(java.math.BigDecimal.TEN.pow(decimals))
            .toBigInteger()
        val function = Function(
            "transfer",
            listOf(Address(toAddress), Uint256(tokenAmount)),
            emptyList<TypeReference<*>>()
        )
        val expectedData = FunctionEncoder.encode(function)

        coEvery { portalManager.getAddress() } returns fromAddress
        coEvery { 
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = contractAddress,
                data = expectedData,
                value = "0x0"
            )
        } returns expectedTxHash

        // When
        val result = portalWalletProvider.sendToken(chainId, contractAddress, toAddress, amount, decimals)

        // Then
        assertEquals(expectedTxHash, result)
        coVerify { 
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = contractAddress,
                data = expectedData,
                value = "0x0"
            )
        }
    }
}
