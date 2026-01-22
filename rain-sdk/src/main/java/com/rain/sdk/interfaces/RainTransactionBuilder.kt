package com.rain.sdk.interfaces

import java.math.BigInteger

/**
 * Interface for Rain SDK Utility methods (Wallet-agnostic).
 */
interface RainTransactionBuilder {

    /**
     * Get the latest nonce for a given proxy address.
     */
    suspend fun getWithdrawalNonce(
        rpcUrl: String,
        proxyAddress: String
    ): BigInteger

    /**
     * Build EIP-712 message for obtaining the admin signature.
     */
    suspend fun buildEIP712Message(
        chainId: Int,
        collateralProxyAddress: String,
        walletAddress: String,
        tokenAddress: String,
        amount: Double,
        decimals: Int,
        recipientAddress: String,
        nonce: BigInteger? = null,
        rpcUrl: String? = null
    ): Pair<String, String>

    /**
     * Builds the encoded transaction call data required to execute a withdrawal.
     */
    fun buildWithdrawTransactionData(
        proxyAddress: String,
        tokenAddress: String,
        amount: Double,
        decimals: Int,
        recipientAddress: String,
        expiresAt: String,
        signatureData: String,
        adminSalt: String,
        adminSignature: String
    ): String
}
