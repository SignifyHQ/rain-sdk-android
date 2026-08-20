package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import java.math.BigDecimal
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
    }

    @After
    fun tearDown() {
    }

    // ---- guards: not initialized -------------------------------------------------



    // ---- happy paths via the stub provider ---------------------------------------

    @Test
    fun `sendNative returns provider tx hash and forwards recipient + amount`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        val expectedHash = "0x" + "a".repeat(64)
        stub.sendNativeTokenHashToReturn = expectedHash

        val result = manager.sendNative(
            chainId = 1,
            to = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("1.5")
        )

        assertThat(result.transactionHash).isEqualTo(expectedHash)
        assertThat(stub.sendNativeTokenCalls).hasSize(1)
        val call = stub.sendNativeTokenCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        // The recipient reaches the provider in checksummed form.
        assertThat(call.toAddress).isEqualTo(TestFixtures.RECIPIENT_ADDRESS_CHECKSUMMED)
        assertThat(call.amount).isEqualTo(1.5)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated sendNativeToken shim delegates to sendNative`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        val expectedHash = "0x" + "c".repeat(64)
        stub.sendNativeTokenHashToReturn = expectedHash

        val result = manager.sendNativeToken(
            chainId = 1,
            toAddress = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("0.5")
        )

        assertThat(result.transactionHash).isEqualTo(expectedHash)
        val call = stub.sendNativeTokenCalls.single()
        assertThat(call.toAddress).isEqualTo(TestFixtures.RECIPIENT_ADDRESS_CHECKSUMMED)
        assertThat(call.amount).isEqualTo(0.5)
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
            to = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("100.0"),
            decimals = 6
        )

        assertThat(result.transactionHash).isEqualTo(expectedHash)
        assertThat(stub.sendTokenCalls).hasSize(1)
        val call = stub.sendTokenCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.contractAddress).isEqualTo(TestFixtures.TOKEN_ADDRESS)
        assertThat(call.toAddress).isEqualTo(TestFixtures.RECIPIENT_ADDRESS_CHECKSUMMED)
        assertThat(call.amount).isEqualTo(100.0)
        assertThat(call.decimals).isEqualTo(6)
    }

    @Test
    fun `sendToken resolves decimals from the token store when caller omits them`(): Unit = runBlocking {
        // Unknown token enriches on-chain via the chain reader → decimals(8).
        val store = TokenMetadataStore(MockChainReader(decimals = 8, symbol = "WBTC"))
        val (manager, stub) = TestManagers.stubProviderManager(tokenStore = store)

        manager.sendToken(
            chainId = 1,
            contractAddress = TestFixtures.TOKEN_ADDRESS,
            to = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("1.0"),
            decimals = null
        )

        assertThat(stub.sendTokenCalls.single().decimals).isEqualTo(8)
    }

    @Test
    fun `sendToken skips the token store on solana chains`(): Unit = runBlocking {
        // The store enriches through the EVM chain reader, which cannot read an SPL mint: it
        // would spend three failing calls against a Solana endpoint and then cache an 18-decimal
        // entry. The Solana provider path reads the mint's own decimals instead.
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val (manager, stub) = TestManagers.stubProviderManager(tokenStore = TokenMetadataStore(reader))

        manager.sendToken(
            chainId = com.rain.sdk.RainChain.SOLANA_DEVNET,
            contractAddress = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
            to = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("1.0"),
            decimals = null
        )

        assertThat(reader.decimalsCalls).isEmpty()
        // An unspecified value resolves to 0 on Solana, not the EVM default. The
        // adapter must not scale with it; the mint's own decimals are read on chain.
        assertThat(stub.sendTokenCalls.single().decimals).isEqualTo(0)

        // EVM chains keep resolving through the store.
        manager.sendToken(
            chainId = 1,
            contractAddress = TestFixtures.TOKEN_ADDRESS,
            to = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("1.0"),
            decimals = null
        )
        assertThat(reader.decimalsCalls).hasSize(1)
        assertThat(stub.sendTokenCalls.last().decimals).isEqualTo(8)
    }

    @Test
    fun `sendToken falls back to default decimals when no token store is available`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        // No token store installed.

        manager.sendToken(
            chainId = 1,
            contractAddress = TestFixtures.TOKEN_ADDRESS,
            to = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("1.0"),
            decimals = null
        )

        assertThat(stub.sendTokenCalls.single().decimals)
            .isEqualTo(com.rain.sdk.interfaces.RainClient.DEFAULT_ERC20_DECIMALS)
    }

    @Test
    fun `sendToken passes a caller-supplied decimals through on solana`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()

        manager.sendToken(
            chainId = com.rain.sdk.RainChain.SOLANA_DEVNET,
            contractAddress = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
            to = TestFixtures.RECIPIENT_ADDRESS,
            amount = BigDecimal("1.0"),
            decimals = 6
        )

        assertThat(stub.sendTokenCalls.single().decimals).isEqualTo(6)
    }

    // ---- recipient validation ------------------------------------------------------

    @Test
    fun `sendNative rejects a truncated recipient before contacting the provider`() {
        // web3j's ABI types would left-zero-pad a short address into a wrong-but-legal
        // recipient, so a malformed one must never reach a provider.
        val (manager, stub) = TestManagers.stubProviderManager()

        val ex = assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking {
                manager.sendNative(chainId = 1, to = "0x7f5c764c", amount = BigDecimal("1.0"))
            }
        }

        assertThat(ex.address).isEqualTo("0x7f5c764c")
        assertThat(stub.sendNativeTokenCalls).isEmpty()
    }

    @Test
    fun `sendToken rejects a truncated recipient before contacting the provider`() {
        val (manager, stub) = TestManagers.stubProviderManager()

        assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking {
                manager.sendToken(
                    chainId = 1,
                    contractAddress = TestFixtures.TOKEN_ADDRESS,
                    to = "0x7f5c764c",
                    amount = BigDecimal("1.0"),
                    decimals = 6
                )
            }
        }
        assertThat(stub.sendTokenCalls).isEmpty()
    }

    @Test
    fun `sendNative passes a solana recipient through without EVM validation`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        val solanaRecipient = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"

        manager.sendNative(
            chainId = com.rain.sdk.RainChain.SOLANA_DEVNET,
            to = solanaRecipient,
            amount = BigDecimal("0.5")
        )

        assertThat(stub.sendNativeTokenCalls.single().toAddress).isEqualTo(solanaRecipient)
    }

    // ---- error wrapping ----------------------------------------------------------

    @Test
    fun `sendNative wraps generic provider exception as ProviderError`() {
        assumeJdk24()
        val failing = object : StubWalletProvider() {
            override suspend fun sendNativeToken(
                chainId: Int,
                toAddress: String,
                amountInEth: BigDecimal
            ): String {
                throw RuntimeException("rpc 503")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)

        val ex = runCatching {
            runBlocking {
                manager.sendNative(
                    chainId = 1,
                    to = TestFixtures.RECIPIENT_ADDRESS,
                    amount = BigDecimal("1.0")
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
                amount: BigDecimal,
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
                    to = TestFixtures.RECIPIENT_ADDRESS,
                    amount = BigDecimal("100.0")
                )
            }
        }
    }
}
