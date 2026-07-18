package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import kotlinx.coroutines.CancellationException

/**
 * Handles transaction signing operations.
 *
 * Delegates signing to the active [WalletProvider] (Portal or Turnkey) so the same
 * withdraw flow works regardless of which wallet provider is registered.
 * Maps signing errors to appropriate [RainError] types.
 */
internal class TransactionSigner(
    private val walletProvider: () -> WalletProvider?,
    private val errorMapper: ErrorMapper
) {

    /**
     * Signs EIP-712 typed data.
     *
     * @param chainId The chain ID
     * @param walletAddress The wallet address to sign with
     * @param typedDataJson The EIP-712 typed data as JSON string
     * @return The signature as a hex string
     * @throws RainError if signing fails
     */
    suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
        return try {
            provider.signTypedData(chainId, walletAddress, typedDataJson)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            throw errorMapper.mapSigningError(e)
        }
    }
}
