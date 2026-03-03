package com.rain.sdk.internal.provider

import com.rain.sdk.internal.core.PortalManager
import com.rain.sdk.utils.EthereumConverter
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256

/**
 * WalletProvider implementation using Portal SDK.
 */
internal class PortalWalletProvider(
    private val portalManager: PortalManager
) : WalletProvider {

    override suspend fun getAddress(): String {
        return portalManager.getAddress()
    }

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: Double
    ): String {
        val fromAddress = getAddress()
        val valueWeiHex = EthereumConverter.convertEthToWeiHex(amountInEth)

        // For native transfers, data is "0x"
        return portalManager.sendTransaction(
            chainId = chainId,
            from = fromAddress,
            to = toAddress,
            data = "0x",
            value = valueWeiHex
        )
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: Double,
        decimals: Int
    ): String {
        val fromAddress = getAddress()

        // Encode ERC-20 transfer(address, uint256) function call
        val tokenAmount = amount.toBigDecimal()
            .multiply(java.math.BigDecimal.TEN.pow(decimals))
            .toBigInteger()
        val function = Function(
            "transfer",
            listOf(Address(toAddress), Uint256(tokenAmount)),
            emptyList<TypeReference<*>>()
        )
        val data = FunctionEncoder.encode(function)

        // For ERC-20 transfers, the "to" is the contract address and value is 0x0
        return portalManager.sendTransaction(
            chainId = chainId,
            from = fromAddress,
            to = contractAddress,
            data = data,
            value = "0x0"
        )
    }

    override suspend fun getNativeBalance(chainId: Int): Double {
        return portalManager.getNativeBalance(chainId)
    }

    override suspend fun getERC20Balance(chainId: Int, tokenAddress: String, decimals: Int?): Double {
        return portalManager.getERC20Balance(chainId, tokenAddress, decimals)
    }

    override suspend fun getERC20Balances(chainId: Int): Map<String, Double> {
        return portalManager.getERC20Balances(chainId)
    }
}
