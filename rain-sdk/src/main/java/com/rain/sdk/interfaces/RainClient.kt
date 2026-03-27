package com.rain.sdk.interfaces

import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainWithdrawResult
import com.rain.sdk.internal.error.RainError
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.BackupConfigs

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
        nonce: java.math.BigInteger? = null,
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
}
