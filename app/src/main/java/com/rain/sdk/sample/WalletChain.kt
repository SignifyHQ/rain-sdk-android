package com.rain.sdk.sample

import com.rain.sdk.RainChain
import com.rain.sdk.models.TokenInfo

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
    /** What this chain calls a fungible token — used for labels on the send screen. */
    val tokenStandard: String,
    /** A token this chain is likely to have on testnet, pre-filled on the send screen. */
    val defaultTokenAddress: String,
    /** A sample recipient. Blank where there is no obvious throwaway address to suggest. */
    val defaultRecipient: String,
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
        tokenStandard = "ERC-20",
        defaultTokenAddress = "0x5425890298aed601595a70AB815c96711a31Bc65", // Fuji USDC
        defaultRecipient = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff",
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
        tokenStandard = "ERC-20",
        defaultTokenAddress = "0x036CbD53842c5426634e7929541eC2318f3dCF7e", // Base Sepolia USDC
        defaultRecipient = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff",
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
        tokenStandard = "SPL",
        defaultTokenAddress = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", // Devnet USDC mint
        // Left blank on purpose: an SPL transfer creates the recipient's token account at the
        // sender's expense, so the address is worth typing deliberately rather than defaulting.
        defaultRecipient = "",
        explorerTxPrefix = "https://explorer.solana.com/tx/",
        explorerAddressPrefix = "https://explorer.solana.com/address/",
        explorerSuffix = "?cluster=devnet"
    );

    /** What the token-address field holds on this chain: a contract on EVM, a mint on Solana. */
    val tokenAddressLabel: String get() = if (isSolana) "Token Mint Address" else "Token Contract Address"

    // Naming for [defaultTokenAddress], registered with the SDK at build time. An SPL mint has
    // no on-chain symbol and the built-in EVM registry is mainnet-only.
    val defaultTokenInfo: TokenInfo
        get() = TokenInfo(
            chainId,
            defaultTokenAddress,
            "USDC",
            6,
            if (isSolana) "USD Coin (devnet)" else "USDC"
        )

    /**
     * True when a Rain collateral contract on [contractChainId] belongs to this wallet. Solana
     * matches its exact cluster; EVM accepts any EVM contract because Rain deploys the user's
     * collateral on one EVM chain (Base Sepolia) regardless of which EVM chain is selected.
     */
    fun ownsCollateralContract(contractChainId: Int): Boolean =
        if (isSolana) contractChainId == chainId else contractChainId !in SOLANA_CHAIN_IDS

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

        /** Rain's Solana chain IDs, for classifying a collateral contract's chain family. */
        val SOLANA_CHAIN_IDS = setOf(
            RainChain.SOLANA_MAINNET, RainChain.SOLANA_TESTNET, RainChain.SOLANA_DEVNET
        )

        /** Every chain's RPC endpoint, for initializing the SDK with all chains at once. */
        val rpcEndpoints: Map<Int, String>
            get() = entries.associate { it.chainId to it.rpcUrl }
    }
}
