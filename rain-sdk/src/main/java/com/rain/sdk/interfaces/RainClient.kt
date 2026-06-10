package com.rain.sdk.interfaces

import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainTokenTransferResult
import com.rain.sdk.models.RainTransactionParameters
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainWithdrawResult
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.internal.error.RainError
import com.turnkey.core.TurnkeyContext
import io.portalhq.android.Portal
import android.graphics.Bitmap
import java.math.BigInteger

interface RainClient {
    /**
     * Checks if the SDK has been successfully initialized.
     */
    val isInitialized: Boolean

    /**
     * Computed property to safely access the Portal instance.
     * Throws RainError.SdkNotInitialized if not initialized.
     */
    val portal: Portal

    /**
     * Computed property to safely access the Turnkey context after `initializeTurnkey`.
     * Throws RainError.SdkNotInitialized if not initialized.
     */
    val turnkey: TurnkeyContext

    /**
     * Initializes the SDK with a Portal token and chain-specific RPC endpoints.
     *
     * @param portalSessionToken A valid Portal session token
     * @param rpcEndpoints Map mapping numeric chain IDs to RPC URLs
     * Example: mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com")
     * @param chainId Optional default Chain ID. If not provided, SDK will attempt to select a suitable one from rpcEndpoints.
     * @throws RainError if initialization fails
     */
    @Throws(RainError::class)
    fun initializePortal(
        portalSessionToken: String = "",
        rpcEndpoints: Map<Int, String>,
        chainId: Int? = null
    )

    /**
     * Initializes the SDK with an authenticated Turnkey context and chain-specific RPC endpoints.
     *
     * Turnkey authentication (passkeys, auth proxy, OAuth, OTP) happens outside Rain via the
     * official Turnkey Kotlin SDK. Initialize `TurnkeyContext` in your `Application.onCreate()`,
     * complete login, then pass the singleton here to register it as Rain's wallet provider.
     *
     * @param turnkey The authenticated `TurnkeyContext` singleton.
     * @param rpcEndpoints Map of numeric chain IDs to RPC URLs.
     * @param chainId Optional default Chain ID. If not provided, SDK will select a suitable one from rpcEndpoints.
     * @param walletAddress Optional explicit EVM address override. When null, Rain uses the first
     *                      available Ethereum account from the Turnkey context.
     * @throws RainError if initialization fails (e.g., invalid RPC URLs or no usable EVM wallet).
     */
    @Throws(RainError::class)
    suspend fun initializeTurnkey(
        turnkey: TurnkeyContext,
        rpcEndpoints: Map<Int, String>,
        chainId: Int? = null,
        walletAddress: String? = null
    )

    /**
     * Wallet-agnostic init: validates [rpcEndpoints] and marks initialized, with no bundled provider.
     * Clears any active provider; call before [setWalletProvider]. Mirrors iOS `initialize(networkConfigs:)`.
     */
    @Throws(RainError::class)
    fun initialize(
        rpcEndpoints: Map<Int, String>
    )

    /**
     * Installs a custom [WalletProvider] implementation, overriding any provider that was
     * previously registered via [initializePortal] or [initializeTurnkey].
     *
     * Hosts can use this to bring their own provider — e.g. Coinbase, Privy, Dynamic, or
     * any future wallet stack — without going through Rain's bundled Portal / Turnkey
     * adapters. For that flow, call [initialize] first to configure networks in
     * wallet-agnostic mode, then install the provider here. Pass `null` to clear the active
     * provider (for example before swapping providers).
     *
     * Mirrors the iOS `setWalletProvider(_:)` public API.
     *
     * @param provider The custom provider to install, or `null` to clear.
     */
    fun setWalletProvider(provider: WalletProvider?)

    /**
     * Withdraws collateral from the Rain system.
     *
     * @param chainId The chain ID for the transaction
     * @param addresses All required addresses for the withdrawal
     * @param amount The amount to withdraw
     * @param decimals Token decimals
     * @param adminSignature Admin signature for authorization
     * @param nonce Optional nonce for the transaction
     * @param autoSend If true, automatically sends the transaction and returns hash. If false, returns prepared transaction data.
     * @return RainWithdrawResult containing either transactionHash (if autoSend=true) or transactionData (if autoSend=false)
     */
    @Throws(RainError::class)
    suspend fun withdrawCollateral(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: Double,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null,
        autoSend: Boolean = false
    ): RainWithdrawResult

    /**
     * Gets the current wallet address from the underlying provider.
     * @return Hex-encoded wallet address
     * @throws RainError if the address cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getWalletAddress(): String

    /**
     * Gets the wallet address for a specific chain. For EVM chains this matches [getWalletAddress]
     * (a hex address); for Solana chains (e.g. `RainChain.SOLANA_DEVNET`) it returns the
     * Turnkey Solana account's base58 address.
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
    ): Double

    /**
     * Estimates the total fee (in the chain's native token, e.g. ETH/AVAX) required to
     * execute a collateral withdrawal transaction.
     *
     * Builds + signs the EIP-712 payload, then `eth_estimateGas` against the controller (no broadcast).
     * Signs for real (a placeholder sig reverts the estimate), so estimate-then-withdraw signs twice;
     * iOS takes a caller-supplied signature instead.
     *
     * @param chainId The chain ID for the transaction.
     * @param addresses All required addresses for the withdrawal.
     * @param amount The amount to withdraw.
     * @param decimals Token decimals.
     * @param adminSignature Admin signature for authorization (matches the one passed to
     *                      [withdrawCollateral]).
     * @param nonce Optional nonce for the transaction. If `null`, the SDK resolves it.
     * @return Estimated withdrawal fee in the chain's native token.
     * @throws RainError if estimation fails.
     */
    @Throws(RainError::class)
    suspend fun estimateWithdrawalFee(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: Double,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null
    ): Double

    /**
     * Composes wallet-agnostic transaction parameters for a contract call.
     *
     * Pure helper: takes the sender / target / calldata and returns a [RainTransactionParameters]
     * struct with `value` pre-set to `"0x0"` (no native value). Leave gas, nonce, and fee
     * calculation to the caller or the active wallet provider.
     *
     * Mirrors the iOS `composeTransactionParameters` API. Returns a Rain-owned struct so the
     * public surface does not leak Portal- or Turnkey-specific types.
     *
     * @param walletAddress Address of the sender wallet.
     * @param contractAddress Target smart contract address.
     * @param transactionData Hex-encoded calldata.
     * @return A fully formed [RainTransactionParameters] object.
     */
    fun composeTransactionParameters(
        walletAddress: String,
        contractAddress: String,
        transactionData: String
    ): RainTransactionParameters

    /**
     * Sends native token (e.g., AVAX).
     *
     * @param chainId Network ID
     * @param toAddress Recipient's wallet address
     * @param amount Amount of token to send
     * @return RainTokenTransferResult containing the transaction hash
     */
    @Throws(RainError::class)
    suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amount: Double
    ): RainTokenTransferResult

    /**
     * Sends an ERC-20 token.
     *
     * @param chainId Network ID
     * @param contractAddress ERC-20 token contract address
     * @param toAddress Recipient's wallet address
     * @param amount Amount to send (in human-readable unit, e.g. 1.5 USDC)
     * @param decimals Number of decimals the token uses (e.g. 6 for USDC, 18 for most tokens)
     * @return RainTokenTransferResult containing the transaction hash
     */
    @Throws(RainError::class)
    suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: Double,
        decimals: Int
    ): RainTokenTransferResult

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
     * @param chainId The numeric chain ID.
     * @return One [Balance] per non-zero token plus the native balance.
     * @throws RainError if no wallet provider is set, or if the request fails.
     */
    @Throws(RainError::class)
    suspend fun getBalances(chainId: Int): List<Balance>

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
     * Note: built on [getBalances], which omits zero-balance contract tokens and includes the
     * native balance; this shim drops the native entry, so the result is non-zero ERC-20s only.
     *
     * @param chainId The numeric chain ID.
     * @return Map of token contract address to balance (Double).
     * @throws RainError if balances cannot be retrieved.
     */
    @Deprecated(
        message = "Use getBalances(chainId), which returns List<Balance> (native + contract " +
            "tokens) with exact precision. This shim drops the native entry and collapses to Double.",
        replaceWith = ReplaceWith("getBalances(chainId)")
    )
    @Throws(RainError::class)
    suspend fun getERC20Balances(chainId: Int): Map<String, Double> =
        getBalances(chainId)
            .mapNotNull { balance ->
                (balance.token as? Token.Contract)?.let { contract ->
                    contract.address to balance.decimalAmount.toDouble()
                }
            }
            .toMap()

    /**
     * Registers additional tokens with the SDK so their metadata (decimals / symbol) resolves
     * without an on-chain enrichment call. Retained across re-initialization; cleared by [reset].
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
     * Generates an Android Bitmap containing a QR code for a wallet address.
     * 
     * @param address Optional address to generate the QR code for. If null, the configured provider's wallet address will be retrieved and used.
     * @param width The width of the generated QR code bitmap in pixels. Defaults to 500.
     * @param height The height of the generated QR code bitmap in pixels. Defaults to 500.
     * @return A Bitmap containing the QR code.
     * @throws RainError If [address] is null and the provider's address cannot be retrieved.
     */
    @Throws(RainError::class)
    suspend fun generateAddressQRCode(
        address: String? = null,
        width: Int = 500,
        height: Int = 500
    ): Bitmap

    /**
     * Retrieves the transaction history for the specified chain.
     *
     * @param chainId The numeric chain ID
     * @param limit Optional maximum number of transactions to return
     * @param offset Optional number of transactions to skip for pagination
     * @param order Optional sort order (ASC or DESC)
     * @return RainTransactionResult containing a list of transactions
     * @throws RainError if the transaction history cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getTransactions(
        chainId: Int,
        limit: Int? = null,
        offset: Int? = null,
        order: RainTransactionOrder? = null
    ): RainTransactionResult

    companion object {
        /**
         * Default number of decimals for ERC20 tokens if not specified.
         */
        const val DEFAULT_ERC20_DECIMALS = 18
    }
}
