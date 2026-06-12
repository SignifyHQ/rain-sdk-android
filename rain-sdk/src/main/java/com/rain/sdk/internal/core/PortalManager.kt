package com.rain.sdk.internal.core

import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.error.RainError
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.EthTransactionParam
import io.portalhq.android.utils.events.PortalEvents
import com.rain.sdk.utils.EthereumConverter
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import io.portalhq.android.api.data.GetTransactionsOrder
import io.portalhq.android.api.data.Transaction
import io.portalhq.android.api.data.ntfassetsbychain.TokenBalance
import io.portalhq.android.storage.mobile.PortalNamespace
import io.portalhq.android.provider.data.PortalRequestMethod
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal

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
   * @return Native token balance in Ether units
   */
  suspend fun getNativeBalance(chainId: Int): BigDecimal {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    return try {
      val response = portal.api.getAssets(eip155ChainId).getOrThrow()
      response.nativeBalance.balance.toBigDecimalOrNull() ?: BigDecimal.ZERO
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
  private suspend fun getNativeBalanceViaRpc(chainId: Int): BigDecimal {
    val portal = getPortalInstance()
    val walletAddress = getAddress()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    val result = portal.request(
      chainId = eip155ChainId,
      method = PortalRequestMethod.eth_getBalance,
      params = listOf(walletAddress, "latest")
    )
    val hex = EthereumConverter.convertPortalResultToHexString(result)
    return EthereumConverter.convertWeiHexToEthDecimal(hex)
  }

  /**
   * Gets the balance of a specific ERC20 token for the current wallet.
   *
   * @param chainId The numeric chain ID (e.g. 43114)
   * @param tokenAddress The ERC20 contract address
   * @param decimals Number of decimals the token uses
   * @return Token balance
   */
  suspend fun getERC20Balance(chainId: Int, tokenAddress: String, decimals: Int?): BigDecimal {
    val portal = getPortalInstance()
    val walletAddress = getAddress()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    val function = Function(
      "balanceOf",
      listOf(Address(walletAddress)),
      listOf(object : TypeReference<Uint256>() {})
    )
    val encodedFunction = FunctionEncoder.encode(function)

    val callParams = mapOf(
      "to" to tokenAddress,
      "data" to encodedFunction
    )

    return try {
      val result = portal.request(
        chainId = eip155ChainId,
        method = PortalRequestMethod.eth_call,
        params = listOf(callParams, "latest")
      )
      val hex = EthereumConverter.convertPortalResultToHexString(result)
      EthereumConverter.convertHexToDecimal(hex, decimals ?: RainClient.DEFAULT_ERC20_DECIMALS)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to get ERC20 balance via RPC for token=$tokenAddress chainId=$chainId")
      throw RainError.ProviderError(e)
    }
  }

  /**
   * Gets all ERC20 token balances for the current wallet.
   *
   * @param chainId Numerical chain ID
   * @return Map of contract address to balance
   */
  suspend fun getERC20Balances(chainId: Int): Map<String, BigDecimal> {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    return try {
      val response = portal.api.getAssets(eip155ChainId).getOrThrow()
      response.tokenBalances.mapNotNull { tokenBalance ->
        val tokenAddress = tokenBalance.tokenAddress()
        val balance = tokenBalance.balance.toBigDecimalOrNull()

        if (tokenAddress.isNullOrBlank() || balance == null) {
          null
        } else {
          tokenAddress to balance
        }
      }.toMap()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to get ERC20 balances for chainId=$chainId")
      throw RainError.ProviderError(e)
    }
  }

  private fun TokenBalance.tokenAddress(): String? = metadata["tokenAddress"] as? String

  /**
   * Gets the transaction history for the specified chain.
   *
   * @param chainId Numerical chain ID (e.g. 43114)
   * @param limit Optional maximum number of transactions to return
   * @param offset Optional number of transactions to skip for pagination
   * @param order Optional sort order (ASC or DESC)
   * @return RainTransactionResult containing the list of transactions
   */
  suspend fun getTransactions(
    chainId: Int,
    limit: Int? = null,
    offset: Int? = null,
    order: RainTransactionOrder? = null
  ): RainTransactionResult {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    return try {
      val portalOrder = when (order) {
        RainTransactionOrder.ASC -> GetTransactionsOrder.ASC
        RainTransactionOrder.DESC -> GetTransactionsOrder.DESC
        null -> null
      }

      val portalTransactions = portal.api.getTransactions(
        chainId = eip155ChainId,
        limit = limit,
        offset = offset,
        order = portalOrder
      ).getOrThrow()

      val rainTransactions = coroutineScope {
        portalTransactions.map { tx ->
          async {
            val resolvedValue = resolveTransactionValue(tx, portal, eip155ChainId)
            val resolvedSymbol = resolveTransactionSymbol(tx, portal, eip155ChainId)
            RainTransaction(
              hash = tx.hash,
              blockNumber = tx.blockNum,
              blockTimestamp = tx.metadata?.blockTimestamp,
              from = tx.from,
              to = tx.to,
              value = resolvedValue,
              gas = null,
              gasPrice = null,
              chainId = tx.chainId.toString(),
              symbol = resolvedSymbol,
              tokenAddress = tx.rawContract?.address,
              metadata = null
            )
          }
        }.awaitAll()
      }

      RainTransactionResult(transactions = rainTransactions)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to get transactions for chainId=$chainId")
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
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    val ethParam = mapOf(
      "from" to from,
      "to" to to,
      "data" to data,
      "value" to value
    )

    // Simulate the transaction first via eth_call to catch failures
    // (e.g. insufficient funds, contract reverts) — no balance fetch needed,
    // the node validates it for free.
    try {
      portal.request(
        chainId = eip155ChainId,
        method = PortalRequestMethod.eth_call,
        params = listOf(ethParam, "latest")
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Transaction simulation failed (eth_call)")
      throw RainError.TransactionSimulationFailed(e)
    }

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

    val result = portal.ethSendTransaction(eip155ChainId, params)
    val txHash = EthereumConverter.convertPortalResultToTransactionHash(result)

    return txHash
  }

  /**
   * Resolves a human-readable value for a transaction.
   * Prefers tx.value, falls back to rawContract hex value / decimal.
   * If rawContract.decimal is null, fetches it on-chain via ERC20 decimals().
   */
  private suspend fun resolveTransactionValue(
    tx: Transaction,
    portal: Portal,
    eip155ChainId: String
  ): String? {
    // If Portal already provides a parsed value, use it
    tx.value?.let { return it.toString() }

    // Fallback: parse rawContract hex value with its decimal
    val rawContract = tx.rawContract ?: return null
    val hexValue = rawContract.value ?: return null

    // Get decimal: from rawContract first, then on-chain call
    val decimal = rawContract.decimal?.toIntOrNull()
      ?: rawContract.address?.let { fetchErc20Decimals(portal, eip155ChainId, it) }
      ?: return null

    return try {
      EthereumConverter.convertHexToDecimal(hexValue, decimal).toPlainString()
    } catch (e: Exception) {
      Timber.w(e, "Rain SDK: Failed to parse rawContract value=$hexValue decimal=$decimal")
      null
    }
  }

  /**
   * Fetches ERC20 decimals from contract via eth_call.
   */
  private suspend fun fetchErc20Decimals(
    portal: Portal,
    eip155ChainId: String,
    contractAddress: String
  ): Int? {
    return try {
      val function = Function("decimals", emptyList(), listOf(object : TypeReference<Uint256>() {}))
      val encodedFunction = FunctionEncoder.encode(function)
      val callParams = mapOf("to" to contractAddress, "data" to encodedFunction)
      val result = portal.request(
        chainId = eip155ChainId,
        method = PortalRequestMethod.eth_call,
        params = listOf(callParams, "latest")
      )
      val hex = EthereumConverter.convertPortalResultToHexString(result)
      hex.removePrefix("0x").toBigInteger(16).toInt()
    } catch (e: Exception) {
      Timber.w(e, "Rain SDK: Failed to fetch decimals for contract=$contractAddress")
      null
    }
  }

  /**
   * Resolves the token symbol for a transaction.
   * If it's a native transfer (no rawContract), returns "AVAX".
   * Otherwise fetches the token symbol via eth_call.
   */
  private suspend fun resolveTransactionSymbol(
    tx: Transaction,
    portal: Portal,
    eip155ChainId: String
  ): String {
    val rawContract = tx.rawContract ?: return "AVAX"
    val contractAddress = rawContract.address ?: return "AVAX"
    return fetchErc20Symbol(portal, eip155ChainId, contractAddress) ?: "AVAX"
  }

  /**
   * Fetches ERC20 symbol from contract via eth_call.
   */
  private suspend fun fetchErc20Symbol(
    portal: Portal,
    eip155ChainId: String,
    contractAddress: String
  ): String? {
    return try {
      val function = Function("symbol", emptyList(), listOf(object : TypeReference<org.web3j.abi.datatypes.Utf8String>() {}))
      val encodedFunction = FunctionEncoder.encode(function)
      val callParams = mapOf("to" to contractAddress, "data" to encodedFunction)
      val result = portal.request(
        chainId = eip155ChainId,
        method = PortalRequestMethod.eth_call,
        params = listOf(callParams, "latest")
      )
      val hex = EthereumConverter.convertPortalResultToHexString(result)
      if (hex.length > 2) {
        val decoded = org.web3j.abi.FunctionReturnDecoder.decode(hex, function.outputParameters)
        if (decoded.isNotEmpty()) {
          (decoded[0] as org.web3j.abi.datatypes.Utf8String).value
        } else null
      } else {
        null
      }
    } catch (e: Exception) {
      Timber.w(e, "Rain SDK: Failed to fetch symbol for contract=$contractAddress")
      null
    }
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
