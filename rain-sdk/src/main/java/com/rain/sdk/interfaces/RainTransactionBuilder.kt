package com.rain.sdk.interfaces

import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import java.math.BigInteger

/**
 * Interface for Rain SDK Utility methods (Wallet-agnostic).
 */
interface RainTransactionBuilder {

    /**
     * Get the latest nonce for a given proxy address.
     */
    suspend fun getLatestNonce(
        rpcUrl: String,
        proxyAddress: String
    ): BigInteger

    /**
     * Build EIP-712 message for obtaining the admin signature.
     */
    suspend fun buildEIP712Message(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        walletAddress: String,
        amount: Double,
        decimals: Int,
        nonce: BigInteger? = null,
    ): Pair<String, ByteArray>

    /**
     * Builds the encoded transaction call data required to execute a withdrawal.
     */
    fun buildWithdrawTransactionData(
        addresses: RainWithdrawAddresses,
        amount: Double,
        decimals: Int,
        saltBytes: ByteArray,
        signatureData: String,
        adminSignature: RainAdminSignature
    ): String
}
