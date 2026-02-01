package com.rain.sdk.internal.core

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.utils.RainAmountUtils
import com.rain.sdk.internal.utils.RainEip712Utils
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.internal.utils.RainHexUtils
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
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
import java.time.Instant
import java.util.Base64
import kotlin.time.ExperimentalTime

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
      val validProxyAddress = RainHexUtils.validateAndChecksum(proxyAddress, "proxyAddress")

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
    addresses: RainWithdrawAddresses,
    walletAddress: String,
    amount: Double,
    decimals: Int,
    nonce: BigInteger?
  ): Pair<String, ByteArray> {
    val validatedAddresses = addresses.validated()
    val validWallet = RainHexUtils.validateAndChecksum(walletAddress, "walletAddress")

    val rpcUrl = RainConfig.getInstance().getRpcUrl(chainId)

    // 1. Resolve Nonce
    val finalNonce = nonce ?: rpcUrl?.let {
      getLatestNonce(it, validatedAddresses.proxyAddress)
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
      verifyingContract = validatedAddresses.proxyAddress,
      saltHex = saltHex,
      walletAddress = validWallet,
      tokenAddress = validatedAddresses.tokenAddress,
      recipientAddress = validatedAddresses.recipientAddress,
      amount = amountBaseUnits,
      nonce = finalNonce
    )
    return Pair(jsonString, saltBytes)
  }

  override fun buildWithdrawTransactionData(
    addresses: RainWithdrawAddresses,
    amount: Double,
    decimals: Int,
    saltBytes: ByteArray,
    signatureData: String,
    adminSignature: RainAdminSignature
  ): String {
    try {
      val validatedAddresses = addresses.validated()

      val amountBaseUnits = RainAmountUtils.toBaseUnits(amount, decimals)

      val expiryTimestamp = try {
        Instant.parse(adminSignature.expiresAt).toEpochMilli() / 1000
      } catch (e: Exception) {
        throw RainError.InvalidConfig("Invalid expiresAt format: ${adminSignature.expiresAt}. Exception: $e")
      }

      val function = Web3jFunction(
        RainConstants.FUNC_WITHDRAW_ASSET,
        listOf(
          Address(validatedAddresses.proxyAddress),
          Address(validatedAddresses.tokenAddress),
          Uint256(amountBaseUnits),
          Address(validatedAddresses.recipientAddress),
          Uint256(expiryTimestamp),
          Bytes32(Base64.getDecoder().decode(adminSignature.salt)),
          DynamicBytes(RainHexUtils.hexToBytes(adminSignature.signature)),
          DynamicArray(Bytes32(saltBytes)),
          DynamicArray(DynamicBytes(RainHexUtils.hexToBytes(signatureData))),
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

}
