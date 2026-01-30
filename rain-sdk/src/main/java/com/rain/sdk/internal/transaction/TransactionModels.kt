package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.RainError
import java.math.BigInteger

/**
 * Request model for withdraw collateral operation.
 * 
 * Encapsulates all parameters needed for withdrawing collateral from a proxy contract.
 */
internal data class WithdrawCollateralRequest(
    val chainId: Int,
    val collateralProxyAddress: String,
    val tokenAddress: String,
    val amount: Double,
    val decimals: Int,
    val recipientAddress: String,
    val expiresAt: String,
    val adminSalt: String,
    val adminSignature: String,
    val walletAddress: String,
    val nonce: BigInteger?
)

/**
 * Result of a transaction operation.
 */
internal sealed class TransactionResult {
    /**
     * Transaction was successfully submitted.
     * @param txHash The transaction hash
     */
    data class Success(val txHash: String) : TransactionResult()
    
    /**
     * Transaction failed with an error.
     * @param error The error that occurred
     */
    data class Failure(val error: RainError) : TransactionResult()
}
