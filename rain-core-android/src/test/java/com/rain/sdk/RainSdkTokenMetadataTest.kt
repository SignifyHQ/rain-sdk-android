package com.rain.sdk

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.network.chainreader.ERC20Selectors
import com.rain.sdk.models.TokenInfo
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * [RainSdk.tokenMetadata] and [RainSdk.registerTokens]: the provider-free metadata seam a host uses
 * for tokens it knows only by address. No provider is registered in any test here.
 */
class RainSdkTokenMetadataTest {

    private lateinit var rpc: MockRpcServer

    // Outside the built-in registry on every chain, so a hit can only come from a registration.
    private val hostToken = "0x00000000000000000000000000000000000000AA"

    // A base58 SPL mint; no Solana chain has registry entries.
    private val solanaMint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"

    // Ethereum mainnet, deliberately absent from the endpoints below.
    private val unconfiguredChain = 1

    @Before
    fun setUp() {
        // RainSdk validates RPC URLs via the Android URLUtil static; stub it.
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        rpc.shutdown()
        unmockkAll()
    }

    /** An SDK built for Base Sepolia and Solana devnet, with the given seed tokens and no provider. */
    private fun sdk(vararg seed: TokenInfo): RainSdk = RainSdk.builder()
        .rpcEndpoints(
            mapOf(
                RainChain.BASE_SEPOLIA to rpc.urlFor(RainChain.BASE_SEPOLIA),
                RainChain.SOLANA_DEVNET to rpc.urlFor(RainChain.SOLANA_DEVNET)
            )
        )
        .registerTokens(seed.toList())
        .build()

    // ---- tokenMetadata ----------------------------------------------------------------

    @Test
    fun `tokenMetadata answers for a builder-registered token with no provider registered`() = runBlocking {
        val registered = TokenInfo(RainChain.BASE_SEPOLIA, hostToken, "TST", 8, "Test")
        val rain = sdk(registered)

        assertThat(rain.tokenMetadata(RainChain.BASE_SEPOLIA, hostToken.lowercase())).isEqualTo(registered)
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `tokenMetadata throws InvalidConfig for a chain the SDK was not built with`() {
        val rain = sdk()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { rain.tokenMetadata(unconfiguredChain, hostToken) }
        }

        assertThat(error).hasMessageThat().contains("No RPC endpoint configured for chainId=1")
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `tokenMetadata throws InvalidConfig for a malformed EVM address`() {
        val rain = sdk()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { rain.tokenMetadata(RainChain.BASE_SEPOLIA, "0x1234") }
        }

        assertThat(error).hasMessageThat().contains("token address for chainId=84532")
        assertThat(error).hasMessageThat().contains("0x1234")
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `tokenMetadata returns null for an unknown token whose chain read fails`() = runBlocking {
        rpc.stubNetworkFailure("eth_call")
        val rain = sdk()

        assertThat(rain.tokenMetadata(RainChain.BASE_SEPOLIA, hostToken)).isNull()
        assertThat(rpc.recordedMethods).contains("eth_call")
    }

    // ---- registerTokens ---------------------------------------------------------------

    @Test
    fun `registerTokens after build makes tokenMetadata answer for a Solana mint`() = runBlocking {
        val rain = sdk()
        val mint = TokenInfo(RainChain.SOLANA_DEVNET, solanaMint, "USDC", 6, "USD Coin")
        assertThat(rain.tokenMetadata(RainChain.SOLANA_DEVNET, solanaMint)).isNull()

        rain.registerTokens(listOf(mint))

        assertThat(rain.tokenMetadata(RainChain.SOLANA_DEVNET, solanaMint)).isEqualTo(mint)
        // Solana resolves from registrations only: nothing went to the node either time.
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `registerTokens with a malformed EVM address throws InvalidConfig and registers nothing`() {
        rpc.stubNetworkFailure("eth_call")
        val rain = sdk()
        val valid = TokenInfo(RainChain.BASE_SEPOLIA, hostToken, "TST", 8, "Test")
        val malformed = TokenInfo(RainChain.BASE_SEPOLIA, "0x1234", "BAD", 18, null)

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { rain.registerTokens(listOf(valid, malformed)) }
        }

        assertThat(error).hasMessageThat().contains("token address for chainId=84532")
        // The valid entry was not registered either: the lookup reaches the chain and finds nothing.
        assertThat(runBlocking { rain.tokenMetadata(RainChain.BASE_SEPOLIA, hostToken) }).isNull()
        assertThat(rpc.recordedMethods).contains("eth_call")
    }

    @Test
    fun `registerTokens rejects decimals outside the supported range`() {
        rpc.stubNetworkFailure("eth_call")
        val rain = sdk()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                rain.registerTokens(listOf(TokenInfo(RainChain.BASE_SEPOLIA, hostToken, "BAD", 78, null)))
            }
        }

        assertThat(error).hasMessageThat().contains("Invalid token decimals for chainId=84532")
        assertThat(runBlocking { rain.tokenMetadata(RainChain.BASE_SEPOLIA, hostToken) }).isNull()
    }

    // ---- address strictness (EIP-55 on EVM, 32-byte base58 on Solana) ------------------

    @Test
    fun `tokenMetadata rejects a mixed-case EVM address with a wrong checksum`() {
        val rain = sdk()
        // Same bytes as hostToken; the trailing "Aa" is a mixed-case spelling that fails EIP-55.
        val wrongChecksum = "0x00000000000000000000000000000000000000Aa"

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { rain.tokenMetadata(RainChain.BASE_SEPOLIA, wrongChecksum) }
        }

        assertThat(error).hasMessageThat().contains("checksum")
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `registerTokens rejects a Solana mint that is not 32 bytes of base58`() {
        val rain = sdk()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                rain.registerTokens(listOf(TokenInfo(RainChain.SOLANA_DEVNET, "not-base58!", "BAD", 6, null)))
            }
        }

        assertThat(error).hasMessageThat().contains("token mint for chainId=901")
        assertThat(runBlocking { rain.tokenMetadata(RainChain.SOLANA_DEVNET, solanaMint) }).isNull()
    }

    @Test
    fun `tokenMetadata rejects a Solana mint that is not 32 bytes of base58`() {
        val rain = sdk()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { rain.tokenMetadata(RainChain.SOLANA_DEVNET, "abc") }
        }
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `registerTokens accepts decimals 0 and 77`() = runBlocking {
        val rain = sdk()
        val zero = TokenInfo(RainChain.BASE_SEPOLIA, hostToken, "ZERO", 0, null)
        val max = TokenInfo(RainChain.BASE_SEPOLIA, "0x00000000000000000000000000000000000000BB", "MAX", 77, null)

        rain.registerTokens(listOf(zero, max))

        assertThat(rain.tokenMetadata(RainChain.BASE_SEPOLIA, zero.address)?.decimals).isEqualTo(0)
        assertThat(rain.tokenMetadata(RainChain.BASE_SEPOLIA, max.address)?.decimals).isEqualTo(77)
    }

    // ---- builder seeds ------------------------------------------------------------------

    @Test
    fun `build rejects a seed token with a malformed address`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to rpc.urlFor(RainChain.BASE_SEPOLIA)))
                .registerTokens(listOf(TokenInfo(RainChain.BASE_SEPOLIA, "0x1234", "BAD", 18, null)))
                .build()
        }
        assertThat(error).hasMessageThat().contains("0x1234")
    }

    @Test
    fun `build rejects a seed token with decimals outside the supported range`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to rpc.urlFor(RainChain.BASE_SEPOLIA)))
                .registerTokens(listOf(TokenInfo(RainChain.BASE_SEPOLIA, hostToken, "BAD", 78, null)))
                .build()
        }
    }

    // ---- the chain read itself ----------------------------------------------------------

    @Test
    fun `tokenMetadata resolves an unknown token from the chain`() = runBlocking {
        // Each ERC-20 read is stubbed by its selector: decimals() = 6, symbol() = "TST", name() = "Test".
        rpc.stubObjectWhenBodyContains("eth_call", ERC20Selectors.DECIMALS, "0x" + "0".repeat(62) + "06")
        rpc.stubObjectWhenBodyContains("eth_call", ERC20Selectors.SYMBOL, abiString("TST"))
        rpc.stubObjectWhenBodyContains("eth_call", ERC20Selectors.NAME, abiString("Test"))
        val rain = sdk()

        val info = rain.tokenMetadata(RainChain.BASE_SEPOLIA, hostToken)

        assertThat(info).isEqualTo(TokenInfo(RainChain.BASE_SEPOLIA, hostToken, "TST", 6, "Test"))
        assertThat(rpc.recordedMethods.count { it == "eth_call" }).isEqualTo(3)
    }

    /** ABI encoding of a dynamic `string` return value: offset, length, then the UTF-8 bytes right-padded. */
    private fun abiString(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val data = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }.padEnd(64, '0')
        return "0x" + "0".repeat(62) + "20" + bytes.size.toString(16).padStart(64, '0') + data
    }
}
