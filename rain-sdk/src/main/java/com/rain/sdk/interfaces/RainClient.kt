package com.rain.sdk.interfaces

import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainTokenTransferResult
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainWithdrawResult
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.internal.error.RainError
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
    suspend fun getAddress(): String

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
     * Gets the native token balance (e.g. AVAX) for the current wallet.
     *
     * @param chainId The numeric chain ID (e.g. 43114 for Avalanche Mainnet)
     * @return Native token balance in Ether units (Double)
     * @throws RainError if the balance cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getNativeBalance(chainId: Int): Double

    /**
     * Gets the balance of a specific ERC20 token for the current wallet.
     *
     * @param chainId The numeric chain ID (e.g. 43114 for Avalanche Mainnet)
     * @param tokenAddress The contract address of the ERC20 token
     * @param decimals Number of decimals the token uses. Defaults to [DEFAULT_ERC20_DECIMALS].
     * @return Token balance as a Double (with decimals already applied)
     * @throws RainError if the balance cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        decimals: Int? = DEFAULT_ERC20_DECIMALS
    ): Double

    /**
     * Gets all ERC20 token balances for the current wallet on the given network.
     *
     * @param chainId The numeric chain ID
     * @return Map of token contract address to balance (Double)
     * @throws RainError if balances cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getERC20Balances(chainId: Int): Map<String, Double>

    /**
     * Gets all balances for the current wallet on the given network.
     *
     * Native balance is stored under the empty-string key `""`.
     *
     * @param chainId The numeric chain ID
     * @return Map of token contract address to balance (Double), plus native balance under `""`
     * @throws RainError if balances cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getBalances(chainId: Int): Map<String, Double>

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
