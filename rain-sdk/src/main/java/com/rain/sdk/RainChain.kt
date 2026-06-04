package com.rain.sdk

object RainChain {
    const val AVALANCHE_MAINNET = 43114
    const val AVALANCHE_TESTNET = 43113

    // Solana clusters. Solana has no EIP-155 numeric chain ID, so the SDK uses the
    // wallet-adapter sentinel convention (101 = mainnet-beta, 102 = testnet, 103 = devnet)
    // to keep Solana addressable through the same `chainId: Int` surface as EVM chains.
    // CAIP-2 (genesis-hash based) and routing live in `internal.constants.SolanaChains`.
    const val SOLANA_MAINNET = 101
    const val SOLANA_TESTNET = 102
    const val SOLANA_DEVNET = 103
}
