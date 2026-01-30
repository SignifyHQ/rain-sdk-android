package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates the complete transaction flow.
 * 
 * Coordinates between validator, transaction builder, signer, and executor
 * to handle the entire lifecycle of a transaction from validation to execution.
 */
internal class TransactionCoordinator(
    private val validator: TransactionValidator,
    private val signer: TransactionSigner,
    private val executor: TransactionExecutor
) {
    
    /**
     * Executes a withdraw collateral transaction.
     * 
     * Flow:
     * 1. Validate request parameters
     * 2. Build EIP-712 typed data message
     * 3. Sign the message
     * 4. Build transaction data
     * 5. Execute transaction
     * 
     * @param request The withdraw collateral request
     * @return The transaction hash
     * @throws RainError if any step fails
     */
    suspend fun executeWithdrawCollateral(
        request: WithdrawCollateralRequest
    ): String = withContext(Dispatchers.IO) {
        try {
            // Step 1: Validate
            validator.validateWithdrawRequest(request)
            
            // Step 2: Build EIP-712 message
            val (typedDataJson, _) = RainTransactionBuilderImpl.buildEIP712Message(
                chainId = request.chainId,
                collateralProxyAddress = request.collateralProxyAddress,
                walletAddress = request.walletAddress,
                tokenAddress = request.tokenAddress,
                amount = request.amount,
                decimals = request.decimals,
                recipientAddress = request.recipientAddress,
                nonce = request.nonce
            )
            
            // Step 3: Sign typed data
            val userSignature = signer.signTypedData(
                chainId = request.chainId,
                walletAddress = request.walletAddress,
                typedDataJson = typedDataJson
            )
            
            // Step 4: Build transaction data
            val transactionData = RainTransactionBuilderImpl.buildWithdrawTransactionData(
                proxyAddress = request.collateralProxyAddress,
                tokenAddress = request.tokenAddress,
                amount = request.amount,
                decimals = request.decimals,
                recipientAddress = request.recipientAddress,
                expiresAt = request.expiresAt,
                signatureData = userSignature,
                adminSalt = request.adminSalt,
                adminSignature = request.adminSignature
            )
            
            // Step 5: Execute transaction
            executor.sendTransaction(
                chainId = request.chainId,
                from = request.walletAddress,
                to = request.collateralProxyAddress,
                data = transactionData,
                value = "0x0"
            )
            
        } catch (e: RainError) {
            // Re-throw RainError as-is
            throw e
        } catch (e: Exception) {
            // Wrap unexpected errors
            throw RainError.InternalError("Withdraw collateral failed: ${e.message}", e)
        }
    }
}
