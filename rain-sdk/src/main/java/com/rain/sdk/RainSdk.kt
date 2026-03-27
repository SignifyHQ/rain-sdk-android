package com.rain.sdk

import androidx.annotation.VisibleForTesting
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.core.RainSdkManager
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.error.RainError
import io.portalhq.android.Portal

/**
 * Main entry point for Rain SDK.
 * 
 * This class provides access to Rain SDK functionality including:
 * - Portal MPC wallet integration (Full Mode)
 * - Transaction builder utilities (Wallet-agnostic Mode)
 * 
 * Usage:
 * ```kotlin
 * // Initialize SDK
 * RainSdk.getInstance().client.initializePortal(...)
 * 
 * // Access Portal wallet
 * val portal = RainSdk.getInstance().portal
 * 
 * // Use transaction builder
 * val txBuilder = RainSdk.getInstance().transactionBuilder
 * ```
 * 
 * @see RainClient
 * @see RainTransactionBuilder
 */
class RainSdk private constructor(
    private val sdkManager: RainClient
) {
    
    /**
     * Access to Rain client operations.
     */
    val client: RainClient get() = sdkManager
    
    /**
     * Transaction builder for wallet-agnostic operations.
     * 
     * @throws com.rain.sdk.internal.error.RainError.SdkNotInitialized if SDK hasn't been initialized
     */
    val transactionBuilder: RainTransactionBuilder
        get() {
            if (!RainConfig.getInstance().isInitialized) {
                throw RainError.SdkNotInitialized()
            }
            return RainTransactionBuilderImpl
        }
    
    /**
     * Convenience property for Portal access.
     * 
     * @throws RainError.SdkNotInitialized if Portal hasn't been initialized
     */
    val portal: Portal get() = sdkManager.portal
    
    /**
     * Checks if the SDK has been initialized.
     */
    val isInitialized: Boolean get() = sdkManager.isInitialized
    
    companion object {
        @Volatile
        private var instance: RainSdk? = null
        
        private val lock = Any()
        
        /**
         * Gets the singleton instance of Rain SDK.
         * 
         * The instance is created lazily on first access.
         * Thread-safe using double-checked locking pattern.
         * 
         * @return The singleton RainSdk instance
         */
        fun getInstance(): RainSdk {
            return instance ?: synchronized(lock) {
                instance ?: createInstance()
            }
        }
        
        /**
         * Internal method to create the SDK instance.
         * Allows dependency injection for testing.
         */
        private fun createInstance(manager: RainClient = RainSdkManager()): RainSdk {
            return RainSdk(manager).also { instance = it }
        }
        
        /**
         * Resets the SDK instance.
         * 
         * This clears all configuration and re-initializes the singleton.
         * Use for testing or when reinitializing the SDK.
         * 
         * @internal For testing purposes only
         */
        @VisibleForTesting
        fun reset() {
            synchronized(lock) {
                instance = null
                RainConfig.reset()
            }
        }
        
        /**
         * Internal method for dependency injection in tests.
         * Allows injecting a mock RainClient.
         * 
         * @internal For testing purposes only
         */
        @VisibleForTesting
        internal fun setTestInstance(manager: RainClient) {
            synchronized(lock) {
                instance = RainSdk(manager)
            }
        }
    }
}

