package com.rain.sdk.internal.core

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.utils.RainAmountUtils
import com.rain.sdk.internal.utils.RainEip712Utils
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.internal.utils.RainHexUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function as Web3jFunction
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom

internal object RainTransactionBuilderImpl : RainTransactionBuilder {

  // Delegate for Web3j creation, allowing injection during tests
  internal var web3jFactory: (String) -> Web3j = { url -> Web3jProvider.getOrCreate(url) }

  internal fun resetFactory() {
    // Use real network for this test
    web3jFactory = { url -> Web3jProvider.getOrCreate(url) }
  }

  override suspend fun getLatestNonce(
    rpcUrl: String,
    proxyAddress: String
  ): BigInteger {
    val web3j = web3jFactory(rpcUrl)
    try {
      val validProxyAddress = validateAndChecksumAddress(proxyAddress, "proxyAddress")

      val function = Web3jFunction(
        RainConstants.FUNC_ADMIN_NONCE,
        emptyList(),
        listOf(object : TypeReference<Uint256>() {})
      )

      val encodedFunction = FunctionEncoder.encode(function)

      val response = withContext(Dispatchers.IO) {
        web3j.ethCall(
          Transaction.createEthCallTransaction(null, validProxyAddress, encodedFunction),
          DefaultBlockParameterName.LATEST
        ).sendAsync().get()
      }

      if (response.error != null) {
        throw RainError.InternalError("RPC Error: ${response.error.message}")
      }

      return FunctionReturnDecoder.decode(response.value, function.outputParameters)
        .firstOrNull()?.value as? BigInteger ?: BigInteger.ZERO

    } catch (e: Exception) {
      if (e is RainError) throw e
      throw RainError.NetworkError(cause = e)
    }
  }

  override suspend fun buildEIP712Message(
    chainId: Int,
    collateralProxyAddress: String,
    walletAddress: String,
    tokenAddress: String,
    amount: Double,
    decimals: Int,
    recipientAddress: String,
    nonce: BigInteger?
  ): Pair<String, String> {
    val validProxy = validateAndChecksumAddress(collateralProxyAddress, "collateralProxyAddress")
    val validWallet = validateAndChecksumAddress(walletAddress, "walletAddress")
    val validToken = validateAndChecksumAddress(tokenAddress, "tokenAddress")
    val validRecipient = validateAndChecksumAddress(recipientAddress, "recipientAddress")

    val rpcUrl = RainConfig.getInstance().getRpcUrl(chainId)

    // 1. Resolve Nonce
    val finalNonce = nonce ?: rpcUrl?.let {
      getLatestNonce(it, validProxy)
    } ?: throw RainError.InvalidConfig("Either nonce must be provided or RPC URL configured for chainId $chainId")

    // 2. Generate Salt
    val saltBytes = ByteArray(32).apply {
      SecureRandom().nextBytes(this)
    }
    val saltHex = Numeric.toHexString(saltBytes)

    // 3. Convert Amount to Base Units
    val amountBaseUnits = RainAmountUtils.toBaseUnits(amount, decimals)

    // 4. Build EIP-712 JSON
    val jsonString = RainEip712Utils.createEIP712Json(
      chainId = chainId.toLong(),
      verifyingContract = validProxy,
      saltHex = saltHex,
      walletAddress = validWallet,
      tokenAddress = validToken,
      recipientAddress = validRecipient,
      amount = amountBaseUnits,
      nonce = finalNonce
    )
    return Pair(jsonString, saltHex)
  }

  override fun buildWithdrawTransactionData(
    proxyAddress: String,
    tokenAddress: String,
    amount: Double,
    decimals: Int,
    recipientAddress: String,
    expiresAt: String,
    signatureData: String,
    adminSalt: String,
    adminSignature: String
  ): String {
    try {
      val validProxy = validateAndChecksumAddress(proxyAddress, "proxyAddress")
      val validToken = validateAndChecksumAddress(tokenAddress, "tokenAddress")
      val validRecipient = validateAndChecksumAddress(recipientAddress, "recipientAddress")

      val amountBaseUnits = RainAmountUtils.toBaseUnits(amount, decimals)

      val expiryTimestamp = try {
        expiresAt.toLong()
      } catch (e: NumberFormatException) {
        throw RainError.InvalidConfig("Invalid expiresAt format. Expected unix timestamp string.")
      }

      val function = Web3jFunction(
        RainConstants.FUNC_WITHDRAW_ASSET,
        listOf(
          Address(validProxy),
          Address(validToken),
          Uint256(amountBaseUnits),
          Address(validRecipient),
          Uint256(expiryTimestamp),
          Bytes32(RainHexUtils.hexToBytes(adminSalt)),
          DynamicBytes(RainHexUtils.hexToBytes(signatureData)),
          DynamicArray(Bytes32(RainHexUtils.hexToBytes(adminSalt))),
          DynamicArray(DynamicBytes(RainHexUtils.hexToBytes(adminSignature))),
          Bool(true)
        ),
        emptyList()
      )

      return FunctionEncoder.encode(function)
    } catch (e: Exception) {
      if (e is RainError) throw e
      throw RainError.InternalError("Failed to build transaction data: ${e.message}", e)
    }
  }

  private fun validateAndChecksumAddress(address: String, paramName: String): String {
    if (!RainHexUtils.isValidAddress(address)) {
      throw RainError.InvalidConfig("Invalid $paramName format: $address")
    }
    return RainHexUtils.toChecksumAddress(address)
  }
}
