package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Handles transaction execution operations.
 *
 * Delegates submission to the active [WalletProvider] (Portal or Turnkey) so the same
 * withdraw flow works regardless of which wallet provider is registered.
 * Maps execution errors to appropriate [RainError] types.
 */
internal class TransactionExecutor(
    private val walletProvider: () -> WalletProvider?,
    private val errorMapper: ErrorMapper
) {

    /**
     * Sends a transaction using the active wallet provider.
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
        val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
        return try {
            val txHash = provider.sendTransaction(chainId, from, to, data, value)
            Timber.d("Rain SDK: Transaction submitted successfully. Hash: $txHash")
            txHash
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Failed to send transaction")
            throw errorMapper.mapTransactionError(e)
        }
    }
}
