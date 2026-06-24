package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Manager-contract tests for send APIs — validation, mode guards, error wrapping.
 * Provider-specific success paths live in `PortalWalletProviderTest` /
 * `TurnkeyWalletProviderTest`.
 */
class RainSdkManagerSendTokenTest {

    @Before
    fun setUp() {
        RainConfig.reset()
    }

    @After
    fun tearDown() {
        RainConfig.reset()
    }

    // ---- guards: not initialized -------------------------------------------------

    @Test
    fun `sendNativeToken throws SdkNotInitialized before initialization`() {
        val manager = RainSdkManager()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking {
                manager.sendNativeToken(
                    chainId = 1,
                    toAddress = TestFixtures.RECIPIENT_ADDRESS,
                    amount = 1.0
                )
            }
        }
    }

    @Test
    fun `sendToken throws SdkNotInitialized before initialization`() {
        val manager = RainSdkManager()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking {
                manager.sendToken(
                    chainId = 1,
                    contractAddress = TestFixtures.TOKEN_ADDRESS,
                    toAddress = TestFixtures.RECIPIENT_ADDRESS,
                    amount = 100.0
                )
            }
        }
    }

    // ---- happy paths via the stub provider ---------------------------------------

    @Test
    fun `sendNativeToken returns provider tx hash and forwards toAddress + amount`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        val expectedHash = "0x" + "a".repeat(64)
        stub.sendNativeTokenHashToReturn = expectedHash

        val result = manager.sendNativeToken(
            chainId = 1,
            toAddress = TestFixtures.RECIPIENT_ADDRESS,
            amount = 1.5
        )

        assertThat(result.transactionHash).isEqualTo(expectedHash)
        assertThat(stub.sendNativeTokenCalls).hasSize(1)
        val call = stub.sendNativeTokenCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.toAddress).isEqualTo(TestFixtures.RECIPIENT_ADDRESS)
        assertThat(call.amount).isEqualTo(1.5)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated sendToken(Int) overload forwards explicit decimals to the provider`(): Unit = runBlocking {
        // Locks in binary/source back-compat: callers compiled against the old `decimals: Int`
        // signature still work, delegating to the nullable version with the given decimals.
        val (manager, stub) = TestManagers.stubProviderManager()
        val expectedHash = "0x" + "b".repeat(64)
        stub.sendTokenHashToReturn = expectedHash

        val result = manager.sendToken(
            chainId = 1,
            contractAddress = TestFixtures.TOKEN_ADDRESS,
            toAddress = TestFixtures.RECIPIENT_ADDRESS,
            amount = 100.0,
            decimals = 6
        )

        assertThat(result.transactionHash).isEqualTo(expectedHash)
        assertThat(stub.sendTokenCalls).hasSize(1)
        val call = stub.sendTokenCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.contractAddress).isEqualTo(TestFixtures.TOKEN_ADDRESS)
        assertThat(call.toAddress).isEqualTo(TestFixtures.RECIPIENT_ADDRESS)
        assertThat(call.amount).isEqualTo(100.0)
        assertThat(call.decimals).isEqualTo(6)
    }

    @Test
    fun `sendToken resolves decimals from the token store when caller omits them`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        // Unknown token enriches on-chain via the chain reader → decimals(8).
        val store = TokenMetadataStore(MockChainReader(decimals = 8, symbol = "WBTC"))
        manager.setTokenStoreForTest(store)

        manager.sendToken(
            chainId = 1,
            contractAddress = TestFixtures.TOKEN_ADDRESS,
            toAddress = TestFixtures.RECIPIENT_ADDRESS,
            amount = 1.0,
            decimals = null
        )

        assertThat(stub.sendTokenCalls.single().decimals).isEqualTo(8)
    }

    @Test
    fun `sendToken falls back to default decimals when no token store is available`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        // No token store installed.

        manager.sendToken(
            chainId = 1,
            contractAddress = TestFixtures.TOKEN_ADDRESS,
            toAddress = TestFixtures.RECIPIENT_ADDRESS,
            amount = 1.0,
            decimals = null
        )

        assertThat(stub.sendTokenCalls.single().decimals)
            .isEqualTo(com.rain.sdk.interfaces.RainClient.DEFAULT_ERC20_DECIMALS)
    }

    // ---- error wrapping ----------------------------------------------------------

    @Test
    fun `sendNativeToken wraps generic provider exception as ProviderError`() {
        assumeJdk24()
        val failing = object : StubWalletProvider() {
            override suspend fun sendNativeToken(
                chainId: Int,
                toAddress: String,
                amountInEth: Double
            ): String {
                throw RuntimeException("rpc 503")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)

        val ex = runCatching {
            runBlocking {
                manager.sendNativeToken(
                    chainId = 1,
                    toAddress = TestFixtures.RECIPIENT_ADDRESS,
                    amount = 1.0
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `sendToken surfaces RainError unchanged when the provider already mapped it`() {
        val failing = object : StubWalletProvider() {
            override suspend fun sendToken(
                chainId: Int,
                contractAddress: String,
                toAddress: String,
                amount: Double,
                decimals: Int
            ): String {
                throw RainError.InsufficientFunds()
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)
        assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking {
                manager.sendToken(
                    chainId = 1,
                    contractAddress = TestFixtures.TOKEN_ADDRESS,
                    toAddress = TestFixtures.RECIPIENT_ADDRESS,
                    amount = 100.0
                )
            }
        }
    }
}
