package com.rain.sdk.internal.transaction

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthCall
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture

/**
 * Tests for the transaction-building primitives behind `withdrawCollateral`: nonce read,
 * EIP-712 message generation, and withdraw calldata encoding. Drives
 * [RainTransactionBuilderImpl] directly rather than going through `RainSdkManager`, since
 * the builder is wallet-provider-agnostic.
 */
class TransactionBuildingTest {

    private lateinit var mockWeb3j: Web3j

    private val validAddresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    @Before
    fun setUp() {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true

        mockWeb3j = mockk(relaxed = true)
        RainTransactionBuilderImpl.web3jFactory = { _ -> mockWeb3j }

        RainConfig.reset()
        Web3jProvider.shutDownAll()
    }

    @After
    fun tearDown() {
        unmockkAll()
        RainConfig.reset()
        Web3jProvider.shutDownAll()
        RainTransactionBuilderImpl.resetFactory()
    }

    // ---- buildEIP712Message success cases ---------------------------------------

    @Test
    fun `buildEIP712Message succeeds with explicit nonce and 18 decimals`() = runBlocking {
        val (json, salt) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 1,
            addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.valueOf(42)
        )

        assertThat(json).contains("\"primaryType\": \"Withdraw\"")
        assertThat(json).contains("\"chainId\": 1")
        assertThat(json).contains("\"nonce\": \"42\"")
        // 100 * 10^18 = 100000000000000000000
        assertThat(json).contains("\"amount\": \"100000000000000000000\"")
        assertThat(salt).hasLength(32)
    }

    @Test
    fun `buildEIP712Message uses 6 decimals correctly (USDC-like)`() = runBlocking {
        val (json, _) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 1,
            addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("100.5"),
            decimals = 6,
            nonce = BigInteger.ONE
        )

        assertThat(json).contains("\"amount\": \"100500000\"")
    }

    @Test
    fun `buildEIP712Message handles zero amount`() = runBlocking {
        val (json, _) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 1,
            addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal.ZERO,
            decimals = 18,
            nonce = BigInteger.ZERO
        )

        assertThat(json).contains("\"amount\": \"0\"")
    }

    @Test
    fun `buildEIP712Message generates a different salt on each call`() = runBlocking {
        val (json1, salt1) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 1, addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("100.0"), decimals = 18, nonce = BigInteger.ONE
        )
        val (json2, salt2) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 1, addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("100.0"), decimals = 18, nonce = BigInteger.ONE
        )

        assertThat(salt1).isNotEqualTo(salt2)
        assertThat(json1).isNotEqualTo(json2)
    }

    @Test
    fun `buildEIP712Message respects different chain IDs`() = runBlocking {
        val (mainnet, _) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 1, addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("100.0"), decimals = 18, nonce = BigInteger.ONE
        )
        val (polygon, _) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 137, addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("100.0"), decimals = 18, nonce = BigInteger.ONE
        )

        assertThat(mainnet).contains("\"chainId\": 1")
        assertThat(polygon).contains("\"chainId\": 137")
    }

    // ---- buildEIP712Message nonce resolution ------------------------------------

    @Test
    fun `buildEIP712Message reads nonce from RPC when nonce param is null`() = runBlocking {
        RainConfig.getInstance().setRpcUrl(1, "https://rpc.example/test")

        val mockEthCall = mockk<Request<*, EthCall>>()
        val response = EthCall().apply {
            // BigInteger.TEN encoded as a uint256
            result = "0x" + "0".repeat(63) + "a"
        }
        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(response)

        val (json, _) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 1,
            addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("1.0"),
            decimals = 18,
            nonce = null
        )

        assertThat(json).contains("\"nonce\": \"10\"")
    }

    @Test
    fun `buildEIP712Message throws InvalidConfig when nonce is null and RPC not configured`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                RainTransactionBuilderImpl.buildEIP712Message(
                    chainId = 999,
                    addresses = validAddresses,
                    walletAddress = TestFixtures.WALLET_ADDRESS,
                    amount = BigDecimal("100.0"),
                    decimals = 18,
                    nonce = null
                )
            }
        }
    }

    @Test
    fun `buildEIP712Message uses explicit nonce even for unknown chainId (no RPC needed)`() = runBlocking {
        val (json, _) = RainTransactionBuilderImpl.buildEIP712Message(
            chainId = 999,
            addresses = validAddresses,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.ONE
        )
        assertThat(json).contains("\"chainId\": 999")
    }

    // ---- buildEIP712Message address validation ----------------------------------

    @Test
    fun `buildEIP712Message throws InvalidConfig for malformed proxy address`() {
        val bad = validAddresses.copy(proxyAddress = "not-an-address")
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                RainTransactionBuilderImpl.buildEIP712Message(
                    chainId = 1, addresses = bad,
                    walletAddress = TestFixtures.WALLET_ADDRESS,
                    amount = BigDecimal("100.0"), decimals = 18, nonce = BigInteger.ONE
                )
            }
        }
    }

    @Test
    fun `buildEIP712Message throws InvalidConfig for malformed recipient address`() {
        val bad = validAddresses.copy(recipientAddress = "invalid")
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                RainTransactionBuilderImpl.buildEIP712Message(
                    chainId = 1, addresses = bad,
                    walletAddress = TestFixtures.WALLET_ADDRESS,
                    amount = BigDecimal("100.0"), decimals = 18, nonce = BigInteger.ONE
                )
            }
        }
    }

    @Test
    fun `buildEIP712Message throws InvalidConfig for malformed wallet address`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                RainTransactionBuilderImpl.buildEIP712Message(
                    chainId = 1, addresses = validAddresses,
                    walletAddress = "0xnope",
                    amount = BigDecimal("100.0"), decimals = 18, nonce = BigInteger.ONE
                )
            }
        }
    }

    // ---- buildWithdrawTransactionData -------------------------------------------

    @Test
    fun `buildWithdrawTransactionData encodes 0x-prefixed hex calldata`() {
        val data = RainTransactionBuilderImpl.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            saltBytes = ByteArray(32) { 0x11.toByte() },
            signatureData = "0x" + "42".repeat(65),
            adminSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32) { 0xAA.toByte() }),
                signature = "0x" + "bb".repeat(65),
                expiresAt = "2025-01-01T00:00:00Z"
            )
        )

        assertThat(data).startsWith("0x")
        // ABI-encoded function selectors are 4 bytes (8 hex chars after "0x"), so data is much longer
        assertThat(data.length).isGreaterThan(10)
    }

    @Test
    fun `buildWithdrawTransactionData accepts ISO8601 timestamp`() {
        val instant = Instant.parse("2025-06-15T12:00:00Z")
        // Just verify it doesn't throw — the timestamp is parsed and embedded.
        val data = RainTransactionBuilderImpl.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("1.0"),
            decimals = 18,
            saltBytes = ByteArray(32) { 0x11.toByte() },
            signatureData = "0x" + "42".repeat(65),
            adminSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                signature = "0x" + "bb".repeat(65),
                expiresAt = instant.toString()
            )
        )
        assertThat(data).startsWith("0x")
    }

    @Test
    fun `buildWithdrawTransactionData throws InvalidConfig for malformed expiresAt`() {
        val ex = runCatching {
            RainTransactionBuilderImpl.buildWithdrawTransactionData(
                addresses = validAddresses,
                amount = BigDecimal("100.0"),
                decimals = 18,
                saltBytes = ByteArray(32),
                signatureData = "0x" + "42".repeat(65),
                adminSignature = RainAdminSignature(
                    salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                    signature = "0x" + "bb".repeat(65),
                    expiresAt = "not-a-timestamp"
                )
            )
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(RainError.InvalidConfig::class.java)
    }

    @Test
    fun `buildWithdrawTransactionData throws InvalidConfig for malformed proxy address`() {
        val bad = validAddresses.copy(proxyAddress = "bad")
        assertThrows(RainError.InvalidConfig::class.java) {
            RainTransactionBuilderImpl.buildWithdrawTransactionData(
                addresses = bad,
                amount = BigDecimal("100.0"),
                decimals = 18,
                saltBytes = ByteArray(32),
                signatureData = "0x" + "42".repeat(65),
                adminSignature = RainAdminSignature(
                    salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                    signature = "0x" + "bb".repeat(65),
                    expiresAt = "2025-01-01T00:00:00Z"
                )
            )
        }
    }

    @Test
    fun `buildWithdrawTransactionData accepts 6-decimal tokens (USDC-like)`() {
        val data = RainTransactionBuilderImpl.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("100.5"),
            decimals = 6,
            saltBytes = ByteArray(32) { 0x11.toByte() },
            signatureData = "0x" + "42".repeat(65),
            adminSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                signature = "0x" + "bb".repeat(65),
                expiresAt = "2025-01-01T00:00:00Z"
            )
        )
        assertThat(data).startsWith("0x")
    }
}
