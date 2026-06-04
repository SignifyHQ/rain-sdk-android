package com.rain.sdk.internal.provider

import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token

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
     * Gets the wallet address for a specific chain. EVM chains share one address; a provider
     * that also holds non-EVM accounts (e.g. Turnkey with a Solana account) returns the
     * address matching [chainId]'s family.
     *
     * Defaults to [getAddress] so EVM-only providers (e.g. Portal) need no change.
     */
    suspend fun getAddress(chainId: Int): String = getAddress()

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
     * Fetches a single balance (native or a contract token) as a rich [Balance].
     *
     * @param chainId The target blockchain network identifier.
     * @param token [Token.Native] or a [Token.Contract].
     * @return A [Balance] with exact `rawAmount` plus resolved decimals / symbol / name.
     */
    suspend fun getBalance(chainId: Int, token: Token): Balance

    /**
     * Fetches all non-zero balances for the current wallet on the given network.
     *
     * @param chainId The target blockchain network identifier.
     * @return One [Balance] per non-zero token plus the native balance (always included).
     */
    suspend fun getBalances(chainId: Int): List<Balance>

    /**
     * Gets the transaction history for the specified chain.
     */
    suspend fun getTransactions(
        chainId: Int,
        limit: Int? = null,
        offset: Int? = null,
        order: RainTransactionOrder? = null
    ): RainTransactionResult

    /**
     * Signs EIP-712 typed data with the provider's wallet.
     *
     * @param chainId The chain ID
     * @param walletAddress The wallet address to sign with
     * @param typedDataJson The EIP-712 typed data as JSON string
     * @return The signature as a hex string (0x-prefixed)
     */
    suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String

    /**
     * Sends a low-level transaction with the provider's wallet.
     * Used by flows like withdraw-collateral that prepare their own calldata.
     *
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The target contract address
     * @param data Hex-encoded calldata (or "0x" / empty for plain transfers)
     * @param value Hex-encoded wei value (e.g. "0x0")
     * @return The transaction hash
     */
    suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String

    /**
     * Estimates the total fee (in the chain's native token, e.g. ETH) for a transaction.
     *
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The target contract address
     * @param data Hex-encoded calldata (or "0x" / empty for plain transfers)
     * @param value Hex-encoded wei value (e.g. "0x0")
     * @return Estimated fee in the chain's native token
     */
    suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): Double
}
