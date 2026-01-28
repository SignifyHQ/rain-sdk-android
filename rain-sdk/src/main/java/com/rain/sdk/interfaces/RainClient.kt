package com.rain.sdk.interfaces

import com.rain.sdk.RainError
import io.portalhq.android.Portal

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
     * Orchestrates the full withdrawal flow for collateral.
     *
     * This method performs:
     * - Preparing transaction calldata
     * - Managing or fetching the correct nonce (if not provided)
     * - Signing the transaction using the Portal wallet
     * - Submitting the transaction to the specified blockchain network
     *
     * @param chainId The target blockchain network identifier
     * @param collateralProxyAddress Address of the collateral proxy contract
     * @param tokenAddress ERC-20 token contract address
     * @param amount Human-readable token amount to withdraw
     * @param decimals Token decimals (e.g., 6 for USDC, 18 for most ERC-20)
     * @param recipientAddress Address that will receive the withdrawn funds
     * @param expiresAt Expiry timestamp for the withdrawal request (unix timestamp as string)
     * @param adminSalt Admin-generated salt for signature verification (hex string)
     * @param adminSignature Admin signature obtained from external source (hex string)
     * @param nonce Optional transaction nonce. If null, SDK will resolve the correct nonce
     *
     * @return The transaction hash of the submitted on-chain transaction
     * @throws RainError.SdkNotInitialized if SDK is not initialized
     * @throws RainError.InvalidConfig if any parameter is invalid
     * @throws RainError.UserRejected if user rejects signing or transaction
     * @throws RainError.NetworkError if network communication fails
     * @throws RainError.ProviderError if Portal SDK encounters an error
     */
    @Throws(RainError::class)
    suspend fun withdrawCollateral(
        chainId: Int,
        collateralProxyAddress: String,
        tokenAddress: String,
        amount: Double,
        decimals: Int,
        recipientAddress: String,
        expiresAt: String,
        adminSalt: String,
        adminSignature: String,
        nonce: java.math.BigInteger? = null
    ): String
}
