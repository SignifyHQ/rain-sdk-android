package com.rain.sdk.interfaces

import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import java.math.BigDecimal
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
     * Whether [walletAddress] is an admin of the collateral contract at [proxyAddress].
     *
     * @return the contract's answer, or `null` if the check could not be performed (RPC failure,
     *   or a collateral that exposes no `isAdmin`). Callers must treat `null` as unknown and
     *   proceed, never as "not authorized".
     */
    suspend fun isCollateralAdmin(
        rpcUrl: String,
        proxyAddress: String,
        walletAddress: String
    ): Boolean?

    /**
     * Build EIP-712 message for obtaining the admin signature.
     */
    suspend fun buildEIP712Message(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        walletAddress: String,
        amount: BigDecimal,
        decimals: Int,
        nonce: BigInteger? = null,
    ): Pair<String, ByteArray>

    /**
     * Builds the encoded transaction call data required to execute a withdrawal.
     */
    fun buildWithdrawTransactionData(
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        saltBytes: ByteArray,
        signatureData: String,
        adminSignature: RainAdminSignature
    ): String

}
