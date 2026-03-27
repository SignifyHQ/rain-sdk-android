package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.core.PortalManager

/**
 * Handles transaction signing operations.
 * 
 * Wraps Portal signing calls and maps signing errors to appropriate RainError types.
 */
internal class TransactionSigner(
    private val portalManager: PortalManager,
    private val errorMapper: ErrorMapper
) {
    
    /**
     * Signs EIP-712 typed data.
     * 
     * @param chainId The chain ID
     * @param walletAddress The wallet address to sign with
     * @param typedDataJson The EIP-712 typed data as JSON string
     * @return The signature as a hex string
     * @throws com.rain.sdk.internal.error.RainError if signing fails
     */
    suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        return try {
            portalManager.signTypedData(chainId, walletAddress, typedDataJson)
        } catch (e: Exception) {
            throw errorMapper.mapSigningError(e)
        }
    }
}
