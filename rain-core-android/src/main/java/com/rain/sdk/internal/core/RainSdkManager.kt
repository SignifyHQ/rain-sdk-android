package com.rain.sdk.internal.core

import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.internal.solana.SolanaCollateralWithdrawComposer
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.internal.solana.UnsignedSolanaTransfer
import com.rain.sdk.internal.transaction.TransactionCoordinator
import com.rain.sdk.internal.transaction.TransactionExecutor
import com.rain.sdk.internal.transaction.TransactionSigner
import com.rain.sdk.internal.transaction.TransactionValidator
import com.rain.sdk.internal.transaction.WithdrawCollateralRequest
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainTokenTransferResult
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransaction
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
 * @param providerId Identity the registered [com.rain.sdk.provider.RainProvider] descriptor
 *                   advertises. Sourced from the descriptor so `RainSdk.providers` and a resolved
 *                   client can never disagree; defaults to the wallet provider's own value.
 * @param capabilities Capabilities the descriptor advertises, for the same reason as [providerId].
 * @param transactionBuilder Withdrawal-building primitives bound to the same chain configuration.
 */
internal class RainSdkManager(
  private val walletProvider: WalletProvider,
  rpcEndpoints: Map<Int, String>,
  private val tokenStore: TokenMetadataStore? = null,
  private val errorMapper: ErrorMapper = ErrorMapper(),
  transactionBuilder: RainTransactionBuilder = RainTransactionBuilderImpl(rpcEndpoints),
  override val providerId: ProviderId = walletProvider.id,
  override val capabilities: Set<Capability> = walletProvider.capabilities
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

  private val validator = TransactionValidator()
  private val signer = TransactionSigner({ walletProvider }, errorMapper)
  private val executor = TransactionExecutor({ walletProvider }, errorMapper)
  private val transactionCoordinator: TransactionCoordinator =
    TransactionCoordinator(
      walletProvider = { walletProvider },
      transactionBuilder = transactionBuilder,
      validator = validator,
      signer = signer,
      executor = executor
    )

  /**
   * A `RainSdkManager` only exists once its provider resolved against a validated configuration,
   * so this is constant `true`.
   */
  override val isInitialized: Boolean get() = true

  override suspend fun withdrawCollateral(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: BigDecimal,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?
  ): String {
    if (SolanaChains.isSolanaChain(chainId)) {
      // Same error contract as the EVM path: simulation revert -> WithdrawalRevertedByNetwork,
      // and raw parsing/decoding exceptions never escape unmapped.
      return transactionCoordinator.withWithdrawalErrors("Withdraw collateral") {
        val unsigned = composeSolanaWithdrawal(chainId, addresses, amount, decimals, adminSignature)
        walletProvider.sendSolanaTransaction(chainId, unsigned)
      }
    }

    return transactionCoordinator.executeWithdrawCollateral(
      withdrawRequest(chainId, addresses, amount, decimals, adminSignature, nonce)
    )
  }

  override suspend fun prepareWithdrawal(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: BigDecimal,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?
  ): RainPreparedWithdrawal {
    if (SolanaChains.isSolanaChain(chainId)) {
      return transactionCoordinator.withWithdrawalErrors("Prepare withdrawal") {
        RainPreparedWithdrawal.Solana(
          composeSolanaWithdrawal(chainId, addresses, amount, decimals, adminSignature)
        )
      }
    }

    return RainPreparedWithdrawal.Evm(
      transactionCoordinator.prepareWithdrawCollateral(
        withdrawRequest(chainId, addresses, amount, decimals, adminSignature, nonce)
      )
    )
  }

  /**
   * Composes a Solana collateral withdrawal. The withdrawal is authorized by Rain's coordinator
   * executor signing a keccak message off chain, so core composes and the provider only signs.
   */
  private suspend fun composeSolanaWithdrawal(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: BigDecimal,
    decimals: Int,
    adminSignature: RainAdminSignature
  ): UnsignedSolanaTransfer {
    // The EVM path validates inside the coordinator; Solana composes here, so it validates here.
    validator.validateWithdrawRequest(chainId, amount, decimals)

    val owner = walletProvider.getWalletAddress(chainId)
    val amountBaseUnits = try {
      amount.movePointRight(decimals).toBigIntegerExact()
    } catch (e: ArithmeticException) {
      throw RainError.InvalidAmount(
        amount.toPlainString(),
        "this token supports at most $decimals decimal places"
      )
    }
    return solanaWithdrawComposer.composeWithdraw(
      chainId = chainId,
      ownerAddress = owner,
      collateralAddress = addresses.proxyAddress,
      mintAddress = addresses.tokenAddress,
      recipientAddress = addresses.recipientAddress,
      amountBaseUnits = amountBaseUnits,
      adminSignature = adminSignature
    )
  }

  private suspend fun withdrawRequest(
    chainId: Int,
    addresses: RainWithdrawAddresses,
    amount: BigDecimal,
    decimals: Int,
    adminSignature: RainAdminSignature,
    nonce: BigInteger?
  ) = WithdrawCollateralRequest(
    chainId = chainId,
    addresses = addresses,
    amount = amount,
    decimals = decimals,
    adminSignature = adminSignature,
    walletAddress = walletProvider.getWalletAddress(),
    nonce = nonce
  )

  override suspend fun estimateGas(
    chainId: Int,
    from: String,
    to: String,
    data: String
  ): BigDecimal {
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
    adminSignature: RainAdminSignature,
    nonce: BigInteger?
  ): BigDecimal {
    // TODO(v2.1): a Solana estimate is the flat per-signature fee plus token-account rent when
    // `UnsignedSolanaTransfer.createsRecipientAccount` is true.
    if (SolanaChains.isSolanaChain(chainId)) {
      throw RainError.InternalError("Withdrawal fee estimation is not supported on Solana")
    }

    return transactionCoordinator.estimateWithdrawalFee(
      withdrawRequest(chainId, addresses, amount, decimals, adminSignature, nonce)
    )
  }

  override suspend fun getWalletAddress(): String {
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
    to: String,
    amount: BigDecimal,
    decimals: Int?
  ): RainTokenTransferResult {
    return try {
      // Resolve decimals when the caller doesn't supply them: the token store checks its
      // registry first and falls back to an on-chain `decimals()` read for unknown tokens.
      //
      // Skipped on Solana — the store enriches through the EVM reader, which cannot see an SPL
      // mint — so an unspecified value resolves to 0 there. The Solana adapter reads the mint's
      // own decimals and must not scale with this one.
      val resolvedDecimals = decimals
        ?: takeUnless { SolanaChains.isSolanaChain(chainId) }
          ?.let { tokenStore?.tokenInfo(chainId, contractAddress)?.decimals }
        ?: if (SolanaChains.isSolanaChain(chainId)) 0 else RainClient.DEFAULT_ERC20_DECIMALS
      val txHash = walletProvider.sendToken(chainId, contractAddress, to, amount, resolvedDecimals)
      RainTokenTransferResult(transactionHash = txHash)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to send ERC-20 token")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getBalance(chainId: Int, token: Token): Balance {
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
    // Apply to the live store too, fire-and-forget so registration stays synchronous.
    tokenStore?.let { store ->
      tokenRegistrationScope.launch { store.register(tokens) }
    }
  }

  override fun reset() {
    // Clears this client's own state only. The chain configuration is owned by the `RainSdk` that
    // built this client and is shared with every other resolved client, so it deliberately
    // survives — one client resetting must not deconfigure the others.
    registeredTokens.clear()
    Timber.d("Rain SDK: Reset client state")
  }

  override suspend fun generateAddressQRCode(address: String?, dimension: Int): Bitmap {

    val targetAddress = address ?: getWalletAddress()

    return withContext(Dispatchers.Default) {
        try {
            QRGenerator.generateQRCode(targetAddress, dimension, dimension)
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
  ): List<RainTransaction> {
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
