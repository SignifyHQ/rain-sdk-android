package com.rain.sdk.interfaces

import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainTokenAllowance
import com.rain.sdk.models.RainTokenApprovalResult
import com.rain.sdk.models.RainTokenTransferResult
import com.rain.sdk.models.RainTransactionParameters
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import android.graphics.Bitmap
import java.math.BigDecimal
import java.math.BigInteger

/** Default body of the Auth Pull methods: implementations that do not support them keep compiling. */
private const val AUTH_PULL_NOT_SUPPORTED = "Auth Pull is not supported by this RainClient implementation"

/**
 * Operations Rain exposes against a single, already-resolved wallet provider.
 *
 * A `RainClient` is obtained from [com.rain.sdk.RainSdk.provider] / [com.rain.sdk.RainSdk.first];
 * it is bound to one provider for its lifetime, so it carries no `initialize*` methods and never
 * references a concrete vendor type. Which provider backs it is described by [providerId] and
 * [capabilities].
 */
interface RainClient {
    /**
     * Checks if the SDK has been successfully initialized.
     */
    val isInitialized: Boolean

    /**
     * Identifier of the provider backing this client (e.g. [ProviderId.PORTAL]).
     */
    val providerId: ProviderId

    /**
     * Optional behaviours the backing provider supports (see [Capability]).
     */
    val capabilities: Set<Capability>

    /**
     * Executes a collateral withdrawal on-chain. Always broadcasts: the 1.0.x `autoSend` flag
     * (default `false`, prepare-only) is gone — use [prepareWithdrawal] for that.
     *
     * @param chainId The chain ID for the transaction
     * @param addresses All required addresses for the withdrawal
     * @param amount The amount to withdraw
     * @param decimals Token decimals. Scales [amount] on every chain including Solana, where it is
     *                 not checked against the SPL mint — pass the mint's real decimals
     * @param adminSignature The withdrawal authorization from `RainSdk.fetchAdminSignature`
     * @param nonce Optional nonce; resolved from the contract when null
     * @return The transaction hash (EVM) or transaction signature (Solana)
     */
    @Throws(RainError::class)
    suspend fun withdrawCollateral(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null
    ): String

    /**
     * Builds a collateral withdrawal without broadcasting it. Takes the same parameters as
     * [withdrawCollateral]. See [RainPreparedWithdrawal] for what this does and does not do
     * offline, and for the Solana blockhash lifetime.
     */
    @Throws(RainError::class)
    suspend fun prepareWithdrawal(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null
    ): RainPreparedWithdrawal

    /**
     * Gets the current wallet address from the underlying provider.
     * @return Hex-encoded wallet address
     * @throws RainError if the address cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getWalletAddress(): String

    /**
     * Gets the current wallet address from the underlying provider.
     *
     * @return Hex-encoded wallet address.
     * @throws RainError if the address cannot be retrieved.
     */
    @Deprecated(
        message = "Renamed to getWalletAddress(). This shim delegates to it.",
        replaceWith = ReplaceWith("getWalletAddress()")
    )
    @Throws(RainError::class)
    suspend fun getAddress(): String = getWalletAddress()

    /**
     * Gets the wallet address for a specific chain. For EVM chains this matches [getWalletAddress]
     * (a hex address). A provider that also holds non-EVM accounts (one advertising
     * [Capability.MULTI_CHAIN]) returns the address matching [chainId]'s family — e.g. a base58
     * address for a Solana chain id (`RainChain.SOLANA_*`). EVM-only providers return the hex
     * address regardless.
     *
     * @param chainId The numeric chain ID (EVM chain ID, or a `RainChain.SOLANA_*` sentinel).
     * @return The wallet address for that chain's family.
     * @throws RainError if the address cannot be retrieved.
     */
    @Throws(RainError::class)
    suspend fun getWalletAddress(chainId: Int): String

    /**
     * Estimates the gas fee required for a transaction.
     *
     * @param chainId The chain ID for the transaction
     * @param from The sender address
     * @param to The target contract address
     * @param data The transaction data (hex-encoded)
     * @return Estimated gas fee in ETH
     * @throws RainError if estimation fails
     */
    @Throws(RainError::class)
    suspend fun estimateGas(
        chainId: Int,
        from: String,
        to: String,
        data: String
    ): BigDecimal

    /**
     * Estimates the total fee (in the chain's native token, e.g. ETH/AVAX) required to
     * execute a collateral withdrawal transaction.
     *
     * Builds the EIP-712 payload, signs it with the wallet, then runs `eth_estimateGas`
     * against the controller. Nothing is broadcast. Note: the calldata also embeds a wallet
     * signature the controller verifies, so the estimate signs once with the wallet (a
     * placeholder signature would revert the estimate).
     *
     * @param chainId The chain ID for the transaction. EVM only.
     * @param addresses All required addresses for the withdrawal.
     * @param amount The amount to withdraw (human units).
     * @param decimals Token decimals.
     * @param adminSignature The withdrawal authorization from `RainSdk.fetchAdminSignature`
     * @param nonce Optional nonce; pin the estimate to the nonce the withdrawal will sign.
     * @return Estimated withdrawal fee in the chain's native token, as an exact [BigDecimal].
     * @throws RainError if estimation fails, or if [chainId] is a Solana chain.
     */
    @Throws(RainError::class)
    suspend fun estimateWithdrawalFee(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null
    ): BigDecimal

    /**
     * Composes wallet-agnostic transaction parameters for a contract call.
     *
     * Pure composition — no wallet provider and no RPC — so it belongs on [com.rain.sdk.RainSdk],
     * not on a resolved client.
     */
    @Deprecated(
        message = "Pure composition needs no resolved client. Call RainSdk.buildTransactionParameters(...).",
        replaceWith = ReplaceWith("rain.buildTransactionParameters(walletAddress, contractAddress, transactionData)")
    )
    fun composeTransactionParameters(
        walletAddress: String,
        contractAddress: String,
        transactionData: String
    ): RainTransactionParameters = RainTransactionParameters(
        from = walletAddress,
        to = contractAddress,
        value = "0x0",
        data = transactionData
    )

    /**
     * Sends the chain's native token (e.g. ETH, AVAX).
     *
     * @param chainId Network ID
     * @param to Recipient's wallet address
     * @param amount Amount to send, in the native token's human unit (e.g. 0.1 AVAX)
     * @return RainTokenTransferResult containing the transaction hash
     */
    @Throws(RainError::class)
    suspend fun sendNative(
        chainId: Int,
        to: String,
        amount: BigDecimal
    ): RainTokenTransferResult

    /**
     * Sends the chain's native token (e.g. ETH, AVAX).
     *
     * @param chainId Network ID
     * @param toAddress Recipient's wallet address
     * @param amount Amount of token to send
     * @return RainTokenTransferResult containing the transaction hash
     */
    @Deprecated(
        message = "Renamed to sendNative(chainId, to, amount). This shim delegates to it.",
        replaceWith = ReplaceWith("sendNative(chainId, toAddress, amount)")
    )
    @Throws(RainError::class)
    suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amount: BigDecimal
    ): RainTokenTransferResult = sendNative(chainId, toAddress, amount)

    /**
     * Sends an ERC-20 token.
     *
     * @param chainId Network ID
     * @param contractAddress ERC-20 token contract address
     * @param to Recipient's wallet address
     * @param amount Amount to send (in human-readable unit, e.g. 1.5 USDC)
     * @param decimals Optional number of decimals the token uses (e.g. 6 for USDC, 18 for most
     *                 tokens). When `null` (the default), the SDK resolves the token's
     *                 `decimals()` itself — from its token registry or, for unknown tokens, an
     *                 on-chain `decimals()` read — so callers don't have to track it. If neither
     *                 can establish it, the send fails with [RainError.TokenNotFound] rather than
     *                 scaling the amount by a guess.
     *                 On Solana it never scales the amount: the SPL mint's own decimals are read
     *                 from the chain and enforced by `TransferChecked`.
     * @return RainTokenTransferResult containing the transaction hash
     */
    @Throws(RainError::class)
    suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        to: String,
        amount: BigDecimal,
        decimals: Int? = null
    ): RainTokenTransferResult

    /**
     * Sends an ERC-20 token with an explicit, non-null [decimals].
     *
     * Backward-compatibility shim for callers compiled against the pre-1.1 signature
     * (`decimals: Int`). It delegates to [sendToken] with a nullable `decimals`; new code can
     * simply omit `decimals` and let the SDK resolve it. Retained so an SDK upgrade doesn't
     * break already-compiled consumers (the `Int` and `Int?` parameters have different JVM
     * descriptors).
     *
     * @param decimals Number of decimals the token uses (e.g. 6 for USDC, 18 for most tokens).
     */
    @Deprecated(
        message = "decimals is now optional; the SDK resolves it from its registry or an " +
            "on-chain decimals() read. Call sendToken(chainId, contractAddress, toAddress, " +
            "amount) and omit decimals.",
        replaceWith = ReplaceWith("sendToken(chainId, contractAddress, toAddress, amount)")
    )
    @Throws(RainError::class)
    suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: Double,
        decimals: Int
    ): RainTokenTransferResult {
        if (!amount.isFinite()) {
            throw RainError.InvalidAmount(amount.toString(), "amount must be a finite number")
        }
        return sendToken(chainId, contractAddress, toAddress, amount.toBigDecimal(), decimals as Int?)
    }

    /**
     * Fetches a single balance (native or a contract token) for the current wallet.
     *
     * @param chainId The numeric chain ID (e.g. 1 for Ethereum, 43114 for Avalanche).
     * @param token [Token.Native] for the chain's gas currency, or [Token.Contract] for an
     *              ERC-20. Contract-address comparison is case-insensitive.
     * @return A [Balance] carrying the exact `rawAmount` plus resolved decimals / symbol / name.
     * @throws RainError if no wallet provider is set, or if the request fails.
     */
    @Throws(RainError::class)
    suspend fun getBalance(chainId: Int, token: Token): Balance

    /**
     * Fetches all non-zero balances for the current wallet on the given network. The native
     * balance is always included; zero-balance contract tokens are omitted.
     *
     * Supersedes the deprecated [getBalances], which returned a lossy `Map<String, Double>`.
     *
     * @param chainId The numeric chain ID.
     * @return One [Balance] per non-zero token plus the native balance.
     * @throws RainError if no wallet provider is set, or if the request fails.
     */
    @Throws(RainError::class)
    suspend fun getTokenBalances(chainId: Int): List<Balance>

    /**
     * Fetches balances across every chain the SDK was initialized with, in parallel,
     * flattened into a single list. Each [Balance] carries its own `chainId`.
     *
     * Per-chain failures are tolerated — a chain that errors out contributes no entries
     * rather than failing the whole call, so a single bad RPC endpoint doesn't hide
     * balances on the other chains.
     *
     * @return A flat list of balances spanning all healthy configured chains.
     * @throws RainError if the SDK was not initialized or no wallet provider is set.
     */
    @Throws(RainError::class)
    suspend fun getAllBalances(): List<Balance>

    // ---------------------------------------------------------------------------------------
    // Deprecated balance API (pre-balance-consolidation, i.e. before #38's follow-up work).
    // Kept as default-method shims so existing call sites keep compiling and linking against
    // newer releases. Each delegates to the precise [Balance] API and collapses the result to
    // the old lossy `Double` shape. Slated for removal in the next major version.
    // ---------------------------------------------------------------------------------------

    /**
     * Gets the native token balance (e.g. AVAX) for the current wallet.
     *
     * @param chainId The numeric chain ID (e.g. 43114 for Avalanche Mainnet).
     * @return Native token balance in Ether units (Double).
     * @throws RainError if the balance cannot be retrieved.
     */
    @Deprecated(
        message = "Use getBalance(chainId, Token.Native) and read .decimalAmount for exact " +
            "precision. This shim collapses the balance to a lossy Double.",
        replaceWith = ReplaceWith(
            "getBalance(chainId, Token.Native).decimalAmount.toDouble()",
            "com.rain.sdk.models.Token"
        )
    )
    @Throws(RainError::class)
    suspend fun getNativeBalance(chainId: Int): Double =
        getBalance(chainId, Token.Native).decimalAmount.toDouble()

    /**
     * Gets the balance of a specific ERC-20 token for the current wallet.
     *
     * The [decimals] argument is ignored: the SDK now resolves token decimals itself (from its
     * token store or on-chain). It is retained only for source compatibility.
     *
     * @param chainId The numeric chain ID (e.g. 43114 for Avalanche Mainnet).
     * @param tokenAddress The contract address of the ERC-20 token.
     * @param decimals Ignored. Previously the assumed token decimals.
     * @return Token balance as a Double (with decimals already applied).
     * @throws RainError if the balance cannot be retrieved.
     */
    @Deprecated(
        message = "Use getBalance(chainId, Token.contract(tokenAddress)) and read .decimalAmount " +
            "for exact precision. The decimals argument is ignored; the SDK resolves decimals itself.",
        replaceWith = ReplaceWith(
            "getBalance(chainId, Token.contract(tokenAddress)).decimalAmount.toDouble()",
            "com.rain.sdk.models.Token"
        )
    )
    @Throws(RainError::class)
    suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        decimals: Int? = DEFAULT_ERC20_DECIMALS
    ): Double = getBalance(chainId, Token.contract(tokenAddress)).decimalAmount.toDouble()

    /**
     * Gets all ERC-20 token balances for the current wallet on the given network, keyed by
     * contract address.
     *
     * Note: built on [getTokenBalances], which omits zero-balance contract tokens and includes the
     * native balance; this shim drops the native entry, so the result is non-zero ERC-20s only.
     *
     * @param chainId The numeric chain ID.
     * @return Map of token contract address to balance (Double).
     * @throws RainError if balances cannot be retrieved.
     */
    @Deprecated(
        message = "Use getTokenBalances(chainId), which returns List<Balance> (native + contract " +
            "tokens) with exact precision. This shim drops the native entry and collapses to Double.",
        replaceWith = ReplaceWith("getTokenBalances(chainId)")
    )
    @Throws(RainError::class)
    suspend fun getERC20Balances(chainId: Int): Map<String, Double> =
        getTokenBalances(chainId)
            .mapNotNull { balance ->
                (balance.token as? Token.Contract)?.let { contract ->
                    contract.address to balance.decimalAmount.toDouble()
                }
            }
            .toMap()

    /**
     * Gets all balances for the current wallet on the given network, keyed by contract address,
     * with the native balance stored under the empty-string key `""`.
     *
     * @param chainId The numeric chain ID.
     * @return Map of token contract address to balance (Double), plus native balance under `""`.
     * @throws RainError if balances cannot be retrieved.
     */
    @Deprecated(
        message = "Use getTokenBalances(chainId), which returns List<Balance> (native + contract " +
            "tokens) with exact precision. This shim collapses to a lossy Double map keyed by " +
            "contract address (as returned by the provider), with the native balance under the " +
            "empty-string key \"\".",
        replaceWith = ReplaceWith("getTokenBalances(chainId)")
    )
    @Throws(RainError::class)
    suspend fun getBalances(chainId: Int): Map<String, Double> =
        getTokenBalances(chainId).associate { balance ->
            val key = (balance.token as? Token.Contract)?.address ?: ""
            key to balance.decimalAmount.toDouble()
        }

    // ---------------------------------------------------------------------------------------
    // Token approvals (Auth Pull)
    // ---------------------------------------------------------------------------------------

    /**
     * The chains this client will accept an Auth Pull approval on, and the only answer that
     * matches what the approval methods below enforce.
     *
     * Empty until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets, and narrower
     * than `RainAuthPullChains.supported(environment)` whenever the configuration is narrower than
     * its environment or a chain has no RPC endpoint. Gate host UI on this rather than on the
     * environment's chain set, so a chain is never offered that an approval would reject.
     */
    val authPullChainIds: Set<Int> get() = emptySet()

    /**
     * Approves [spender] to move up to [amount] of an ERC-20 token from this wallet, and returns
     * the resulting transaction hash.
     *
     * This is the wallet-side prerequisite for Rain's Auth Pull: the Rain operator must be
     * approved on the user's wallet before an authorization can pull USDC into their collateral
     * contract. Rain executes the pull itself; the SDK only sets the allowance.
     *
     * Auth Pull is disabled until `RainSdk.Builder.authPullConfig(...)` supplies the trusted
     * operator and token targets. This method rejects any different chain, token, or spender.
     *
     * @param chainId EVM chain the token lives on. Solana chain IDs throw — SPL has no
     *                ERC-20-style allowance.
     * @param contractAddress The ERC-20 token contract (USDC for Auth Pull today).
     * @param spender The address being approved. Source Rain's operator address from Rain rather
     *                than hardcoding it — it differs between sandbox and production.
     * @param amount Human-readable allowance (e.g. `250` for 250 USDC). `null` (the default)
     *               approves an unlimited (`uint256` max) allowance, so the user never has to
     *               re-approve; `BigDecimal.ZERO` revokes an existing approval.
     * @return [RainTokenApprovalResult] carrying the transaction hash.
     */
    @Throws(RainError::class)
    suspend fun approveTokenAllowance(
        chainId: Int,
        contractAddress: String,
        spender: String,
        amount: BigDecimal? = null
    ): RainTokenApprovalResult = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Reads the ERC-20 allowance [spender] currently holds over [owner]'s balance.
     *
     * Call it before approving (to skip a redundant transaction). To confirm an approval was
     * mined, use [confirmTokenAllowance], this read is unpinned and can still return the
     * pre-approval value right after submitting.
     *
     * @param owner The wallet whose balance is approved. `null` (the default) reads this client's
     *              own wallet.
     */
    @Throws(RainError::class)
    suspend fun getTokenAllowance(
        chainId: Int,
        contractAddress: String,
        spender: String,
        owner: String? = null
    ): RainTokenAllowance = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Estimates the total fee (estimated gas x gas price) to submit the approval, in the chain's
     * native token. Same parameters as [approveTokenAllowance]; nothing is broadcast and no
     * signature is requested.
     */
    @Throws(RainError::class)
    suspend fun estimateApprovalFee(
        chainId: Int,
        contractAddress: String,
        spender: String,
        amount: BigDecimal? = null
    ): BigDecimal = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Waits for an approval transaction to mine successfully, then reads back the resulting
     * allowance at the block it mined in. A submitted transaction hash alone does not make Auth
     * Pull ready.
     *
     * Because the read is pinned to the transaction's own block, the result is exactly what the
     * approval left behind: anything but [amount] means the approval did not do what was asked
     * (wrong owner, token, or spender, or a revert inside a bundle — leaving less when raising
     * the allowance, more when lowering it) and throws, as does a revoke that left a spendable
     * allowance.
     *
     * @param amount The allowance that was requested, so the result can be checked against it.
     *               `null` (the default) means the unlimited approval.
     * @throws RainError.TransactionSimulationFailed when the mined transaction reverted.
     * @throws RainError.TransactionPending when the transaction has not mined by the end of the
     *   poll window. Not a failure: `statusId` carries [transactionHash]; re-read the allowance or
     *   confirm again rather than re-approving.
     * @throws RainError.InternalError when the mined allowance contradicts the request. An Auth
     *   Pull `transferFrom` mined later in the same block also reads back lower and surfaces here;
     *   re-read with [getTokenAllowance] before treating it as a failed approval.
     */
    @Throws(RainError::class)
    suspend fun confirmTokenAllowance(
        transactionHash: String,
        chainId: Int,
        contractAddress: String,
        spender: String,
        amount: BigDecimal? = null,
        owner: String? = null
    ): RainTokenAllowance = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Registers additional tokens with the SDK so their metadata (decimals / symbol) resolves
     * without an on-chain enrichment call. Retained across re-initialization; cleared by [reset].
     * Re-registering a host-added address replaces its entry; built-in registry tokens are
     * trusted and cannot be overridden.
     *
     * @param tokens Tokens to add to the SDK's token store.
     */
    fun registerTokens(tokens: List<TokenInfo>)

    /**
     * Clears all SDK state — wallet provider, Portal/Turnkey contexts, and stored chain
     * configuration. After this returns, the SDK is back to the same state as immediately
     * after construction and must be re-initialized before further use. Idempotent.
     */
    fun reset()

    /**
     * Generates a square QR code [Bitmap] encoding [address], or the wallet's own address when
     * [address] is null.
     *
     * Use this for any address the host needs to show — a chain-specific wallet address (the
     * Solana account rather than the EVM one), or a Rain collateral deposit address.
     *
     * @param address Address to encode. Null encodes the provider's wallet address.
     * @param dimension Output width and height in pixels (the QR is square). Defaults to 256.
     * @return A [Bitmap] containing the QR code.
     * @throws RainError If [address] is null and the provider's address cannot be retrieved.
     */
    @Throws(RainError::class)
    suspend fun generateAddressQRCode(
        address: String? = null,
        dimension: Int = 256
    ): Bitmap

    /**
     * Generates a QR code with independent width and height.
     *
     * A QR code is square, so the two dimensions were always set to the same value in practice.
     */
    @Deprecated(
        message = "A QR code is square. Call generateAddressQRCode(address, dimension).",
        replaceWith = ReplaceWith("generateAddressQRCode(address, width)")
    )
    @Throws(RainError::class)
    suspend fun generateAddressQRCode(
        address: String?,
        width: Int,
        height: Int
    ): Bitmap = generateAddressQRCode(address, width)

    /**
     * Retrieves the transaction history for the specified chain.
     *
     * @param chainId The numeric chain ID
     * @param limit Optional maximum number of transactions to return
     * @param offset Optional number of transactions to skip for pagination
     * @param order Optional sort order (ASC or DESC)
     * @return List<RainTransaction> containing a list of transactions
     * @throws RainError if the transaction history cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getTransactions(
        chainId: Int,
        limit: Int? = null,
        offset: Int? = null,
        order: RainTransactionOrder? = null
    ): List<RainTransaction>

    companion object {
        /**
         * Default number of decimals for ERC20 tokens if not specified.
         */
        const val DEFAULT_ERC20_DECIMALS = 18
    }
}
