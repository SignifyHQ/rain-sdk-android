package com.rain.sdk.interfaces

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

    @Throws(RainError::class)
    suspend fun withdrawCollateral(
        chainId: Int,
        collateralProxyAddress: String,
        controllerAddress: String,
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
