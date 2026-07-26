package com.rain.sdk.interfaces

import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainEIP712Message
import com.rain.sdk.models.RainWithdrawAddresses
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Wallet-agnostic withdrawal-building primitives. These need no resolved provider — only the
 * configured RPC endpoints — and are exposed directly on [com.rain.sdk.RainSdk].
 */
interface RainTransactionBuilder {

    /**
     * Reads the collateral's current admin nonce — the value [buildEIP712Message] binds when
     * `nonce` is omitted.
     */
    suspend fun getLatestNonce(
        chainId: Int,
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
        chainId: Int,
        proxyAddress: String,
        walletAddress: String
    ): Boolean?

    /**
     * Builds the EIP-712 message the wallet signs to authorize a withdrawal, along with the salt
     * bound into it. Pass a null [nonce] to read the collateral's current nonce on chain.
     */
    suspend fun buildEIP712Message(
        chainId: Int,
        walletAddress: String,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        nonce: BigInteger? = null,
    ): RainEIP712Message

    /**
     * ABI-encodes the `withdrawAsset` call for the collateral controller.
     *
     * Pure encoding — no RPC, so it needs no chain id.
     *
     * @param executorSignature Rain's authorization, from `RainSdk.fetchAdminSignature`.
     * @param walletSalt The salt from [RainEIP712Message.salt], unchanged.
     * @param walletSignature The wallet's hex signature over [RainEIP712Message.message].
     */
    fun buildWithdrawTransactionData(
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        executorSignature: RainAdminSignature,
        walletSalt: ByteArray,
        walletSignature: String
    ): String
}
