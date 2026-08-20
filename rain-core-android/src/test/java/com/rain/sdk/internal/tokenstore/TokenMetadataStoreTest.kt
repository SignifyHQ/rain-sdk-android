package com.rain.sdk.internal.tokenstore

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Registry-seeded lookups skip enrichment, unknown tokens enrich exactly once and cache, and
 * host-registered tokens are returned without an on-chain read.
 */
class TokenMetadataStoreTest {

    // Chain-1 registry USDC (checksummed in the registry; lookups are case-insensitive).
    private val usdcEthereum = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val unknown = "0xAbCdEf1234567890abcdef1234567890aBcDeF12"

    @Test
    fun `tokenInfo returns registry metadata without enrichment for a known token`() = runBlocking {
        val reader = MockChainReader(decimals = 99, symbol = "WRONG")
        val store = TokenMetadataStore(reader)

        val info = store.tokenInfo(chainId = 1, address = usdcEthereum)

        assertThat(info.symbol).isEqualTo("USDC")
        assertThat(info.decimals).isEqualTo(6)
        // Registry hit — no on-chain enrichment.
        assertThat(reader.decimalsCalls).isEmpty()
        assertThat(reader.symbolCalls).isEmpty()
    }

    @Test
    fun `tokenInfo enriches an unknown token once and caches the result`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC", name = "Wrapped BTC")
        val store = TokenMetadataStore(reader)

        val first = store.tokenInfo(chainId = 1, address = unknown)
        // Second lookup with a different-cased address must hit the cache.
        val second = store.tokenInfo(chainId = 1, address = unknown.lowercase())

        assertThat(first.decimals).isEqualTo(8)
        assertThat(first.symbol).isEqualTo("WBTC")
        assertThat(first.name).isEqualTo("Wrapped BTC")
        assertThat(second).isEqualTo(first)
        // Enriched exactly once despite two lookups.
        assertThat(reader.decimalsCalls).hasSize(1)
        assertThat(reader.symbolCalls).hasSize(1)
        assertThat(reader.nameCalls).hasSize(1)
    }

    @Test
    fun `tokenInfo enrichment tolerates a missing name`() = runBlocking {
        // name defaults to null on the mock — a failed/absent name() read must leave name null
        // without breaking decimals/symbol resolution.
        val reader = MockChainReader(decimals = 18, symbol = "FOO")
        val store = TokenMetadataStore(reader)

        val info = store.tokenInfo(chainId = 1, address = unknown)

        assertThat(info.symbol).isEqualTo("FOO")
        assertThat(info.decimals).isEqualTo(18)
        assertThat(info.name).isNull()
    }

    @Test
    fun `registered token is returned without enrichment`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val store = TokenMetadataStore(reader)
        store.register(
            listOf(TokenInfo(chainId = 1, address = unknown, symbol = "HOST", decimals = 12, name = "Host Token"))
        )

        val info = store.tokenInfo(chainId = 1, address = unknown)

        assertThat(info.symbol).isEqualTo("HOST")
        assertThat(info.decimals).isEqualTo(12)
        assertThat(reader.decimalsCalls).isEmpty()
        assertThat(reader.symbolCalls).isEmpty()
    }

    @Test
    fun `registeredTokens returns registry tokens plus host registrations in order`() = runBlocking {
        val store = TokenMetadataStore(MockChainReader())
        val host = TokenInfo(chainId = 1, address = unknown, symbol = "HOST", decimals = 12)
        store.register(listOf(host))

        val tokens = store.registeredTokens(chainId = 1)

        assertThat(tokens).contains(host)
        // Registry first, host registrations appended.
        assertThat(tokens.last()).isEqualTo(host)
    }

    @Test
    fun `seedTokens passed to the constructor are registered`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val seed = TokenInfo(chainId = 999, address = unknown, symbol = "SEED", decimals = 4)
        val store = TokenMetadataStore(reader, seedTokens = listOf(seed))

        val info = store.tokenInfo(chainId = 999, address = unknown)

        assertThat(info).isEqualTo(seed)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `nativeCurrency resolves from the registry`() {
        val store = TokenMetadataStore(MockChainReader())
        assertThat(store.nativeCurrency(43114).symbol).isEqualTo("AVAX")
        assertThat(store.nativeCurrency(1).symbol).isEqualTo("ETH")
        // Unknown chain falls back to an ETH-like default.
        assertThat(store.nativeCurrency(123456).symbol).isEqualTo("ETH")
    }

    @Test
    fun `a fallback decimals is not cached so a later lookup re-reads the chain`() = runBlocking {
        // A transient RPC failure must not pin the 18-decimals guess for the process lifetime:
        // caching it would misreport a 6-decimal token's balance by a factor of 10^12.
        val reader = MockChainReader(decimals = 6, symbol = "EURC")
        reader.metadataError = RuntimeException("rpc timeout")
        val store = TokenMetadataStore(reader)

        val failed = store.tokenInfo(chainId = 1, address = unknown)
        assertThat(failed.decimals).isEqualTo(18)

        reader.metadataError = null
        val retried = store.tokenInfo(chainId = 1, address = unknown)

        assertThat(retried.decimals).isEqualTo(6)
        assertThat(retried.symbol).isEqualTo("EURC")
        assertThat(reader.decimalsCalls).hasSize(2)

        // The successful read is cached — a third lookup issues no further RPC.
        store.tokenInfo(chainId = 1, address = unknown)
        assertThat(reader.decimalsCalls).hasSize(2)
    }

    @Test
    fun `nativeCurrencyOrNull returns null for an unknown chain instead of a default`() {
        val store = TokenMetadataStore(MockChainReader())
        assertThat(store.nativeCurrencyOrNull(43114)?.symbol).isEqualTo("AVAX")
        assertThat(store.nativeCurrencyOrNull(com.rain.sdk.RainChain.SOLANA_MAINNET)?.symbol)
            .isEqualTo("SOL")
        assertThat(store.nativeCurrencyOrNull(123456)).isNull()
    }
}
