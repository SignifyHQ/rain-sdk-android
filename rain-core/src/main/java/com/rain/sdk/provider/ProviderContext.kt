package com.rain.sdk.provider

import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.tokenstore.TokenMetadataStore

/**
 * Vendor-free infrastructure that core builds once from the configured RPC endpoints and hands
 * to each [RainProvider] when it materializes its [com.rain.sdk.internal.provider.WalletProvider].
 *
 * Adapters use these shared pieces instead of constructing their own, so every provider sees one
 * token store and one RPC view. [tokenStore] is public because out-of-module adapters (e.g.
 * `rain-portal`) need it; the chain readers stay module-internal — only core-resident adapters
 * (e.g. Turnkey) consume them directly.
 */
class ProviderContext internal constructor(
    val rpcEndpoints: Map<Int, String>,
    val tokenStore: TokenMetadataStore,
    internal val evmChainReader: EvmChainReader,
    internal val solanaChainReader: SolanaChainReader,
)
