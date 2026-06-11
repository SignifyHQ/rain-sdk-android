package com.rain.sdk.internal.core

import com.rain.sdk.internal.config.RainConfig
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
import com.rain.sdk.models.RainTransactionParameters
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainWithdrawResult
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.provider.PortalWalletProvider
import com.rain.sdk.internal.provider.TurnkeyContextAdapter
import com.rain.sdk.internal.provider.TurnkeyContextProtocol
import com.rain.sdk.internal.provider.TurnkeyWalletProvider
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.turnkey.core.TurnkeyContext
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import android.graphics.Bitmap
import com.rain.sdk.utils.QRGenerator

/**
 * Internal implementation of [RainClient].
 *
 * This class acts as a thin facade that delegates to specialized components:
 * - PortalManager: Manages Portal SDK interactions
 * - ConfigManager: Handles configuration and validation
 * - TransactionCoordinator: Orchestrates transaction flows (routes through the active wallet provider)
 *
 * Wallet provider is registered at initialization time (Portal or Turnkey) and used for
 * sign + send + balance + history operations.
 */
internal class RainSdkManager(
  private val portalManager: PortalManager = PortalManager(),
  private val configManager: ConfigManager = ConfigManager(),
  private val errorMapper: ErrorMapper = ErrorMapper()
) : RainClient {

  /**
   * Hook for tests to inject a fake `TurnkeyContextProtocol` without touching the real
   * Turnkey singleton. Kept as a settable property (rather than a constructor default)
   * so that simply instantiating RainSdkManager doesn't transitively class-load the
   * Turnkey types — which would force JDK 24+ on test JVMs.
   */
  @Suppress("PrivatePropertyName")
  internal var turnkeyContextFactory: ((TurnkeyContext) -> TurnkeyContextProtocol)? = null

  private var walletProvider: WalletProvider? = null
  private var turnkeyContext: TurnkeyContext? = null

  /** Token metadata store shared with the active wallet provider. Null in wallet-agnostic mode. */
  private var tokenStore: TokenMetadataStore? = null

  /**
   * Host-registered tokens, retained so they re-seed the store on each (re)initialization.
   * Thread-safe because [registerTokens] is a non-suspend public API callable from any
   * thread while `initialize*` / [reset] read or clear it.
   */
  private val registeredTokens = java.util.concurrent.CopyOnWriteArrayList<TokenInfo>()

  /** Fire-and-forget scope for applying late `registerTokens` calls to a live store. */
  private val tokenRegistrationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  /**
   * Snapshot of the chain IDs the SDK was last initialized with. Populated from the
   * `rpcEndpoints` map in `initializePortal` / `initializeTurnkey` and used by
   * `getAllBalances` to fan out across every configured chain.
   */
  @Volatile
  private var configuredChainIds: List<Int> = emptyList()

  /**
   * Installs a custom [WalletProvider], overriding any provider previously registered via
   * [initializePortal] or [initializeTurnkey]. Public hook for hosts that want to bring
   * their own provider implementation (e.g. Coinbase, Privy, Dynamic, custom MPC).
   */
  override fun setWalletProvider(provider: WalletProvider?) {
    walletProvider = provider
  }

  /**
   * Installs a fake [WalletProvider] without going through `initializePortal` /
   * `initializeTurnkey`. Kept as a thin alias over [setWalletProvider] so existing tests
   * keep compiling; new code should call [setWalletProvider] directly.
   */
  @androidx.annotation.VisibleForTesting
  internal fun setWalletProviderForTest(provider: WalletProvider?) {
    setWalletProvider(provider)
  }

  /**
   * Test seam for installing a `configuredChainIds` snapshot without driving
   * `initializePortal` / `initializeTurnkey`. Used by `getAllBalances` to fan out over
   * the chains the SDK was initialized with.
   */
  @androidx.annotation.VisibleForTesting
  internal fun setConfiguredChainIdsForTest(chainIds: List<Int>) {
    configuredChainIds = chainIds
  }

  private val signer = TransactionSigner({ walletProvider }, errorMapper)
  private val executor = TransactionExecutor({ walletProvider }, errorMapper)
  private val transactionCoordinator: TransactionCoordinator =
    TransactionCoordinator(
      walletProvider = { walletProvider },
      validator = TransactionValidator(),
      signer = signer,
      executor = executor
    )

  override val isInitialized: Boolean
    get() = configManager.isInitialized

  override val portal: Portal
    get() = portalManager.getPortalInstance()

  override val turnkey: TurnkeyContext
    get() = turnkeyContext ?: throw RainError.SdkNotInitialized()

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

        // Initialize wallet provider - Default to Portal. The token store resolves
        // ERC-20 metadata (decimals / symbol) and enriches unknown tokens once via a
        // chain reader backed by the same RPC endpoints.
        val reader = EvmChainReader(rpcEndpoints = rpcEndpoints)
        val store = TokenMetadataStore(chainReader = reader, seedTokens = registeredTokens.toList())
        tokenStore = store
        walletProvider = PortalWalletProvider(portalManager, store)
        turnkeyContext = null
      }

      configuredChainIds = rpcEndpoints.keys.toList()

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

  override suspend fun initializeTurnkey(
    turnkey: TurnkeyContext,
    rpcEndpoints: Map<Int, String>,
    chainId: Int?,
    walletAddress: String?
  ) {
    try {
      // Validate and setup RPC endpoints (also writes them into RainConfig)
      configManager.validateAndSetupRpcEndpoints(rpcEndpoints)

      val context = turnkeyContextFactory?.invoke(turnkey) ?: TurnkeyContextAdapter(turnkey)
      val reader = EvmChainReader(rpcEndpoints = rpcEndpoints)
      val solanaReader = SolanaChainReader(rpcEndpoints = rpcEndpoints)
      val store = TokenMetadataStore(chainReader = reader, seedTokens = registeredTokens.toList())
      val provider = TurnkeyWalletProvider(
        turnkey = context,
        rpcEndpoints = rpcEndpoints,
        walletAddressOverride = walletAddress,
        chainReader = reader,
        solanaChainReader = solanaReader,
        tokenStore = store
      )

      // Probe — ensures Turnkey has an EVM wallet available before swapping the provider in.
      withContext(Dispatchers.IO) {
        provider.getWalletAddress()
      }

      walletProvider = provider
      turnkeyContext = turnkey
      tokenStore = store
      configuredChainIds = rpcEndpoints.keys.toList()
      // Turnkey path doesn't use Portal — make sure stale state is cleared.
      portalManager.destroy()

      configManager.markInitialized()
      Timber.d("Rain SDK: Initialized Turnkey wallet provider successfully")
    } catch (e: RainError) {
      Timber.e(e, "Rain SDK: Turnkey initialization error")
      throw e
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Turnkey initialization error")
      throw errorMapper.mapSigningError(e)
    }
  }

  override fun initialize(rpcEndpoints: Map<Int, String>) {
    try {
      configManager.validateAndSetupRpcEndpoints(rpcEndpoints)

      // Wallet-agnostic: no bundled provider; clear any prior one. Host installs via setWalletProvider.
      walletProvider = null
      turnkeyContext = null
      tokenStore = null
      portalManager.destroy()

      configuredChainIds = rpcEndpoints.keys.toList()
      configManager.markInitialized()

      Timber.d("Rain SDK: Initialized in wallet-agnostic mode with ${rpcEndpoints.size} network(s)")
    } catch (e: RainError) {
      Timber.e(e, "Rain SDK: Initialization error")
      throw e
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "Rain SDK: Initialization error")
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

    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    val walletAddress = provider.getWalletAddress()

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

  override suspend fun estimateWithdrawalFee(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: Double,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?
  ): Double {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    val walletAddress = provider.getWalletAddress()

    val request = WithdrawCollateralRequest(
      chainId = chainId,
      addresses = addresses,
      amount = amount,
      decimals = decimals,
      adminSignature = adminSignature,
      walletAddress = walletAddress,
      nonce = nonce
    )

    return transactionCoordinator.estimateWithdrawalFee(request)
  }

  override fun composeTransactionParameters(
    walletAddress: String,
    contractAddress: String,
    transactionData: String
  ): RainTransactionParameters {
    return RainTransactionParameters(
      from = walletAddress,
      to = contractAddress,
      value = "0x0",
      data = transactionData
    )
  }

  override suspend fun getWalletAddress(): String {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return try {
      provider.getWalletAddress()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get wallet address")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getWalletAddress(chainId: Int): String {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return try {
      provider.getWalletAddress(chainId)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get wallet address for chainId=$chainId")
      throw errorMapper.mapTransactionError(e)
    }
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
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to send native token")
      throw errorMapper.mapTransactionError(e)
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
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to send ERC-20 token")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getBalance(chainId: Int, token: Token): Balance {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return try {
      provider.getBalance(chainId, token)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get balance")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getTokenBalances(chainId: Int): List<Balance> {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return try {
      provider.getBalances(chainId)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get balances")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getAllBalances(): List<Balance> {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    val chainIds = configuredChainIds
    if (chainIds.isEmpty()) return emptyList()

    // Fan out across every configured chain in parallel, flattened into one list. Each
    // Balance carries its own chainId. A chain that fails contributes no entries rather
    // than failing the whole call, so one bad RPC endpoint doesn't hide the others.
    return coroutineScope {
      chainIds.map { chainId ->
        async {
          runCatching { provider.getBalances(chainId) }.getOrElse { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "Rain SDK: getAllBalances failed for chainId=$chainId")
            emptyList()
          }
        }
      }.awaitAll().flatten()
    }
  }

  override fun registerTokens(tokens: List<TokenInfo>) {
    if (tokens.isEmpty()) return
    registeredTokens.addAll(tokens)
    // Apply to the live store too. Fire-and-forget like iOS; the tokens are also retained
    // in `registeredTokens` so they re-seed the store on the next (re)initialization.
    tokenStore?.let { store ->
      tokenRegistrationScope.launch { store.register(tokens) }
    }
  }

  override fun reset() {
    walletProvider = null
    turnkeyContext = null
    tokenStore = null
    registeredTokens.clear()
    configuredChainIds = emptyList()
    portalManager.destroy()
    // Clear the existing RainConfig instance rather than nulling the singleton —
    // `ConfigManager` holds a captured reference and would otherwise see a stale config
    // after a subsequent re-initialization.
    RainConfig.getInstance().clear()
    Timber.d("Rain SDK: Reset SDK state")
  }

  override suspend fun generateAddressQRCode(address: String?, width: Int, height: Int): Bitmap {
    if (!isInitialized) throw RainError.SdkNotInitialized()

    val targetAddress = address ?: getWalletAddress()

    return withContext(Dispatchers.Default) {
        try {
            QRGenerator.generateQRCode(targetAddress, width, height)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Rain SDK: Failed to generate QR code")
            throw RainError.ProviderError(e)
        }
    }
  }

  override suspend fun getTransactions(
    chainId: Int,
    limit: Int?,
    offset: Int?,
    order: RainTransactionOrder?
  ): RainTransactionResult {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val provider = walletProvider ?: throw RainError.SdkNotInitialized()
    return try {
      provider.getTransactions(chainId, limit, offset, order)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get transactions")
      throw errorMapper.mapTransactionError(e)
    }
  }
}
