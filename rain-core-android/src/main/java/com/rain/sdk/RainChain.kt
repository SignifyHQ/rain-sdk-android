package com.rain.sdk

object RainChain {
    const val AVALANCHE_MAINNET = 43114
    const val AVALANCHE_TESTNET = 43113

    // Solana clusters. Solana has no EIP-155 numeric chain ID, so the SDK uses Rain's own
    // chain IDs (900 = mainnet-beta, 901 = devnet — the values the Rain issuing API returns in
    // collateral contracts and expects on withdrawal-signature requests) to keep Solana
    // addressable through the same `chainId: Int` surface as EVM chains. Rain assigns no ID to
    // the testnet cluster; 902 extends the scheme for SDK-internal use only.
    // CAIP-2 (genesis-hash based) and routing live in `internal.constants.SolanaChains`.
    const val SOLANA_MAINNET = 900
    const val SOLANA_TESTNET = 902
    const val SOLANA_DEVNET = 901
}
