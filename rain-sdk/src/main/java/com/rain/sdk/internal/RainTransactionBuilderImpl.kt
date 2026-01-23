package com.rain.sdk.internal

import com.rain.sdk.RainError
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.utils.RainAmountUtils
import com.rain.sdk.utils.RainEip712Utils
import com.rain.sdk.utils.RainHexUtils
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
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom

internal object RainTransactionBuilderImpl : RainTransactionBuilder {

    override suspend fun getWithdrawalNonce(
        rpcUrl: String,
        proxyAddress: String
    ): BigInteger {
        val web3j = Web3j.build(HttpService(rpcUrl))
        try {
            val validProxyAddress = validateAndChecksumAddress(proxyAddress, "proxyAddress")

            val function = Web3jFunction(
                "adminNonce",
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
            throw RainError.NetworkError(e)
        } finally {
            web3j.shutdown()
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
        nonce: BigInteger?,
        rpcUrl: String?
    ): Pair<String, String> {
        val validProxy = validateAndChecksumAddress(collateralProxyAddress, "collateralProxyAddress")
        val validWallet = validateAndChecksumAddress(walletAddress, "walletAddress")
        val validToken = validateAndChecksumAddress(tokenAddress, "tokenAddress")
        val validRecipient = validateAndChecksumAddress(recipientAddress, "recipientAddress")

        // 1. Resolve Nonce
        val finalNonce = nonce ?: rpcUrl?.let {
            getWithdrawalNonce(it, validProxy)
        } ?: throw RainError.InvalidConfig("Either nonce or rpcUrl must be provided")

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
                "withdrawAsset",
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
