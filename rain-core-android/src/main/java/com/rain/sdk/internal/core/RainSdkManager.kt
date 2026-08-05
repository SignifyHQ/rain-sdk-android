package com.rain.sdk.internal.core

import com.rain.sdk.RainAuthPullChains
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.models.RainApiEnvironment
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.utils.RainAmountUtils
import com.rain.sdk.internal.utils.isValidEthereumAddress
import com.rain.sdk.models.RainTokenAllowance
import com.rain.sdk.models.RainTokenApprovalResult
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
  override val capabilities: Set<Capability> = walletProvider.capabilities,
  /**
   * Read-only chain access for state the wallet provider does not expose — today, ERC-20
   * allowances. Balances still route through the provider, which may have a faster native API.
   */
  private val chainReader: ChainReader = EvmChainReader(rpcEndpoints = rpcEndpoints),
  /**
   * Chains an Auth Pull approval may target, derived from the configured [RainApiEnvironment].
   * Held as a resolved set rather than the environment itself so the approval guard has one thing
   * to check and no opinion about API hosts.
   */
  private val authPullChainIds: Set<Int> = RainAuthPullChains.SANDBOX
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

  // ---------------------------------------------------------------------------------------
  // Token approvals (Auth Pull)
  //
  // The wallet-side prerequisite for Rain's Auth Pull: approve the Rain operator to move USDC
  // from the user's wallet, read back what it may still move, and price the approval beforehand.
  // The pull itself is Rain's, not the SDK's.
  //
  // Approvals ride the same generic pipeline as any other send — calldata from `Erc20Abi`,
  // broadcast through `TransactionExecutor.sendTransaction` — so provider-specific behaviour
  // (Portal/Privy simulation, biometric prompts) applies unchanged.
  // ---------------------------------------------------------------------------------------

  override suspend fun approveTokenAllowance(
    chainId: Int,
    contractAddress: String,
    spender: String,
    amount: BigDecimal?,
    decimals: Int?
  ): RainTokenApprovalResult {
    return try {
      val (from, data) = buildApproval(chainId, contractAddress, spender, amount, decimals)
      val txHash = executor.sendTransaction(
        chainId = chainId,
        from = from,
        to = contractAddress,
        data = data,
        value = "0x0"
      )
      Timber.i("Rain SDK: Approval transaction submitted. Hash: %s", txHash)
      RainTokenApprovalResult(transactionHash = txHash)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to approve token allowance")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun getTokenAllowance(
    chainId: Int,
    contractAddress: String,
    spender: String,
    owner: String?,
    decimals: Int?
  ): RainTokenAllowance {
    return try {
      validateApprovalRequest(chainId, contractAddress, spender)

      val resolvedOwner = owner ?: walletProvider.getWalletAddress()
      if (!resolvedOwner.isValidEthereumAddress) {
        throw RainError.InvalidConfig("Invalid owner address: $resolvedOwner")
      }

      val rawAmount = chainReader.getErc20Allowance(
        chainId = chainId,
        tokenAddress = contractAddress,
        owner = resolvedOwner,
        spender = spender
      )
      // Same strictness as the approval: an allowance whose scale is a guess cannot be compared
      // against anything — `covers` would answer from the wrong exponent.
      val resolvedDecimals = requireDecimals(chainId, contractAddress, decimals)

      RainTokenAllowance(
        chainId = chainId,
        tokenAddress = contractAddress,
        owner = resolvedOwner,
        spender = spender,
        rawAmount = rawAmount,
        decimals = resolvedDecimals
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to read token allowance")
      throw errorMapper.mapTransactionError(e)
    }
  }

  override suspend fun estimateApprovalFee(
    chainId: Int,
    contractAddress: String,
    spender: String,
    amount: BigDecimal?,
    decimals: Int?
  ): BigDecimal {
    return try {
      val (from, data) = buildApproval(chainId, contractAddress, spender, amount, decimals)
      walletProvider.estimateTransactionFee(
        chainId = chainId,
        from = from,
        to = contractAddress,
        data = data,
        value = "0x0"
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to estimate approval fee")
      throw errorMapper.mapTransactionError(e)
    }
  }

  /**
   * Builds the `approve` transaction shared by the broadcast and estimate paths, so the fee is
   * priced against the exact calldata that would be sent.
   */
  private suspend fun buildApproval(
    chainId: Int,
    contractAddress: String,
    spender: String,
    amount: BigDecimal?,
    decimals: Int?
  ): Pair<String, String> {
    validateApprovalRequest(chainId, contractAddress, spender)

    val from = walletProvider.getWalletAddress()
    val allowanceBaseUnits = approvalBaseUnits(chainId, contractAddress, amount, decimals)
    return from to Erc20Abi.encodeApprove(spender, allowanceBaseUnits)
  }

  /**
   * An omitted amount means unlimited, which needs no decimals and so no metadata read. A
   * supplied amount is scaled by the token's decimals, and `0` is legal — that is a revoke.
   */
  private suspend fun approvalBaseUnits(
    chainId: Int,
    contractAddress: String,
    amount: BigDecimal?,
    decimals: Int?
  ): BigInteger {
    if (amount == null) return RainTokenAllowance.UNLIMITED_RAW_AMOUNT
    if (amount.signum() < 0) {
      throw RainError.InvalidAmount(
        amount = amount.toPlainString(),
        reason = "approval amount must not be negative"
      )
    }
    val resolvedDecimals = requireDecimals(chainId, contractAddress, decimals)
    return RainAmountUtils.toBaseUnits(amount, resolvedDecimals)
  }

  /**
   * The token's decimals, refusing to guess.
   *
   * The rest of the SDK falls back to 18 when a `decimals()` read fails, which misreports a
   * balance. On an approval the same guess would *over-approve* a 6-decimal token by 10^12 — and
   * `approve` succeeds regardless of balance, so no later step would catch it. Failing loudly is
   * the only safe answer.
   */
  private suspend fun requireDecimals(
    chainId: Int,
    contractAddress: String,
    decimals: Int?
  ): Int {
    val resolved = decimals
      ?: tokenStore?.decimalsOrNull(chainId, contractAddress)
      ?: throw RainError.TokenNotFound(contractAddress, chainId)

    // `decimals()` is whatever the contract says, and scaling raises 10 to that power. Bound it
    // here, the one place both the approval and the allowance read pass through. 77 is the
    // ceiling that means anything: uint256 max is ~1.16e77, so one whole unit of a finer token
    // is unrepresentable.
    if (resolved !in 0..77) {
      throw RainError.InvalidConfig(
        "Token $contractAddress reports $resolved decimals, outside the supported range 0..77"
      )
    }
    return resolved
  }

  /**
   * Rejects approval parameters that cannot produce a valid transaction, before any network call
   * or signature prompt.
   */
  private fun validateApprovalRequest(chainId: Int, contractAddress: String, spender: String) {
    if (chainId <= 0) {
      throw RainError.InvalidConfig("Invalid chainId: $chainId. Must be a positive integer.")
    }
    // SPL delegation is per token account and carries its own semantics, so an ERC-20 approval
    // has no Solana equivalent to fall back on.
    if (SolanaChains.isSolanaChain(chainId)) {
      throw RainError.InternalError(
        "Token approvals are EVM-only; chainId=$chainId is a Solana chain"
      )
    }
    // Rain's operator and USDC are per environment, and the sandbox and production chain sets are
    // disjoint. Approving on the other environment's chain mines a real allowance that no
    // authorization will ever draw on — on mainnet, at real cost. Nothing downstream would catch
    // it, since `approve` succeeds against any address, so refuse the pairing here.
    if (chainId !in authPullChainIds) {
      throw RainError.InvalidConfig(
        "chainId=$chainId is not an Auth Pull chain for the configured Rain API environment " +
          "(expected one of ${authPullChainIds.sorted().joinToString(", ")}). Set " +
          "RainSdk.Builder.rainApiEnvironment(...) to match the chain you are approving on."
      )
    }
    if (!contractAddress.isValidEthereumAddress) {
      throw RainError.InvalidConfig("Invalid token contract address: $contractAddress")
    }
    if (!spender.isValidEthereumAddress) {
      throw RainError.InvalidConfig("Invalid spender address: $spender")
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
