package com.rain.sdk.provider

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore

/**
 * Vendor-free infrastructure that core builds once from the configured RPC endpoints and hands
 * to each [RainProvider] when it materializes its [com.rain.sdk.internal.provider.WalletProvider].
 *
 * Adapters use these shared pieces instead of constructing their own, so every provider sees one
 * token store and one RPC view. [tokenStore] and [solanaSupport] are public because every adapter
 * module needs them; the chain readers carry [com.rain.sdk.internal.RainAdapterApi] for the same
 * reason, which keeps them out of the contract with host apps.
 */
class ProviderContext @RainAdapterApi constructor(
    val rpcEndpoints: Map<Int, String>,
    val tokenStore: TokenMetadataStore,
    @property:RainAdapterApi val evmChainReader: EvmChainReader,
    val solanaSupport: SolanaSupport,
) {
    @RainAdapterApi
    val solanaChainReader: SolanaChainReader get() = solanaSupport.chainReader
}
