package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.rain.sdk.RainChain
import com.rain.sdk.RainError
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.config.RainConfig
import io.portalhq.android.Portal
import io.portalhq.android.provider.data.PortalRequestMethod
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.storage.mobile.PortalNamespace
import timber.log.Timber

internal class RainSdkManager : RainClient {

  private var _portal: Portal? = null

  override val isInitialized: Boolean
    get() = RainConfig.getInstance().isInitialized

  override val portal: Portal
    get() = _portal ?: throw RainError.SdkNotInitialized()

  override fun initializePortal(
    portalSessionToken: String,
    rpcEndpoints: Map<Int, String>,
    chainId: Int?,
  ) {
    try {
      // Validate and Map RPC endpoints
      if (rpcEndpoints.isEmpty()) {
        throw RainError.InvalidConfig("At least one RPC endpoint is required")
      }

      val config = RainConfig.getInstance()

      val eip155RpcEndpointsConfig = mutableMapOf<String, String>()

      for ((id, url) in rpcEndpoints) {
        if (id <= 0) {
          throw RainError.InvalidConfig("Invalid Chain ID: $id. Must be a positive integer.")
        }
        if (!URLUtil.isValidUrl(url)) {
          throw RainError.InvalidConfig("Invalid RPC URL for chainId $id: $url")
        }
        eip155RpcEndpointsConfig["${PortalNamespace.EIP155.value}:$id"] = url
        config.setRpcUrl(id, url)
      }

      // Determine Legacy Chain ID (Use provided one, or infer from endpoints)
      val legacyChainId = chainId ?: if (rpcEndpoints.containsKey(RainChain.AVALANCHE_MAINNET)) {
        RainChain.AVALANCHE_MAINNET
      } else {
        rpcEndpoints.keys.first()
      }

      // Initialize Portal instance
      if (portalSessionToken.isNotEmpty()) {
        _portal = createPortal(
          apiKey = portalSessionToken,
          legacyEthChainId = legacyChainId,
          rpcConfig = eip155RpcEndpointsConfig,
          featureFlags = FeatureFlags(isMultiBackupEnabled = true),
          autoApprove = true
        )

        Timber.d("Rain SDK: Registered Portal instance successfully")
      }

      config.markInitialized()
    } catch (e: RainError) {
      Timber.e(e, "Rain SDK: Initialization error")
      throw e
    } catch (e: Throwable) {
      Timber.e(e, "Rain SDK: Portal SDK error")
      throw RainError.ProviderError(e)
    }
  }

  override suspend fun withdrawCollateral(
    chainId: Int,
    collateralProxyAddress: String,
    tokenAddress: String,
    amount: Double,
    decimals: Int,
    recipientAddress: String,
    expiresAt: String,
    adminSalt: String,
    adminSignature: String,
    nonce: java.math.BigInteger?
  ): String {
    try {
      if (!isInitialized) {
        throw RainError.SdkNotInitialized()
      }

      if (chainId <= 0) {
        throw RainError.InvalidConfig("Invalid chainId: $chainId. Must be a positive integer.")
      }

      if (amount <= 0) {
        throw RainError.InvalidConfig("Invalid amount: $amount. Must be greater than zero.")
      }

      if (decimals < 0) {
        throw RainError.InvalidConfig("Invalid decimals: $decimals. Must be non-negative.")
      }

      // Step 2: Get Wallet Address from Portal
      Timber.d("Rain SDK: Getting wallet address from Portal")
      val walletAddress = try {
        portal.getAddress(PortalNamespace.EIP155)
          ?: throw RainError.ProviderError(null)
      } catch (e: Exception) {
        Timber.e(e, "Rain SDK: Failed to get wallet address")
        throw RainError.ProviderError(e)
      }

      Timber.d("Rain SDK: Wallet address: $walletAddress")

      // Step 3: Prepare EIP-712 Message
      Timber.d("Rain SDK: Building EIP-712 message")
      val (typedDataJson, userSalt) = RainTransactionBuilderImpl.buildEIP712Message(
        chainId = chainId,
        collateralProxyAddress = collateralProxyAddress,
        walletAddress = walletAddress,
        tokenAddress = tokenAddress,
        amount = amount,
        decimals = decimals,
        recipientAddress = recipientAddress,
        nonce = nonce
      )

      // Step 4: Sign EIP-712 Message with Portal
      Timber.d("Rain SDK: Requesting signature from Portal")
      val userSignature = try {
        portal.request(
          chainId = "${PortalNamespace.EIP155.value}:$chainId",
          method = PortalRequestMethod.eth_signTypedData_v4,
          params = listOf(walletAddress, typedDataJson)
        ).result.toString()
      } catch (e: Exception) {
        Timber.e(e, "Rain SDK: Failed to request transaction")
      }

      Timber.d("Rain SDK: Signature obtained: $userSignature")

      // Step 5: Build Transaction Data
      Timber.d("Rain SDK: Building transaction data")
      val transactionData = RainTransactionBuilderImpl.buildWithdrawTransactionData(
        proxyAddress = collateralProxyAddress,
        tokenAddress = tokenAddress,
        amount = amount,
        decimals = decimals,
        recipientAddress = recipientAddress,
        expiresAt = expiresAt,
        signatureData = userSignature,
        adminSalt = adminSalt,
        adminSignature = adminSignature
      )

      // Step 6: Submit Transaction
      Timber.d("Rain SDK: Submitting transaction to blockchain on chain $chainId")
      val txHash = try {
        val response = portal.request(
          chainId = "${PortalNamespace.EIP155.value}:$chainId",
          method = PortalRequestMethod.eth_sendTransaction,
          params = listOf(
            mapOf(
              "from" to walletAddress,
              "to" to collateralProxyAddress,
              "data" to transactionData,
              "value" to "0x0"
            )
          )
        )
        response.result.toString()
      } catch (e: Exception) {
        Timber.e(e, "Rain SDK: Failed to send transaction")
        // Check if user rejected
        if (e.message?.contains("user", ignoreCase = true) == true ||
          e.message?.contains("reject", ignoreCase = true) == true ||
          e.message?.contains("cancel", ignoreCase = true) == true) {
          throw RainError.UserRejected()
        }
        // Check for insufficient funds
        if (e.message?.contains("insufficient", ignoreCase = true) == true) {
          throw RainError.InsufficientFunds()
        }
        throw RainError.ProviderError(e)
      }

      Timber.d("Rain SDK: Transaction submitted successfully. Hash: $txHash")

      // Step 7: Return Result
      return txHash

    } catch (e: RainError) {
      // Re-throw RainError as-is
      throw e
    } catch (e: Exception) {
      // Wrap unexpected errors
      Timber.e(e, "Rain SDK: Unexpected error in withdrawCollateral")
      throw RainError.InternalError("Withdraw collateral failed: ${e.message}", e)
    }
  }

  internal fun createPortal(
    apiKey: String,
    legacyEthChainId: Int,
    rpcConfig: Map<String, String>,
    featureFlags: FeatureFlags,
    autoApprove: Boolean
  ): Portal {
    return Portal(
      apiKey = apiKey,
      legacyEthChainId = legacyEthChainId,
      rpcConfig = rpcConfig,
      featureFlags = featureFlags,
      autoApprove = autoApprove
    )
  }
}
