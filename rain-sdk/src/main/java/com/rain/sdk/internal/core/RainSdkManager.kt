package com.rain.sdk.internal.core

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.transaction.TransactionCoordinator
import com.rain.sdk.internal.transaction.TransactionExecutor
import com.rain.sdk.internal.transaction.TransactionSigner
import com.rain.sdk.internal.transaction.TransactionValidator
import com.rain.sdk.internal.transaction.WithdrawCollateralRequest
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import timber.log.Timber
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
      }

      // Mark SDK as initialized
      configManager.markInitialized()
      
      Timber.d("Rain SDK: Initialized successfully")
    } catch (e: RainError) {
      Timber.e(e, "Rain SDK: Initialization error")
      throw e
    } catch (e: Throwable) {
      Timber.e(e, "Rain SDK: Portal SDK error")
      throw RainError.ProviderError(e)
    }
  }

  override suspend fun withdrawCollateral(
    chainId: Int,
    collateralProxyAddress: String,
    tokenAddress: String,
    amount: Double,
    decimals: Int,
    recipientAddress: String,
    expiresAt: String,
    adminSalt: String,
    adminSignature: String,
    nonce: BigInteger?
  ): String {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    // Get wallet address from Portal
    val walletAddress = portalManager.getAddress()

    // Create request object
    val request = WithdrawCollateralRequest(
      chainId = chainId,
      collateralProxyAddress = collateralProxyAddress,
      tokenAddress = tokenAddress,
      amount = amount,
      decimals = decimals,
      recipientAddress = recipientAddress,
      expiresAt = expiresAt,
      adminSalt = adminSalt,
      adminSignature = adminSignature,
      walletAddress = walletAddress,
      nonce = nonce
    )

    // Delegate to coordinator
    return transactionCoordinator.executeWithdrawCollateral(request)
  }

  companion object {
    /**
     * Creates a TransactionCoordinator with all required dependencies.
     * Separated for testability and clean initialization.
     */
    private fun createTransactionCoordinator(portalManager: PortalManager): TransactionCoordinator {
      val errorMapper = ErrorMapper()
      
      return TransactionCoordinator(
        validator = TransactionValidator(),
        signer = TransactionSigner(portalManager, errorMapper),
        executor = TransactionExecutor(portalManager, errorMapper)
      )
    }
  }
}
