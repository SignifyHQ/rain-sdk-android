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
import com.rain.sdk.utils.EthereumConverter
import timber.log.Timber
import kotlinx.coroutines.CancellationException
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

  @Volatile
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
    val portal = getPortalInstance()

    return try {
      portal.getAddress(PortalNamespace.EIP155)
        ?: throw RainError.ProviderError(IllegalStateException("Portal returned null address"))
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw RainError.ProviderError(e)
    }
  }

  /**
   * Gets the native token balance for the current wallet.
   *
   * Consolidates API calls by using portal.api.getAssets.
   *
   * @param chainId The numeric chain ID (e.g. 43114)
   * @return Native token balance in Ether units (Double)
   */
  suspend fun getNativeBalance(chainId: Int): Double {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    return try {
      val response = portal.api.getAssets(eip155ChainId).getOrThrow()
      response.nativeBalance.balance.toDoubleOrNull() ?: 0.0
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.w(e, "Rain SDK: portal.api.getAssets failed for native balance, falling back to RPC for chainId=$chainId")
      
      // Fallback to RPC if getAssets fails (common on testnets or indexing delays)
      getNativeBalanceViaRpc(chainId)
    }
  }

  /**
   * Internal helper to fetch native balance via RPC as a fallback.
   */
  private suspend fun getNativeBalanceViaRpc(chainId: Int): Double {
    val portal = getPortalInstance()
    val walletAddress = getAddress()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"
    
    val result = portal.request(
      chainId = eip155ChainId,
      method = PortalRequestMethod.eth_getBalance,
      params = listOf(walletAddress, "latest")
    )
    val hex = EthereumConverter.convertPortalResultToHexString(result)
    return EthereumConverter.convertWeiHexToEth(hex)
  }

  /**
   * Gets the balance of a specific ERC20 token for the current wallet.
   *
   * @param chainId The numeric chain ID (e.g. 43114)
   * @param tokenAddress The ERC20 contract address
   * @return Token balance as a Double, or null if the token is not found
   */
  suspend fun getERC20Balance(chainId: Int, tokenAddress: String): Double? {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    return try {
      val response = portal.api.getAssets(eip155ChainId).getOrThrow()
      response.tokenBalances
        .find {
          //TODO: will update after clarify with portal team
//          val address = it.contractAddress
//          address?.equals(tokenAddress, ignoreCase = true) == true
          false
        }
        ?.balance?.toDoubleOrNull()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to get ERC20 balance for token=$tokenAddress chainId=$chainId")
      throw RainError.ProviderError(e)
    }
  }

  /**
   * Gets all ERC20 token balances for the current wallet.
   *
   * @param chainId Numerical chain ID
   * @return Map of contract address to balance
   */
  suspend fun getERC20Balances(chainId: Int): Map<String, Double> {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    return try {
      val response = portal.api.getAssets(eip155ChainId).getOrThrow()
      response.tokenBalances.associate {
        //TODO: will update after clarify with portal team
        (null ?: "") to (it.balance?.toDoubleOrNull() ?: 0.0)
//        (it.contractAddress ?: "") to (it.balance?.toDoubleOrNull() ?: 0.0)
      }.filterKeys { it.isNotEmpty() }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to get ERC20 balances for chainId=$chainId")
      throw RainError.ProviderError(e)
    }
  }

  /**
   * Gets all balances (native + ERC20) for the current wallet on the given network.
   *
   * @param chainId The numeric chain ID
   * @return Map of token contract address to balance (Double). Native token is stored under key "".
   */
  suspend fun getBalances(chainId: Int): Map<String, Double> {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    return try {
      val response = portal.api.getAssets(eip155ChainId).getOrThrow()
      
      val balances = response.tokenBalances.associate {
        //TODO: will update after clarify with portal team
        (null ?: "") to (it.balance?.toDoubleOrNull() ?: 0.0)
      }.toMutableMap()
      
      val nativeBalance = response.nativeBalance.balance.toDoubleOrNull() ?: 0.0
      balances[""] = nativeBalance
      
      balances.filterKeys { it.isNotEmpty() || it == "" }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to get combined balances for chainId=$chainId")
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
    val portal = getPortalInstance()

    return try {
      val response = portal.request(
        chainId = "${PortalNamespace.EIP155.value}:$chainId",
        method = PortalRequestMethod.eth_signTypedData_v4,
        params = listOf(walletAddress, typedDataJson)
      )
      response.result.toString()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
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
    val portal = getPortalInstance()

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
    val txHash = EthereumConverter.convertPortalResultToTransactionHash(result)

    return txHash
  }

  fun getPortalInstance(): Portal {
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
