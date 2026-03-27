package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.core.PortalManager
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import com.rain.sdk.internal.error.RainError

/**
 * Handles transaction execution operations.
 * 
 * Executes transactions via Portal and maps execution errors to appropriate RainError types.
 */
internal class TransactionExecutor(
    private val portalManager: PortalManager,
    private val errorMapper: ErrorMapper
) {
    
    /**
     * Sends a transaction using Portal.
     * 
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The recipient address (contract address)
     * @param data The transaction data (encoded function call)
     * @param value The value to send (default "0x0")
     * @return The transaction hash
     * @throws RainError if transaction fails
     */
    suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String = "0x0"
    ): String {
        return try {
            val txHash = portalManager.sendTransaction(chainId, from, to, data, value)
            Timber.d("Rain SDK: Transaction submitted successfully. Hash: $txHash")
            txHash
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Rain SDK: Failed to send transaction")
            throw errorMapper.mapTransactionError(e)
        }
    }
}
