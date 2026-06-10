package com.rain.sdk.internal.provider

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.core.PortalManager
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
import com.rain.sdk.utils.EthereumConverter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        // The token store is only consulted inside PortalManager (mocked here), so a relaxed
        // mock suffices — the provider just forwards it through.
        portalWalletProvider = PortalWalletProvider(portalManager, mockk(relaxed = true))
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

    // ---- Balance delegations ----------------------------------------------------

    @Test
    fun `getAddress delegates to PortalManager`() = runBlocking {
        coEvery { portalManager.getAddress() } returns TestFixtures.WALLET_ADDRESS

        val address = portalWalletProvider.getWalletAddress()

        assertThat(address).isEqualTo(TestFixtures.WALLET_ADDRESS)
        coVerify { portalManager.getAddress() }
    }

    @Test
    fun `getBalance delegates to PortalManager and returns its result`() = runBlocking {
        val expected = Balance(
            token = Token.Native,
            chainId = 43114,
            rawAmount = BigInteger("1500000000000000000"),
            decimals = 18,
            symbol = "AVAX",
            name = "Avalanche"
        )
        coEvery { portalManager.getBalance(eq(43114), eq(Token.Native), any()) } returns expected

        val balance = portalWalletProvider.getBalance(chainId = 43114, token = Token.Native)

        assertThat(balance).isEqualTo(expected)
        coVerify { portalManager.getBalance(43114, Token.Native, any()) }
    }

    @Test
    fun `getBalance propagates PortalManager errors`() {
        coEvery { portalManager.getBalance(any(), any(), any()) } throws RainError.ProviderError(
            RuntimeException("portal indexer failed")
        )

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking { portalWalletProvider.getBalance(chainId = 43114, token = Token.Native) }
        }
    }

    @Test
    fun `getBalances delegates to PortalManager and returns its result`() = runBlocking {
        val expected = listOf(
            Balance(Token.Native, 1, BigInteger.ZERO, 18, "ETH", "Ether"),
            Balance(Token.Contract(TestFixtures.USDC_ADDRESS), 1, BigInteger("25000000"), 6, "USDC", "USDC")
        )
        coEvery { portalManager.getBalances(eq(1), any()) } returns expected

        val result = portalWalletProvider.getBalances(chainId = 1)

        assertThat(result).isEqualTo(expected)
        coVerify { portalManager.getBalances(1, any()) }
    }

    @Test
    fun `getBalances propagates PortalManager errors`() {
        coEvery { portalManager.getBalances(any(), any()) } throws RainError.ProviderError(
            RuntimeException("getAssets failed")
        )

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking { portalWalletProvider.getBalances(chainId = 1) }
        }
    }

    // ---- Transaction history delegation -----------------------------------------

    @Test
    fun `getTransactions forwards pagination + order to PortalManager`() = runBlocking {
        val expected = RainTransactionResult(
            transactions = listOf(
                RainTransaction(
                    hash = "0xabc",
                    blockNumber = "100",
                    from = "0xfrom",
                    to = "0xto",
                    value = "1.0",
                    chainId = "43114"
                )
            )
        )
        coEvery {
            portalManager.getTransactions(43114, 5, 2, RainTransactionOrder.DESC)
        } returns expected

        val result = portalWalletProvider.getTransactions(
            chainId = 43114,
            limit = 5,
            offset = 2,
            order = RainTransactionOrder.DESC
        )

        assertThat(result).isSameInstanceAs(expected)
        coVerify { portalManager.getTransactions(43114, 5, 2, RainTransactionOrder.DESC) }
    }

    // ---- Signing + low-level sendTransaction + fee estimation -------------------

    @Test
    fun `signTypedData delegates to PortalManager with chain + wallet + JSON`() = runBlocking {
        val expectedSig = "0x" + "1".repeat(130)
        coEvery {
            portalManager.signTypedData(1, TestFixtures.WALLET_ADDRESS, """{"foo":"bar"}""")
        } returns expectedSig

        val signature = portalWalletProvider.signTypedData(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            typedDataJson = """{"foo":"bar"}"""
        )

        assertThat(signature).isEqualTo(expectedSig)
    }

    @Test
    fun `sendTransaction (low-level) delegates to PortalManager`() = runBlocking {
        val expectedHash = "0x" + "f".repeat(64)
        coEvery {
            portalManager.sendTransaction(
                chainId = 1,
                from = TestFixtures.WALLET_ADDRESS,
                to = TestFixtures.CONTRACT_ADDRESS,
                data = "0xdeadbeef",
                value = "0x0"
            )
        } returns expectedHash

        val txHash = portalWalletProvider.sendTransaction(
            chainId = 1,
            from = TestFixtures.WALLET_ADDRESS,
            to = TestFixtures.CONTRACT_ADDRESS,
            data = "0xdeadbeef",
            value = "0x0"
        )

        assertThat(txHash).isEqualTo(expectedHash)
    }

    @Test
    fun `sendTransaction propagates TransactionSimulationFailed from PortalManager`() {
        coEvery {
            portalManager.sendTransaction(any(), any(), any(), any(), any())
        } throws RainError.TransactionSimulationFailed(RuntimeException("revert"))

        assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking {
                portalWalletProvider.sendTransaction(
                    chainId = 1,
                    from = TestFixtures.WALLET_ADDRESS,
                    to = TestFixtures.CONTRACT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }

    @Test
    fun `estimateTransactionFee delegates to PortalManager`() = runBlocking {
        coEvery {
            portalManager.estimateTransactionFee(1, TestFixtures.WALLET_ADDRESS, TestFixtures.CONTRACT_ADDRESS, "0x", "0x0")
        } returns 0.00042

        val fee = portalWalletProvider.estimateTransactionFee(
            chainId = 1,
            from = TestFixtures.WALLET_ADDRESS,
            to = TestFixtures.CONTRACT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        assertThat(fee).isWithin(1e-12).of(0.00042)
    }
}
