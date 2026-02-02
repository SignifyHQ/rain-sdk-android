package com.rain.sdk.internal.core

import com.rain.sdk.internal.error.RainError
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.BackupConfigs
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.EthTransactionParam
import io.portalhq.android.provider.data.PortalProviderResult
import io.portalhq.android.utils.events.PortalEvents
import io.portalhq.android.provider.data.PortalRequestMethod
import io.portalhq.android.storage.mobile.PortalNamespace
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wrapper around Portal SDK to encapsulate all Portal interactions.
 *
 * Provides a clean API for signing and sending transactions through Portal,
 * and manages the Portal instance lifecycle.
 */
internal class PortalManager {

  private var _portal: Portal? = null

  /**
   * Checks if Portal has been initialized.
   */
  val isInitialized: Boolean
    get() = _portal != null

  private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  /**
   * Initializes the Portal instance with provided configuration.
   *
   * @param apiKey Portal API key (session token)
   * @param legacyEthChainId The default chain ID for legacy operations
   * @param rpcConfig Map of chain identifiers to RPC URLs
   * @param featureFlags Portal feature flags
   * @param backupConfigs Portal backup configuration (optional)
   * @param autoApprove Whether to auto-approve transactions
   */
  fun initialize(
    apiKey: String,
    legacyEthChainId: Int,
    rpcConfig: Map<String, String>,
    featureFlags: FeatureFlags,
    autoApprove: Boolean
  ) {
    destroy()

    scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val portal = createPortal(
      apiKey = apiKey,
      legacyEthChainId = legacyEthChainId,
      rpcConfig = rpcConfig,
      featureFlags = featureFlags,
      autoApprove = autoApprove
    )

    // Setup auto-signing handler matching InitPortalUseCase logic
    portal.on(PortalEvents.PortalSigningRequested) { data ->
      Timber.d("Rain SDK: Auto-approving signing request")
      if (scope.isActive) {
        scope.launch {
          portal.emit(PortalEvents.PortalSigningApproved, data)
        }
      }
    }

    _portal = portal
    Timber.d("Rain SDK: Portal initialized successfully with event handlers")
  }

  /**
   * Gets the wallet address for the specified namespace.
   *
   * @return The wallet address
   * @throws RainError.ProviderError if Portal is not initialized or fails to get address
   */
  suspend fun getAddress(): String {
    val portal = getPortalOrThrow()

    return try {
      portal.getAddress(PortalNamespace.EIP155)
        ?: throw RainError.ProviderError(IllegalStateException("Portal returned null address"))
    } catch (e: Exception) {
      throw RainError.ProviderError(e)
    }
  }

  /**
   * Signs typed data (EIP-712) using Portal.
   *
   * @param chainId The chain ID
   * @param walletAddress The wallet address to sign with
   * @param typedDataJson The EIP-712 typed data as JSON string
   * @return The signature as a hex string
   * @throws RainError.ProviderError if signing fails
   */
  suspend fun signTypedData(
    chainId: Int,
    walletAddress: String,
    typedDataJson: String
  ): String {
    val portal = getPortalOrThrow()

    return try {
      val response = portal.request(
        chainId = "${PortalNamespace.EIP155.value}:$chainId",
        method = PortalRequestMethod.eth_signTypedData_v4,
        params = listOf(walletAddress, typedDataJson)
      )
      response.result.toString()
    } catch (e: Exception) {
      Timber.e(e, "Rain SDK: Failed to sign typed data")
      throw e
    }
  }

  /**
   * Sends a transaction using Portal.
   *
   * @param chainId The chain ID
   * @param from The sender address
   * @param to The recipient address
   * @param data The transaction data (encoded function call)
   * @param value The value to send (default "0x0")
   * @return The transaction hash
   * @throws Exception if transaction fails
   */
  suspend fun sendTransaction(
    chainId: Int,
    from: String,
    to: String,
    data: String,
    value: String = "0x0"
  ): String {
    val portal = getPortalOrThrow()

    val params = EthTransactionParam(
      from = from,
      to = to,
      gas = null,
      gasPrice = null,
      maxFeePerGas = null,
      maxPriorityFeePerGas = null,
      value = value,
      data = data,
      nonce = null
    )

    val result = portal.ethSendTransaction("${PortalNamespace.EIP155.value}:$chainId", params)
    val txHash = convertPortalResultToTransactionHash(result)

    return txHash
  }

  fun convertPortalResultToTransactionHash(portalResult: Any): String {
    return (portalResult as? PortalProviderResult)?.result as String
  }

  /**
   * Gets the Portal instance.
   *
   * @return The Portal instance
   * @throws RainError.SdkNotInitialized if Portal is not initialized
   */
  fun getPortalInstance(): Portal {
    return _portal ?: throw RainError.SdkNotInitialized()
  }

  /**
   * Helper to get Portal instance or throw error.
   */
  private fun getPortalOrThrow(): Portal {
    return _portal ?: throw RainError.SdkNotInitialized()
  }

  /**
   * Factory method to create Portal instance.
   * Separated for testability (can be mocked).
   */
  internal fun createPortal(
    apiKey: String,
    legacyEthChainId: Int,
    rpcConfig: Map<String, String>,
    featureFlags: FeatureFlags,
    autoApprove: Boolean
  ): Portal {
    return Portal(
      apiKey = apiKey,
      legacyEthChainId = legacyEthChainId,
      rpcConfig = rpcConfig,
      featureFlags = featureFlags,
      autoApprove = autoApprove
    )
  }

  fun destroy() {
    scope.cancel()
    _portal = null
    Timber.d("Rain SDK: PortalManager destroyed and coroutines cancelled")
  }
}
