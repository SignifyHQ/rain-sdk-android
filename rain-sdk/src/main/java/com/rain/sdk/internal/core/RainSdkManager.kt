package com.rain.sdk.internal.core

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.transaction.TransactionCoordinator
import com.rain.sdk.internal.transaction.TransactionExecutor
import com.rain.sdk.internal.transaction.TransactionSigner
import com.rain.sdk.internal.transaction.TransactionValidator
import com.rain.sdk.internal.transaction.WithdrawCollateralRequest
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainTokenTransferResult
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainWithdrawResult
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.internal.provider.PortalWalletProvider
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.BackupConfigs
import io.portalhq.android.mpc.data.FeatureFlags
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import java.math.BigInteger

/**
 * Internal implementation of RainClient.
 * 
 * This class acts as a thin facade that delegates to specialized components:
 * - PortalManager: Manages Portal SDK interactions
 * - ConfigManager: Handles configuration and validation
 * - TransactionCoordinator: Orchestrates transaction flows
 * 
 * This architecture provides better separation of concerns, testability, and maintainability.
 */
internal class RainSdkManager(
  private val portalManager: PortalManager = PortalManager(),
  private val configManager: ConfigManager = ConfigManager(),
  private val transactionCoordinator: TransactionCoordinator = createTransactionCoordinator(portalManager)
) : RainClient {

  private var walletProvider: WalletProvider? = null

  override val isInitialized: Boolean
    get() = configManager.isInitialized

  override val portal: Portal
    get() = portalManager.getPortalInstance()

  override fun initializePortal(
    portalSessionToken: String,
    rpcEndpoints: Map<Int, String>,
    chainId: Int?,
  ) {
    try {
      // Validate and setup RPC endpoints
      val eip155RpcConfig = configManager.validateAndSetupRpcEndpoints(rpcEndpoints)

      // Determine legacy chain ID
      val legacyChainId = configManager.determineLegacyChainId(chainId, rpcEndpoints)

      // Initialize Portal instance if token is provided
      if (portalSessionToken.isNotEmpty()) {
        portalManager.initialize(
          apiKey = portalSessionToken,
          legacyEthChainId = legacyChainId,
          rpcConfig = eip155RpcConfig,
          featureFlags = FeatureFlags(isMultiBackupEnabled = true),
          autoApprove = true
        )

        // Initialize wallet provider - Default to Portal
        walletProvider = PortalWalletProvider(portalManager)
      }

      // Mark SDK as initialized
      configManager.markInitialized()

      Timber.d("Rain SDK: Initialized successfully")
    } catch (e: RainError) {
      Timber.e(e, "Rain SDK: Initialization error")
      throw e
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Portal SDK error")
      throw RainError.ProviderError(e)
    }
  }

  override suspend fun withdrawCollateral(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: Double,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?,
    autoSend: Boolean
  ): RainWithdrawResult {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    // Get wallet address from Portal
    val walletAddress = portalManager.getAddress()

    // Create request object
    val request = WithdrawCollateralRequest(
      chainId = chainId,
      addresses = addresses,
      amount = amount,
      decimals = decimals,
      adminSignature = adminSignature,
      walletAddress = walletAddress,
      nonce = nonce
    )

    // Delegate to coordinator with autoSend parameter
    val (txHash, txData) = transactionCoordinator.executeWithdrawCollateral(request, autoSend)
    
    return RainWithdrawResult(
      transactionHash = txHash,
      transactionData = txData
    )
  }

  override suspend fun estimateGas(
    chainId: Int,
    from: String,
    to: String,
    data: String
  ): Double {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    return transactionCoordinator.estimateGas(
      chainId = chainId,
      from = from,
      to = to,
      data = data
    )
  }

  override suspend fun getAddress(): String {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }
    return portalManager.getAddress()
  }

  override suspend fun sendNativeToken(
    chainId: Int,
    toAddress: String,
    amount: Double
  ): RainTokenTransferResult {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    val provider = walletProvider ?: throw RainError.SdkNotInitialized()

    return try {
      val txHash = provider.sendNativeToken(chainId, toAddress, amount)
      RainTokenTransferResult(transactionHash = txHash)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to send native token")
      throw RainError.ProviderError(e)
    }
  }

  override suspend fun sendToken(
    chainId: Int,
    contractAddress: String,
    toAddress: String,
    amount: Double,
    decimals: Int
  ): RainTokenTransferResult {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    val provider = walletProvider ?: throw RainError.SdkNotInitialized()

    return try {
      val txHash = provider.sendToken(chainId, contractAddress, toAddress, amount, decimals)
      RainTokenTransferResult(transactionHash = txHash)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to send ERC-20 token")
      throw RainError.ProviderError(e)
    }
  }

  override suspend fun getNativeBalance(chainId: Int): Double {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return provider.getNativeBalance(chainId)
  }

  override suspend fun getERC20Balance(chainId: Int, tokenAddress: String): Double? {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return provider.getERC20Balance(chainId, tokenAddress)
  }

  override suspend fun getERC20Balances(chainId: Int): Map<String, Double> {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return provider.getERC20Balances(chainId)
  }

  override suspend fun getBalances(chainId: Int): Map<String, Double> {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return provider.getBalances(chainId)
  }

  companion object {
    /**
     * Creates a TransactionCoordinator with all required dependencies.
     * Separated for testability and clean initialization.
     */
    private fun createTransactionCoordinator(portalManager: PortalManager): TransactionCoordinator {
      val errorMapper = ErrorMapper()
      
      return TransactionCoordinator(
        portalManager = portalManager,
        validator = TransactionValidator(),
        signer = TransactionSigner(portalManager, errorMapper),
        executor = TransactionExecutor(portalManager, errorMapper)
      )
    }
  }
}
