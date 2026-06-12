package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.core.PortalManager
import com.rain.sdk.internal.transaction.WithdrawCollateralRequest
import com.rain.sdk.utils.EthereumConverter
import io.portalhq.android.provider.data.EthTransactionParam
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Orchestrates the complete transaction flow.
 *
 * Coordinates between validator, transaction builder, signer, and executor
 * to handle the entire lifecycle of a transaction from validation to execution.
 */
internal class TransactionCoordinator(
  private val portalManager: PortalManager,
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
   * 5. Execute transaction (if autoSend=true) or return transaction data (if autoSend=false)
   *
   * @param request The withdraw collateral request
   * @param autoSend If true, sends the transaction and returns hash. If false, returns transaction data only.
   * @return Pair of (transactionHash, transactionData) where one will be null depending on autoSend
   * @throws RainError if any step fails
   */
  suspend fun executeWithdrawCollateral(
    request: WithdrawCollateralRequest,
    autoSend: Boolean = true
  ): Pair<String?, String?> = withContext(Dispatchers.IO) {
    try {
      // Step 1: Validate
      validator.validateWithdrawRequest(request)

      // Step 2: Build EIP-712 message
      val (typedDataJson, saltBytes) = RainTransactionBuilderImpl.buildEIP712Message(
        chainId = request.chainId,
        addresses = request.addresses,
        walletAddress = request.walletAddress,
        amount = request.amount,
        decimals = request.decimals,
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
        addresses = request.addresses,
        amount = request.amount,
        decimals = request.decimals,
        saltBytes = saltBytes,
        signatureData = userSignature,
        adminSignature = request.adminSignature
      )

      // Step 5: Execute transaction or return data
      if (autoSend) {
        val txHash = executor.sendTransaction(
          chainId = request.chainId,
          from = request.walletAddress,
          to = request.addresses.controllerAddress,
          data = transactionData,
          value = "0x0"
        )
        Pair(txHash, null)
      } else {
        Pair(null, transactionData)
      }

    } catch (e: RainError) {
      // Re-throw RainError as-is
      throw e
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      // Wrap unexpected errors
      throw RainError.InternalError("Withdraw collateral failed: ${e.message}", e)
    }
  }

  /**
   * Estimates gas for any transaction.
   *
   * @param chainId The chain ID
   * @param from Sender address
   * @param to Target contract address
   * @param data Transaction data (hex-encoded)
   * @return Estimated gas fee in ETH
   */
  suspend fun estimateGas(
    chainId: Int,
    from: String,
    to: String,
    data: String
  ): BigDecimal = withContext(Dispatchers.IO) {
    try {
      val portal = portalManager.getPortalInstance()
      
      val ethParams = EthTransactionParam(
        from = from,
        to = to,
        gas = null,
        gasPrice = null,
        maxFeePerGas = null,
        maxPriorityFeePerGas = null,
        data = data,
        value = "0x0",
        nonce = null
      )

      val chainIdString = "${PortalNamespace.EIP155.value}:$chainId"
      
      val (gasHex, gasPriceHex) = coroutineScope {
        val gasLimitDeferred = async { portal.ethEstimateGas(chainIdString, ethParams) }
        val gasPriceDeferred = async { portal.ethGasPrice(chainIdString) }
        
        val gasLimitResult = gasLimitDeferred.await()
        val gasPriceResult = gasPriceDeferred.await()
        
        val gasHex = EthereumConverter.convertPortalResultToHexString(gasLimitResult)
        val gasPriceHex = EthereumConverter.convertPortalResultToHexString(gasPriceResult)
        
        Pair(gasHex, gasPriceHex)
      }
      
      // Fee = gasLimit * gasPrice
      val gasLimit = BigInteger(gasHex.removePrefix("0x"), 16)
      val gasPrice = BigInteger(gasPriceHex.removePrefix("0x"), 16)
      
      val feeWei = gasLimit.multiply(gasPrice)
      
      // Convert Wei to ETH using the canonical decimal helper.
      EthereumConverter.convertWeiToEthDecimal(feeWei)

    } catch (e: RainError) {
      throw e
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw RainError.InternalError("Gas estimation failed: ${e.message}", e)
    }
  }
}
