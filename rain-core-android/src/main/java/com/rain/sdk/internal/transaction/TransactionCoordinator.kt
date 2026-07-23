package com.rain.sdk.internal.transaction

import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Orchestrates the complete transaction flow.
 *
 * Coordinates between validator, transaction builder, signer, and executor — all of which
 * route through the active [WalletProvider] (Portal or Turnkey) — to handle the entire
 * lifecycle of a transaction from validation to execution.
 */
internal class TransactionCoordinator(
  private val walletProvider: () -> WalletProvider?,
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
   * 3. Sign the message via the active wallet provider
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
      requireCollateralAdmin(request)

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

    } catch (e: RainError.TransactionSimulationFailed) {
      // A simulation failure on the withdrawal path means the node reverted the withdrawal
      // itself (duplicate withdrawal, already-used signature, contract guard tripped).
      throw RainError.WithdrawalRevertedByNetwork(cause = e)
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
   * Throws [RainError.WalletNotAuthorized] when the signing wallet is not a collateral admin.
   * Only a definitive `false` blocks — an unknown result proceeds, so a check that cannot run
   * never blocks a withdrawal.
   */
  private suspend fun requireCollateralAdmin(request: WithdrawCollateralRequest) {
    val rpcUrl = RainConfig.getInstance().getRpcUrl(request.chainId) ?: return
    val isAdmin = RainTransactionBuilderImpl.isCollateralAdmin(
      rpcUrl = rpcUrl,
      proxyAddress = request.addresses.proxyAddress,
      walletAddress = request.walletAddress
    )
    if (isAdmin == false) {
      throw RainError.WalletNotAuthorized(
        walletAddress = request.walletAddress,
        proxyAddress = request.addresses.proxyAddress
      )
    }
  }

  /**
   * Estimates gas for any transaction using the active wallet provider.
   *
   * @param chainId The chain ID
   * @param from Sender address
   * @param to Target contract address
   * @param data Transaction data (hex-encoded)
   * @return Estimated gas fee in the chain's native token (e.g. ETH/AVAX)
   */
  suspend fun estimateGas(
    chainId: Int,
    from: String,
    to: String,
    data: String
  ): BigDecimal = withContext(Dispatchers.IO) {
    val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
    try {
      provider.estimateTransactionFee(
        chainId = chainId,
        from = from,
        to = to,
        data = data,
        value = "0x0"
      )
    } catch (e: RainError) {
      throw e
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw RainError.InternalError("Gas estimation failed: ${e.message}", e)
    }
  }

  /**
   * Estimates the total fee (in the chain's native token) required to execute a collateral
   * withdrawal transaction.
   *
   * Reuses steps 1–4 of [executeWithdrawCollateral] (validate → build EIP-712 → sign → build
   * calldata) and then asks the active wallet provider to estimate gas for that calldata
   * against the withdrawal controller.
   *
   * @param request The withdraw collateral request (same shape used by [executeWithdrawCollateral]).
   * @return Estimated withdrawal fee in the chain's native token (e.g. ETH/AVAX).
   * @throws RainError if any step fails.
   */
  suspend fun estimateWithdrawalFee(
    request: WithdrawCollateralRequest
  ): BigDecimal = withContext(Dispatchers.IO) {
    try {
      // Step 1: Validate
      validator.validateWithdrawRequest(request)
      requireCollateralAdmin(request)

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

      // Step 5 (estimate instead of send): ask the provider for the fee.
      val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
      provider.estimateTransactionFee(
        chainId = request.chainId,
        from = request.walletAddress,
        to = request.addresses.controllerAddress,
        data = transactionData,
        value = "0x0"
      )
    } catch (e: RainError.TransactionSimulationFailed) {
      // The fee estimate simulates the withdrawal; a revert here means the network would
      // reject the withdrawal itself (duplicate, used signature, contract guard tripped).
      throw RainError.WithdrawalRevertedByNetwork(cause = e)
    } catch (e: RainError) {
      throw e
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw RainError.InternalError("Estimate withdrawal fee failed: ${e.message}", e)
    }
  }
}
