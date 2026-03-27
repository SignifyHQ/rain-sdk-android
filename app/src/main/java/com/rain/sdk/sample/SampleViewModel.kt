package com.rain.sdk.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.RainSdk
import com.rain.sdk.RainChain
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import io.portalhq.android.mpc.data.BackupConfigs
import io.portalhq.android.mpc.data.BackupMethods
import io.portalhq.android.mpc.data.PasswordStorageConfig
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class RpcOption {
  MAINNET, TESTNET
}

class SampleViewModel(
  private val rainClient: RainClient
) : ViewModel() {

  var pin by mutableStateOf("")
    private set

  var sessionToken by mutableStateOf("")
    private set

  var accessToken by mutableStateOf("")
    private set

  var isInitialized by mutableStateOf(rainClient.isInitialized)
    private set

  var statusText by mutableStateOf("Ready")
    private set

  var needsRecovery by mutableStateOf(false)
    private set

  fun onPinChanged(newValue: String) {
    pin = newValue
  }

  fun onTokenChanged(newToken: String) {
    sessionToken = newToken
  }

  fun onAccessTokenChanged(newToken: String) {
    accessToken = newToken
  }


  fun recoverWithPin() {
    if (!isInitialized) {
      statusText = "Please initialize SDK first (with a token) or logic might need adjustment"
      // Actually, recovery usually happens when we HAVE a session token but NO wallet on device
      if (sessionToken.isBlank()) {
        statusText = "Session token required for recovery"
        return
      }
    }

    if (pin.isBlank()) {
      statusText = "PIN required for recovery"
      return
    }

    statusText = "Fetching backup share..."
    viewModelScope.launch {
      try {
        val backupResponse = NetworkClient.fetchBackupShare(accessToken)
        if (backupResponse.result.isFailure) {
          statusText = "Failed to fetch backup share: ${backupResponse.result.exceptionOrNull()?.message}"
          return@launch
        }

        val cipherText = backupResponse.result.getOrThrow()
        statusText = "Recovering wallet..."

        // Use Portal SDK directly as approved
        val portal = rainClient.portal
        val backupConfigs = BackupConfigs(
          PasswordStorageConfig(password = pin)
        )

        portal.recoverWallet(
          cipherText,
          BackupMethods.Password,
          backupConfigs
        ) { status ->
          statusText = "Recovery status: $status"
        }

        statusText = "Recovery triggered! Check wallet address in a moment."
      } catch (e: Exception) {
        statusText = "Recovery failed: ${e.message}"
      }
    }
  }

  fun initializeSdk() {
    if (sessionToken.isBlank()) return

    try {
      val rpcConfig = mapOf(RainChain.AVALANCHE_TESTNET to "https://api.avax-test.network/ext/bc/C/rpc")

      rainClient.initializePortal(
        portalSessionToken = sessionToken,
        rpcEndpoints = rpcConfig,
        chainId = RainChain.AVALANCHE_TESTNET
      )

      isInitialized = rainClient.isInitialized
      statusText = "SDK Initialized Successfully!"
      needsRecovery = true
    } catch (e: Exception) {
      statusText = "Error: ${e.message}"
      isInitialized = false
    }
  }


  fun getWalletAddress() {
    if (!isInitialized) return

    viewModelScope.launch {
      try {
        val address = rainClient.portal.getAddress(PortalNamespace.EIP155) ?: "Address not found"
        statusText = "Address fetched: $address"
      } catch (e: Exception) {
        statusText = "Failed to get address: ${e.message}"
      }
    }
  }

  fun estimateGas() {
    if (!isInitialized) return
    if (accessToken.isBlank()) {
      statusText = "Error: Access Token is required"
      return
    }

    viewModelScope.launch {
      try {
        statusText = "Fetching Admin Signature for Gas Estimation..."
        val contractResponse = NetworkClient.fetchCollateralContract(accessToken)
        if (contractResponse.result.isFailure) {
          statusText = "Fetch contract failed: ${contractResponse.result.exceptionOrNull()?.message}"
          return@launch
        }
        val contract = contractResponse.result.getOrThrow()

        val chainId = contract.chainId.toInt()
        val tokenAddress = "0xD856a0585Da55e83d03ccb49Ef09D180494CfBAD"
        val amount = 0.1
        val decimals = 6
        val amountLong = (amount * Math.pow(10.0, decimals.toDouble())).toLong()
        val recipientAddress = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff"

        val response = NetworkClient.fetchAdminSignature(
          accessToken = accessToken,
          chainId = chainId.toLong(),
          token = tokenAddress.lowercase(),
          amount = amountLong,
          recipientAddress = recipientAddress
        )

        if (response.result.isFailure) {
          statusText = "Fetch failed: ${response.result.exceptionOrNull()?.message}"
          return@launch
        }

        val resultSignature = response.result.getOrThrow()
        val signature = resultSignature.first
        val expiresAt = resultSignature.second

        statusText = "Preparing transaction data..."

        // Step 1: Get transaction data without sending
        val withdrawResult = rainClient.withdrawCollateral(
          chainId = chainId,
          addresses = com.rain.sdk.models.RainWithdrawAddresses(
            proxyAddress = contract.address,
            controllerAddress = contract.controllerAddress,
            tokenAddress = tokenAddress,
            recipientAddress = recipientAddress
          ),
          amount = amount,
          decimals = decimals,
          adminSignature = com.rain.sdk.models.RainAdminSignature(
            salt = signature.salt,
            signature = signature.data,
            expiresAt = expiresAt
          ),
          autoSend = false // Get transaction data only
        )

        val transactionData = withdrawResult.transactionData
        if (transactionData == null) {
          statusText = "Error: Failed to get transaction data"
          return@launch
        }

        statusText = "Estimating gas..."

        // Step 2: Estimate gas with transaction data
        val fromAddress = rainClient.getAddress()
        val fee = rainClient.estimateGas(
          chainId = chainId,
          from = fromAddress,
          to = contract.controllerAddress,
          data = transactionData
        )

        statusText = "Estimated Gas Fee: $fee ETH"
      } catch (e: Exception) {
        statusText = "Gas estimation failed: ${e.message}"
        e.printStackTrace()
      }
    }
  }

  fun testWithdraw(context: android.content.Context) {
    if (!isInitialized) return
    if (accessToken.isBlank()) {
      statusText = "Error: Access Token is required"
      return
    }

    viewModelScope.launch {
      try {
        statusText = "Fetching Admin Signature..."
        // fetch contract
        val contractResponse = NetworkClient.fetchCollateralContract(accessToken)
        if (contractResponse.result.isFailure) {
          statusText = "Fetch contract failed: ${contractResponse.result.exceptionOrNull()?.message}"
          return@launch
        }
        val contract = contractResponse.result.getOrThrow()

        val chainId = contract.chainId.toInt()
        val tokenAddress = "0xD856a0585Da55e83d03ccb49Ef09D180494CfBAD" // USDC on Avalanche Testnet?
        val amount = 0.1
        val decimals = 6
        // IMPORTANT: Adjust logic to convert amount to base units based on decimals
        val amountLong = (amount * Math.pow(10.0, decimals.toDouble())).toLong()

        // TODO: Replace with real inputs
        val recipientAddress = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff"

        val response = NetworkClient.fetchAdminSignature(
          accessToken = accessToken,
          chainId = chainId.toLong(),
          token = tokenAddress.lowercase(),
          amount = amountLong,
          recipientAddress = recipientAddress
        )

        if (response.result.isFailure) {
          statusText = "Fetch failed: ${response.result.exceptionOrNull()?.message}"
          return@launch
        }

        val resultSignature = response.result.getOrThrow()
        val signature = resultSignature.first
        val expiresAt = resultSignature.second

        statusText = "Signature fetched! Processing withdrawal..."

        val result = rainClient.withdrawCollateral(
          chainId = chainId,
          addresses = RainWithdrawAddresses(
            proxyAddress = contract.address,
            controllerAddress = contract.controllerAddress,
            tokenAddress = tokenAddress,
            recipientAddress = recipientAddress
          ),
          amount = amount,
          decimals = decimals,
          adminSignature = RainAdminSignature(
            salt = signature.salt,
            signature = signature.data,
            expiresAt = expiresAt
          ),
          nonce = null, // Let SDK resolve
          autoSend = true // Auto send transaction
        )

        statusText = if (result.isAutoSent) {
          "Withdrawal successful!\nTx: ${result.transactionHash?.take(16)}..."
        } else {
          "Transaction data: ${result.transactionData?.take(32)}..."
        }
      } catch (e: Exception) {
        statusText = "Withdrawal failed: ${e.message}"
        e.printStackTrace()
      }
    }
  }

  fun clearSession() {
    sessionToken = ""
    accessToken = ""
    pin = ""
    statusText = "Session Cleared"
    isInitialized = false
  }
}
