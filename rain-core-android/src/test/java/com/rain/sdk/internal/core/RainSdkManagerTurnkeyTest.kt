package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.network.Web3jProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/**
 * Integration coverage for the Turnkey path through `RainSdkManager` — builds a
 * [com.rain.sdk.turnkey.TurnkeyWalletProvider] over a mock TurnkeyContext, binds a manager to it,
 * and drives `withdrawCollateral` end-to-end to assert the EIP-712 payload is signed via
 * `signRawPayload` with the correct encoding.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled against major class version 68.
 *
 * IMPORTANT: this file deliberately avoids referencing any Turnkey type (or any of our own
 * types that have Turnkey in their signature, like `MockTurnkey`) in method or field signatures.
 * JUnit calls `Class.getDeclaredMethods()` during test discovery, which eagerly resolves every
 * method's parameter/return types — and that would trigger a cascading load of `TurnkeyContext`
 * on JDK 21, failing before `assumeTrue` runs. All Turnkey-touched values live inside test method
 * bodies and are typed as `Any` at the field level.
 */
class RainSdkManagerTurnkeyTest {

    private var sdkManager: Any? = null
    private var mockTurnkey: Any? = null

    @Before
    fun setUpAndGate() {
        assumeJdk24()

        RainConfig.reset()
        Web3jProvider.shutDownAll()

        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true

        // Build Turnkey-touching objects only after the JDK-24 gate has passed.
        val rpcEndpoints = mapOf(1 to "https://rpc.example/test")
        val tk = com.rain.sdk.turnkey.MockTurnkey()
        mockTurnkey = tk
        val provider = com.rain.sdk.turnkey.TurnkeyWalletProvider(
            turnkey = tk,
            rpcEndpoints = rpcEndpoints
        )
        sdkManager = RainSdkManager(
            walletProvider = provider,
            rpcEndpoints = rpcEndpoints
        ).also { RainConfig.getInstance().markInitialized() }
    }

    @After
    fun tearDown() {
        unmockkAll()
        RainConfig.reset()
        Web3jProvider.shutDownAll()
    }

    @Test
    fun `withdrawCollateral routes signing through Turnkey`() = runBlocking {
        val manager = sdkManager as RainSdkManager
        val turnkey = mockTurnkey as com.rain.sdk.turnkey.MockTurnkey

        val chainId = 1

        assertThat(manager.isInitialized).isTrue()
        assertThat(manager.getWalletAddress())
            .isEqualTo(com.rain.sdk.turnkey.MockTurnkey.DEFAULT_WALLET_ADDRESS)

        val addresses = com.rain.sdk.models.RainWithdrawAddresses(
            proxyAddress = "0x0000000000000000000000000000000000000001",
            controllerAddress = "0x0000000000000000000000000000000000000002",
            tokenAddress = "0x0000000000000000000000000000000000000003",
            recipientAddress = "0x0000000000000000000000000000000000000004"
        )
        val adminSignature = com.rain.sdk.models.RainAdminSignature(
            salt = Base64.getEncoder().encodeToString(ByteArray(32)),
            signature = "0x" + "01".repeat(65),
            expiresAt = "2025-12-31T23:59:59Z"
        )

        val result = manager.withdrawCollateral(
            chainId = chainId,
            addresses = addresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            adminSignature = adminSignature,
            nonce = BigInteger.valueOf(42), // explicit nonce, builder skips its RPC
            autoSend = false                 // skip broadcast — no real RPC needed
        )

        // Calldata returned; no broadcast happened.
        assertThat(result.transactionHash).isNull()
        assertThat(result.transactionData).isNotNull()
        assertThat(result.transactionData!!).startsWith("0x")

        // EIP-712 message went through Turnkey signRawPayload with the right encoding/hash.
        assertThat(turnkey.signRawPayloadCalls).hasSize(1)
        val signCall = turnkey.signRawPayloadCalls.single()
        assertThat(signCall.signWith)
            .isEqualTo(com.rain.sdk.turnkey.MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(signCall.encoding)
            .isEqualTo(com.turnkey.types.V1PayloadEncoding.PAYLOAD_ENCODING_EIP712)
        assertThat(signCall.hashFunction)
            .isEqualTo(com.turnkey.types.V1HashFunction.HASH_FUNCTION_NO_OP)
        assertThat(signCall.payload).isNotEmpty()
    }
}
