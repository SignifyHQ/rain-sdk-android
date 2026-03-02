package com.rain.sdk.internal.provider

/**
 * Interface for abstracting wallet operations.
 * Allows the SDK to support multiple wallet providers (Portal, Magic, Web3Auth, etc.).
 */
internal interface WalletProvider {
    /**
     * Gets the current wallet address.
     */
    suspend fun getAddress(): String

    /**
     * Sends native token (e.g., AVAX).
     *
     * @param chainId The network ID (e.g., 43114 for Avalanche).
     * @param toAddress The recipient's wallet address.
     * @param amountInEth The amount of token to send (in Eth/Avax unit, not Wei).
     * @return The transaction hash of the transaction.
     */
    suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: Double
    ): String

    /**
     * Sends an ERC-20 token.
     *
     * @param chainId The network ID.
     * @param contractAddress The ERC-20 token contract address.
     * @param toAddress The recipient's wallet address.
     * @param amount The amount of token to send (in human-readable unit).
     * @param decimals The number of decimals the token uses.
     * @return The transaction hash.
     */
    suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: Double,
        decimals: Int
    ): String

    /**
     * Gets the native token balance.
     */
    suspend fun getNativeBalance(chainId: Int): Double

    /**
     * Gets the ERC-20 token balance.
     */
    suspend fun getERC20Balance(chainId: Int, tokenAddress: String): Double?

    /**
     * Gets all ERC-20 token balances.
     */
    suspend fun getERC20Balances(chainId: Int): Map<String, Double>

    /**
     * Gets both native and ERC-20 token balances.
     */
    suspend fun getBalances(chainId: Int): Map<String, Double>
}
