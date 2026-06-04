package com.rain.sdk.sample

import com.rain.sdk.RainChain

/**
 * The wallet/chain the sample app is currently operating on, selected via the Home-screen
 * dropdown. Bundles everything the feature screens need to be chain-agnostic: the SDK chain
 * ID, RPC URL, native symbol, address validation, and block-explorer links.
 *
 * The SDK is initialized with every entry's RPC endpoint at once (see [rpcEndpoints]); the
 * dropdown just switches which chain the screens read/send on. EVM chains share one Turnkey
 * (secp256k1) account; Solana uses the ed25519 account.
 */
enum class WalletChain(
    val displayName: String,
    val chainId: Int,
    val rpcUrl: String,
    val nativeSymbol: String,
    val isSolana: Boolean,
    /** Block-explorer name, e.g. for a "View on Snowtrace" button. */
    val explorerName: String,
    private val explorerTxPrefix: String,
    private val explorerAddressPrefix: String,
    private val explorerSuffix: String = ""
) {
    EVM(
        displayName = "EVM · Avalanche Fuji",
        chainId = RainChain.AVALANCHE_TESTNET,
        rpcUrl = "https://api.avax-test.network/ext/bc/C/rpc",
        nativeSymbol = "AVAX",
        isSolana = false,
        explorerName = "Snowtrace",
        explorerTxPrefix = "https://testnet.snowtrace.io/tx/",
        explorerAddressPrefix = "https://testnet.snowtrace.io/address/"
    ),
    BASE_SEPOLIA(
        displayName = "EVM · Base Sepolia",
        chainId = 84532, // Base Sepolia testnet (already in the SDK's Turnkey-supported chains)
        rpcUrl = "https://sepolia.base.org",
        nativeSymbol = "ETH",
        isSolana = false,
        explorerName = "Basescan",
        explorerTxPrefix = "https://sepolia.basescan.org/tx/",
        explorerAddressPrefix = "https://sepolia.basescan.org/address/"
    ),
    SOLANA(
        displayName = "Solana · Devnet",
        chainId = RainChain.SOLANA_DEVNET,
        rpcUrl = "https://api.devnet.solana.com",
        nativeSymbol = "SOL",
        isSolana = true,
        explorerName = "Solana Explorer",
        explorerTxPrefix = "https://explorer.solana.com/tx/",
        explorerAddressPrefix = "https://explorer.solana.com/address/",
        explorerSuffix = "?cluster=devnet"
    );

    /** Light client-side address sanity check (the SDK validates authoritatively). */
    fun isValidAddress(address: String): Boolean {
        if (address.isBlank()) return false
        return if (isSolana) {
            address.length in 32..44 && address.all { it in BASE58_ALPHABET }
        } else {
            address.startsWith("0x") &&
                address.length == 42 &&
                address.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }

    fun explorerTxUrl(hash: String): String = "$explorerTxPrefix$hash$explorerSuffix"

    fun explorerAddressUrl(address: String): String =
        "$explorerAddressPrefix$address$explorerSuffix"

    companion object {
        private const val BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** Every chain's RPC endpoint, for initializing the SDK with all chains at once. */
        val rpcEndpoints: Map<Int, String>
            get() = entries.associate { it.chainId to it.rpcUrl }
    }
}
