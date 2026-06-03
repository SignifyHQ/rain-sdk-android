package com.rain.sdk.internal.provider

import com.rain.sdk.internal.core.PortalManager
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
import com.rain.sdk.utils.EthereumConverter
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigDecimal

/**
 * WalletProvider implementation using Portal SDK.
 *
 * Balance reads build rich [Balance] values via [PortalManager], resolving token metadata
 * (decimals / symbol / name) through the shared [tokenStore].
 */
internal class PortalWalletProvider(
  private val portalManager: PortalManager,
  private val tokenStore: TokenMetadataStore
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
      .multiply(BigDecimal.TEN.pow(decimals))
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

  override suspend fun getBalance(chainId: Int, token: Token): Balance {
    return portalManager.getBalance(chainId, token, tokenStore)
  }

  override suspend fun getBalances(chainId: Int): List<Balance> {
    return portalManager.getBalances(chainId, tokenStore)
  }

  override suspend fun getTransactions(
    chainId: Int,
    limit: Int?,
    offset: Int?,
    order: RainTransactionOrder?
  ): RainTransactionResult {
    return portalManager.getTransactions(chainId, limit, offset, order)
  }

  override suspend fun signTypedData(
    chainId: Int,
    walletAddress: String,
    typedDataJson: String
  ): String {
    return portalManager.signTypedData(chainId, walletAddress, typedDataJson)
  }

  override suspend fun sendTransaction(
    chainId: Int,
    from: String,
    to: String,
    data: String,
    value: String
  ): String {
    return portalManager.sendTransaction(chainId, from, to, data, value)
  }

  override suspend fun estimateTransactionFee(
    chainId: Int,
    from: String,
    to: String,
    data: String,
    value: String
  ): Double {
    return portalManager.estimateTransactionFee(chainId, from, to, data, value)
  }
}
