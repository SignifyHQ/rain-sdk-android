package com.rain.sdk.internal.core

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import io.portalhq.android.Portal
import io.portalhq.android.api.data.ntfassetsbychain.TokenBalance
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.EthTransactionParam
import io.portalhq.android.utils.events.PortalEvents
import com.rain.sdk.internal.provider.toHexString
import com.rain.sdk.internal.provider.toTransactionHash
import com.rain.sdk.utils.EthereumConverter
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
import java.math.BigInteger
import io.portalhq.android.api.data.GetTransactionsOrder
import io.portalhq.android.api.data.Transaction
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
      val address = portal.getAddress(PortalNamespace.EIP155)
      if (address.isNullOrEmpty()) {
        throw RainError.WalletUnavailable("Portal returned no EIP-155 wallet address")
      }
      address
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      throw RainError.ProviderError(e)
    }
  }

  /**
   * Fetches a single balance (native or a contract token) as a rich [Balance], preserving
   * exact base-unit precision.
   */
  suspend fun getBalance(
    chainId: Int,
    token: Token,
    tokenStore: TokenMetadataStore
  ): Balance = when (token) {
    is Token.Native -> fetchNativeBalance(chainId, tokenStore)
    is Token.Contract -> fetchContractBalance(chainId, token.address, tokenStore)
  }

  /**
   * Fetches all non-zero balances (native always included) for the current wallet on the
   * given network. Native via `eth_getBalance`; ERC-20s via Portal's `getAssets`, with raw
   * amounts reconstructed exactly and zero-balance contract tokens omitted.
   */
  suspend fun getBalances(
    chainId: Int,
    tokenStore: TokenMetadataStore
  ): List<Balance> {
    val native = fetchNativeBalance(chainId, tokenStore)
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    val tokenBalances = try {
      portal.api.getAssets(eip155ChainId).getOrThrow().tokenBalances
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Failed to get balances for chainId=$chainId")
      throw RainError.ProviderError(e)
    }

    val output = mutableListOf(native)
    for (entry in tokenBalances) {
      // Portal's TokenBalance exposes the contract address inside the untyped `metadata`
      // map under "tokenAddress" (iOS reads the same field via metadata.tokenAddress).
      val address = entry.metadata["tokenAddress"] as? String
      if (address.isNullOrEmpty()) continue
      val info = tokenStore.tokenInfo(chainId, address)
      val raw = reconstructRawAmount(entry, info.decimals)
      if (raw <= BigInteger.ZERO) continue
      output += Balance(
        token = Token.Contract(address),
        chainId = chainId,
        rawAmount = raw,
        decimals = info.decimals,
        symbol = info.symbol ?: entry.symbol,
        name = info.name ?: entry.name
      )
    }
    return output
  }

  /** Fetches the native balance via `eth_getBalance`, preserving exact wei precision. */
  private suspend fun fetchNativeBalance(chainId: Int, tokenStore: TokenMetadataStore): Balance {
    val portal = getPortalInstance()
    val walletAddress = getAddress()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"
    val result = portal.request(
      chainId = eip155ChainId,
      method = PortalRequestMethod.eth_getBalance,
      params = listOf(walletAddress, "latest")
    )
    val raw = EthereumConverter.parseHexToBigInteger(result.toHexString())
    val native = tokenStore.nativeCurrency(chainId)
    return Balance(
      token = Token.Native,
      chainId = chainId,
      rawAmount = raw,
      decimals = native.decimals,
      symbol = native.symbol,
      name = native.name
    )
  }

  /** Fetches a single ERC-20 balance via direct RPC `eth_call` (balanceOf), preserving exact precision. */
  private suspend fun fetchContractBalance(
    chainId: Int,
    address: String,
    tokenStore: TokenMetadataStore
  ): Balance {
    val portal = getPortalInstance()
    val walletAddress = getAddress()
    val info = tokenStore.tokenInfo(chainId, address)
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    val function = Function(
      "balanceOf",
      listOf(Address(walletAddress)),
      listOf(object : TypeReference<Uint256>() {})
    )
    val callParams = mapOf("to" to address, "data" to FunctionEncoder.encode(function))

    return try {
      val result = portal.request(
        chainId = eip155ChainId,
        method = PortalRequestMethod.eth_call,
        params = listOf(callParams, "latest")
      )
      val raw = EthereumConverter.parseHexToBigInteger(result.toHexString())
      Balance(
        token = Token.Contract(address),
        chainId = chainId,
        rawAmount = raw,
        decimals = info.decimals,
        symbol = info.symbol,
        name = info.name
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get ERC20 balance via RPC for token=$address chainId=$chainId")
      throw RainError.ProviderError(e)
    }
  }

  /**
   * Reconstructs the exact base-unit amount for a Portal asset entry: prefer the raw integer
   * string when present, else reconstruct from the formatted decimal balance.
   */
  private fun reconstructRawAmount(entry: TokenBalance, decimals: Int): BigInteger {
    val rawBalance = entry.rawBalance
    if (!rawBalance.isNullOrEmpty()) {
      runCatching { BigInteger(rawBalance) }.getOrNull()?.let { return it }
    }
    return EthereumConverter.decimalStringToBigInteger(entry.balance, decimals)
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
   * Estimates the total fee (gas limit * gas price) for a transaction, in the chain's native token.
   *
   * @param chainId The chain ID
   * @param from The sender address
   * @param to The target contract address
   * @param data Hex-encoded calldata (or "0x" / empty for plain transfers)
   * @param value Hex-encoded wei value
   * @return Estimated fee in the chain's native token (e.g. ETH/AVAX)
   */
  suspend fun estimateTransactionFee(
    chainId: Int,
    from: String,
    to: String,
    data: String,
    value: String = "0x0"
  ): Double {
    val portal = getPortalInstance()
    val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

    val ethParams = io.portalhq.android.provider.data.EthTransactionParam(
      from = from,
      to = to,
      gas = null,
      gasPrice = null,
      maxFeePerGas = null,
      maxPriorityFeePerGas = null,
      data = data,
      value = value,
      nonce = null
    )

    val (gasHex, gasPriceHex) = coroutineScope {
      val gasLimitDeferred = async {
        portal.request(
          chainId = eip155ChainId,
          method = PortalRequestMethod.eth_estimateGas,
          params = listOf(ethParams)
        )
      }
      val gasPriceDeferred = async {
        portal.request(
          chainId = eip155ChainId,
          method = PortalRequestMethod.eth_gasPrice,
          params = listOf()
        )
      }

      val gasHex = gasLimitDeferred.await().toHexString()
      val gasPriceHex = gasPriceDeferred.await().toHexString()
      Pair(gasHex, gasPriceHex)
    }

    val gasLimit = java.math.BigInteger(gasHex.removePrefix("0x"), 16)
    val gasPrice = java.math.BigInteger(gasPriceHex.removePrefix("0x"), 16)
    return EthereumConverter.convertWeiToEth(gasLimit.multiply(gasPrice))
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

    val result = portal.request(
      chainId = eip155ChainId,
      method = PortalRequestMethod.eth_sendTransaction,
      params = listOf(params)
    )
    return result.toTransactionHash()
  }

  /**
   * Resolves a human-readable value for a transaction.
   * Prefers tx.value (Double), falls back to rawContract hex value / decimal.
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
      EthereumConverter.convertHexToDouble(hexValue, decimal).toString()
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
      val hex = result.toHexString()
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
      val hex = result.toHexString()
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
