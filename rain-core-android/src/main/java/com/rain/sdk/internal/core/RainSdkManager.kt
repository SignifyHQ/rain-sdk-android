package com.rain.sdk.internal.core

import com.rain.sdk.RainAuthPullChains
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.models.RainApiEnvironment
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.MinedReceipt
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
import com.rain.sdk.internal.utils.RainHexUtils
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
import kotlinx.coroutines.delay
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
   * Chains an Auth Pull approval may target: the host's [com.rain.sdk.RainAuthPullConfig] narrowed
   * to the chains that have an RPC endpoint. Held as a resolved set rather than the environment
   * itself so the approval guard has one thing to check and no opinion about API hosts, and
   * exposed on [RainClient] so host UI can gate on exactly what the guard enforces.
   */
  override val authPullChainIds: Set<Int> = emptySet(),
  /** Rain's operator for the configured environment — the only spender an approval may name. */
  private val authPullOperator: String? = null,
  /** The trusted token contract per Auth Pull chain — the only token an approval may target. */
  private val authPullTokenAddresses: Map<Int, String> = emptyMap(),
  /** Pause between confirmation polls; injectable so tests can drive the full window instantly. */
  private val approvalConfirmationIntervalMs: Long = APPROVAL_CONFIRMATION_INTERVAL_MS
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
      // A typo'd recipient would otherwise broadcast as-is and the funds are gone; validate and
      // checksum up front, as the withdrawal path does. Solana recipients are validated by the
      // transfer composer.
      val recipient = if (SolanaChains.isSolanaChain(chainId)) to else checksummedRecipient(to)
      val txHash = walletProvider.sendNativeToken(chainId, recipient, amount)
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
      // Decimals the caller omitted come from the registry or a strict on-chain read; a failed
      // read throws rather than scaling a real transfer by a guessed 18.
      //
      // Skipped on Solana — the store enriches through the EVM reader, which cannot see an SPL
      // mint — so an unspecified value resolves to 0 there. The Solana adapter reads the mint's
      // own decimals and must not scale with this one.
      val recipient = if (SolanaChains.isSolanaChain(chainId)) to else checksummedRecipient(to)
      val resolvedDecimals = decimals
        ?: if (SolanaChains.isSolanaChain(chainId)) 0 else requireDecimals(chainId, contractAddress)
      val txHash = walletProvider.sendToken(chainId, contractAddress, recipient, amount, resolvedDecimals)
      RainTokenTransferResult(transactionHash = txHash)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to send ERC-20 token")
      throw errorMapper.mapTransactionError(e)
    }
  }

  /**
   * The token's decimals from the registry or a strict on-chain read. Refuses to guess: a
   * default 18 against a 6-decimal token would move 10^12 times the intended amount.
   */
  private suspend fun requireDecimals(chainId: Int, contractAddress: String): Int {
    val resolved = tokenStore?.decimalsOrNull(chainId, contractAddress)
      ?: throw RainError.TokenNotFound(contractAddress, chainId)

    // Scaling raises 10 to this power; uint256 max is ~1.16e77, so anything finer is unusable.
    if (resolved !in 0..77) {
      throw RainError.InvalidConfig(
        "Token $contractAddress reports $resolved decimals, outside the supported range 0..77"
      )
    }
    return resolved
  }

  /** Validates and EIP-55 checksums an EVM recipient; a malformed address must never broadcast. */
  private fun checksummedRecipient(to: String): String {
    if (!RainHexUtils.isValidAddress(to)) {
      throw RainError.InvalidRecipient(to, "not a valid EVM address")
    }
    return RainHexUtils.toChecksumAddress(to)
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
    // than failing the whole call, so one bad RPC endpoint doesn't hide the others. A dead
    // wallet session is the exception: it affects every chain identically, and swallowing it
    // would return an empty list a host could mistake for zero balances.
    return coroutineScope {
      chainIds.map { chainId ->
        async {
          runCatching { walletProvider.getBalances(chainId) }.getOrElse { e ->
            if (e is CancellationException) throw e
            if (e is RainError.TokenExpired) throw e
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
    amount: BigDecimal?
  ): RainTokenApprovalResult {
    return try {
      val (from, data) = buildApproval(chainId, contractAddress, spender, amount)
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
    owner: String?
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
      val resolvedDecimals = requireDecimals(chainId, contractAddress)

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
    amount: BigDecimal?
  ): BigDecimal {
    return try {
      val (from, data) = buildApproval(chainId, contractAddress, spender, amount)
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

  override suspend fun confirmTokenAllowance(
    transactionHash: String,
    chainId: Int,
    contractAddress: String,
    spender: String,
    amount: BigDecimal?,
    owner: String?
  ): RainTokenAllowance {
    try {
      validateApprovalRequest(chainId, contractAddress, spender)
      // Resolved up front: these fail on configuration, not timing, so retrying them changes
      // nothing.
      val expectedRaw = approvalBaseUnits(chainId, contractAddress, amount)
      val resolvedDecimals = requireDecimals(chainId, contractAddress)
      val resolvedOwner = owner ?: walletProvider.getWalletAddress()
      if (!resolvedOwner.isValidEthereumAddress) {
        throw RainError.InvalidConfig("Invalid owner address: $resolvedOwner")
      }

      var receipt: MinedReceipt? = null
      var lastReadFailure: Exception? = null

      repeat(APPROVAL_CONFIRMATION_ATTEMPTS) { attempt ->
        if (receipt == null) {
          // The approval is already out, so a failed receipt read is a reason to try again, not
          // a verdict on the transaction. Only a malformed hash is final.
          val mined = try {
            chainReader.getTransactionReceipt(chainId, transactionHash)
          } catch (e: CancellationException) {
            throw e
          } catch (e: RainError.InvalidConfig) {
            throw e
          } catch (e: Exception) {
            Timber.w(e, "Rain SDK: Receipt read for %s failed; retrying", transactionHash)
            lastReadFailure = e
            null
          }
          if (mined != null && !mined.succeeded) {
            throw RainError.TransactionSimulationFailed(
              IllegalStateException("Approval transaction reverted on-chain: $transactionHash")
            )
          }
          receipt = mined
        }

        receipt?.let { mined ->
          val rawAmount = try {
            chainReader.getErc20Allowance(
              chainId = chainId,
              tokenAddress = contractAddress,
              owner = resolvedOwner,
              spender = spender,
              atBlock = mined.blockNumber
            )
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Timber.w(
              e,
              "Rain SDK: Allowance read at block %s failed; the node may not have it yet",
              mined.blockNumber
            )
            lastReadFailure = e
            null
          }

          if (rawAmount != null) {
            val allowance = RainTokenAllowance(
              chainId = chainId,
              tokenAddress = contractAddress,
              owner = resolvedOwner,
              spender = spender,
              rawAmount = rawAmount,
              decimals = resolvedDecimals
            )
            verifyConfirmedAllowance(allowance, expectedRaw)
            return allowance
          }
        }

        if (attempt < APPROVAL_CONFIRMATION_ATTEMPTS - 1) {
          delay(approvalConfirmationIntervalMs)
        }
      }

      val mined = receipt
      if (mined == null) {
        // Not a failure: the approval may still mine. The hash is what the host resumes from —
        // re-read the allowance or confirm again, never re-approve.
        Timber.w(
          lastReadFailure,
          "Rain SDK: Approval %s was not confirmed within the window",
          transactionHash
        )
        throw RainError.TransactionPending(transactionHash)
      }
      // Mined, but the allowance never read back. Surface the node's own failure where there is
      // one; a generic timeout would hide why every read at that block failed.
      throw when (val cause = lastReadFailure) {
        is RainError -> cause
        else -> RainError.NetworkError(
          message = "Approval transaction $transactionHash mined in block ${mined.blockNumber}, " +
            "but the allowance could not be read at that block before timing out",
          cause = cause
        )
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is RainError) throw e
      Timber.e(e, "Rain SDK: Failed to confirm token allowance")
      throw errorMapper.mapTransactionError(e)
    }
  }

  /**
   * Checks the allowance a mined approval actually left behind, read at the block it mined in.
   *
   * Pinning the read to that block is what makes the comparison exact: `approve` sets the value
   * outright and later blocks cannot move what it reads. Anything but the requested amount
   * therefore means the approval did not do what was asked — a revoke that left a spendable
   * allowance, an approval that mined against nothing (the shape a wrong owner, token, or
   * spender produces), or one that reverted inside a bundle whose transaction as a whole
   * succeeded: below when it was raising the allowance, above when it was lowering it.
   *
   * One edge: the read sees end-of-block state, so an Auth Pull `transferFrom` mined later in the
   * same block also reads back lower and is reported here as a mismatch.
   */
  private fun verifyConfirmedAllowance(allowance: RainTokenAllowance, expectedRaw: BigInteger) {
    if (expectedRaw.signum() == 0) {
      if (!allowance.isZero) {
        throw RainError.InternalError(
          "Revoke mined but ${allowance.spender} still holds an allowance of ${allowance.rawAmount}"
        )
      }
      return
    }
    if (allowance.isZero) {
      throw RainError.InternalError(
        "Approval mined but the allowance for ${allowance.spender} on ${allowance.tokenAddress} " +
          "is still zero; expected $expectedRaw"
      )
    }
    if (allowance.rawAmount < expectedRaw) {
      throw RainError.InternalError(
        "Approval mined but the allowance for ${allowance.spender} on ${allowance.tokenAddress} " +
          "is ${allowance.rawAmount}, below the requested $expectedRaw"
      )
    }
    if (allowance.rawAmount > expectedRaw) {
      throw RainError.InternalError(
        "Approval mined but the allowance for ${allowance.spender} on ${allowance.tokenAddress} " +
          "is ${allowance.rawAmount}, above the requested $expectedRaw"
      )
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
    amount: BigDecimal?
  ): Pair<String, String> {
    validateApprovalRequest(chainId, contractAddress, spender)

    val from = walletProvider.getWalletAddress()
    val allowanceBaseUnits = approvalBaseUnits(chainId, contractAddress, amount)
    return from to Erc20Abi.encodeApprove(spender, allowanceBaseUnits)
  }

  /**
   * An omitted amount means unlimited, which needs no decimals and so no metadata read. A
   * supplied amount is scaled by the token's decimals, and `0` is legal — that is a revoke.
   */
  private suspend fun approvalBaseUnits(
    chainId: Int,
    contractAddress: String,
    amount: BigDecimal?
  ): BigInteger {
    if (amount == null) return RainTokenAllowance.UNLIMITED_RAW_AMOUNT
    if (amount.signum() < 0) {
      throw RainError.InvalidAmount(
        amount = amount.toPlainString(),
        reason = "approval amount must not be negative"
      )
    }
    val resolvedDecimals = requireDecimals(chainId, contractAddress)
    return RainAmountUtils.toBaseUnits(amount, resolvedDecimals)
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
    if (authPullOperator == null || authPullTokenAddresses.isEmpty()) {
      throw RainError.InvalidConfig(
        "Auth Pull is disabled. Configure RainSdk.Builder.authPullConfig(...) first."
      )
    }
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
    if (!spender.equals(authPullOperator, ignoreCase = true)) {
      throw RainError.InvalidConfig("Spender does not match the configured Auth Pull operator")
    }
    val trustedToken = authPullTokenAddresses[chainId]
      ?: throw RainError.InvalidConfig("No Auth Pull token configured for chainId=$chainId")
    if (!contractAddress.equals(trustedToken, ignoreCase = true)) {
      throw RainError.InvalidConfig(
        "Token contract does not match the configured Auth Pull token for chainId=$chainId"
      )
    }
  }

  internal companion object {
    const val APPROVAL_CONFIRMATION_ATTEMPTS = 60
    const val APPROVAL_CONFIRMATION_INTERVAL_MS = 1_000L
  }

  override fun registerTokens(tokens: List<TokenInfo>) {
    if (tokens.isEmpty()) return
    // Reject malformed EVM addresses at the source: an entry that enters the store rides into
    // every balance batch on its chain. Solana mints are base58 and validated by their own
    // paths. Validate the whole list before adding anything, so a bad entry registers nothing.
    tokens.forEach { token ->
      if (!SolanaChains.isSolanaChain(token.chainId) && !RainHexUtils.isValidAddress(token.address)) {
        throw RainError.InvalidConfig(
          "Invalid token address for chainId=${token.chainId}: ${token.address}"
        )
      }
    }
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
