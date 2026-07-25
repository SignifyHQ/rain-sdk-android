package com.rain.sdk.internal.core

import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.internal.solana.SolanaCollateralWithdrawComposer
import com.rain.sdk.internal.solana.SolanaRpcClient
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
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
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
import java.math.BigDecimal
import java.math.BigInteger
import android.graphics.Bitmap
import com.rain.sdk.utils.QRGenerator

/**
 * Vendor-free implementation of [RainClient], bound to a single, already-resolved
 * [WalletProvider].
 *
 * Construction now happens through [com.rain.sdk.RainSdk] / a [com.rain.sdk.provider.RainProvider]
 * adapter, which materializes the provider (Portal, Turnkey, or a host-supplied one) and hands the
 * built `WalletProvider` here. The manager itself imports no provider SDK — it only orchestrates
 * Rain domain logic (CST auth, collateral flows, balances, tx) against the port.
 *
 * @param walletProvider The resolved provider this client routes every operation through.
 * @param rpcEndpoints The chains the SDK was configured with; used by [getAllBalances] to fan out.
 * @param tokenStore Shared metadata store used to resolve ERC-20 decimals when callers omit them.
 *                   May be null for providers that do their own metadata resolution.
 */
internal class RainSdkManager(
  private val walletProvider: WalletProvider,
  rpcEndpoints: Map<Int, String>,
  private val tokenStore: TokenMetadataStore? = null,
  private val errorMapper: ErrorMapper = ErrorMapper()
) : RainClient {

  /** Chains the SDK was initialized with; [getAllBalances] fans out across them. */
  private val configuredChainIds: List<Int> = rpcEndpoints.keys.toList()

  private val configuredRpcEndpoints: Map<Int, String> = rpcEndpoints.toMap()

  /** Composes Solana collateral withdrawals; the provider only signs the result. */
  private val solanaWithdrawComposer by lazy {
    SolanaCollateralWithdrawComposer(
      solanaRpcClient = SolanaRpcClient(),
      rpcUrlResolver = configuredRpcEndpoints::get
    )
  }

  /**
   * Host-registered tokens applied to the live store. Thread-safe because [registerTokens] is a
   * non-suspend public API callable from any thread.
   */
  private val registeredTokens = java.util.concurrent.CopyOnWriteArrayList<TokenInfo>()

  /** Fire-and-forget scope for applying late `registerTokens` calls to a live store. */
  private val tokenRegistrationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    get() = RainConfig.getInstance().isInitialized

  override val providerId: ProviderId
    get() = walletProvider.id

  override val capabilities: Set<Capability>
    get() = walletProvider.capabilities

  override suspend fun withdrawCollateral(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: BigDecimal,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?,
    autoSend: Boolean
  ): RainWithdrawResult {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    // Solana withdrawals go through Rain's on-chain collateral program rather than an EVM
    // coordinator contract: core composes the two-instruction transaction (ed25519 proof of
    // Rain's signature + the program's withdraw instruction) and the provider signs it.
    if (SolanaChains.isSolanaChain(chainId)) {
      val owner = walletProvider.getWalletAddress(chainId)
      val amountBaseUnits = try {
        amount.movePointRight(decimals).toBigIntegerExact()
      } catch (e: ArithmeticException) {
        throw RainError.InvalidAmount(
          amount.toPlainString(),
          "this token supports at most $decimals decimal places"
        )
      }
      val unsigned = solanaWithdrawComposer.composeWithdraw(
        chainId = chainId,
        ownerAddress = owner,
        collateralAddress = addresses.proxyAddress,
        mintAddress = addresses.tokenAddress,
        recipientAddress = addresses.recipientAddress,
        amountBaseUnits = amountBaseUnits,
        adminSignature = adminSignature
      )
      if (!autoSend) {
        // Same contract as the EVM path: hand back the prepared (unsigned) transaction for
        // manual submission instead of broadcasting.
        return RainWithdrawResult(transactionHash = null, transactionData = unsigned.transactionHex)
      }
      val signature = walletProvider.sendSolanaTransaction(chainId, unsigned)
      return RainWithdrawResult(transactionHash = signature, transactionData = null)
    }

    val walletAddress = walletProvider.getWalletAddress()

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
  ): BigDecimal {
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
    amount: BigDecimal,
    decimals: Int,
    salt: String,
    signature: String,
    expiresAt: String
  ): BigDecimal = estimateWithdrawalFee(
    chainId = chainId,
    addresses = addresses,
    amount = amount,
    decimals = decimals,
    adminSignature = RainAdminSignature(salt = salt, signature = signature, expiresAt = expiresAt),
    nonce = null
  )

  @Deprecated(
    message = "Use the BigDecimal overload, which takes the withdrawal authorization as " +
        "salt / signature / expiresAt and returns an exact BigDecimal " +
        "instead of a lossy Double.",
    replaceWith = ReplaceWith(
      "estimateWithdrawalFee(chainId, addresses, amount.toBigDecimal(), decimals, " +
          "adminSignature.salt, adminSignature.signature, adminSignature.expiresAt)"
    )
  )
  override suspend fun estimateWithdrawalFee(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: Double,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?
  ): Double {
    if (!amount.isFinite()) {
      throw RainError.InvalidAmount(amount.toString(), "amount must be a finite number")
    }
    return estimateWithdrawalFee(
      chainId = chainId,
      addresses = addresses,
      amount = amount.toBigDecimal(),
      decimals = decimals,
      adminSignature = adminSignature,
      nonce = nonce
    ).toDouble()
  }

  /** Shared estimation path: validate, build EIP-712, sign, build calldata, `eth_estimateGas`. */
  private suspend fun estimateWithdrawalFee(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: BigDecimal,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?
  ): BigDecimal {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    val walletAddress = walletProvider.getWalletAddress()

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
    return try {
      walletProvider.getWalletAddress()
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
    return try {
      walletProvider.getWalletAddress(chainId)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get wallet address for chainId=$chainId")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun sendNative(
    chainId: Int,
    to: String,
    amount: BigDecimal
  ): RainTokenTransferResult {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    return try {
      val txHash = walletProvider.sendNativeToken(chainId, to, amount)
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
    amount: BigDecimal,
    decimals: Int?
  ): RainTokenTransferResult {
    if (!isInitialized) {
      throw RainError.SdkNotInitialized()
    }

    return try {
      // Resolve decimals when the caller doesn't supply them: the token store checks its
      // registry first and falls back to an on-chain `decimals()` read for unknown tokens.
      //
      // Skipped on Solana: the store enriches through the EVM reader, so an SPL mint would cost
      // three failing `eth_call`s against a Solana endpoint and then cache an 18-decimal entry
      // that is almost certainly wrong. The Solana send path reads the mint's own decimals.
      val resolvedDecimals = decimals
        ?: takeUnless { SolanaChains.isSolanaChain(chainId) }
          ?.let { tokenStore?.tokenInfo(chainId, contractAddress)?.decimals }
        ?: RainClient.DEFAULT_ERC20_DECIMALS
      val txHash = walletProvider.sendToken(chainId, contractAddress, toAddress, amount, resolvedDecimals)
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
    return try {
      walletProvider.getBalance(chainId, token)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get balance")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getTokenBalances(chainId: Int): List<Balance> {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    return try {
      walletProvider.getBalances(chainId)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get balances")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getAllBalances(): List<Balance> {
    if (!isInitialized) throw RainError.SdkNotInitialized()
    val chainIds = configuredChainIds
    if (chainIds.isEmpty()) return emptyList()

    // Fan out across every configured chain in parallel, flattened into one list. Each
    // Balance carries its own chainId. A chain that fails contributes no entries rather
    // than failing the whole call, so one bad RPC endpoint doesn't hide the others.
    return coroutineScope {
      chainIds.map { chainId ->
        async {
          runCatching { walletProvider.getBalances(chainId) }.getOrElse { e ->
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
    // Apply to the live store too. Fire-and-forget like iOS.
    tokenStore?.let { store ->
      tokenRegistrationScope.launch { store.register(tokens) }
    }
  }

  override fun reset() {
    registeredTokens.clear()
    // Clear the existing RainConfig instance rather than nulling the singleton —
    // `RainTransactionBuilderImpl` holds a captured reference and would otherwise see a stale
    // config after a subsequent re-initialization.
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
    return try {
      walletProvider.getTransactions(chainId, limit, offset, order)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to get transactions")
      throw errorMapper.mapTransactionError(e)
    }
  }
}
