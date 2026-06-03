package com.rain.sdk.internal.helpers

import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
import java.math.BigInteger

/**
 * Provider-agnostic stub for manager-contract tests. Records calls and returns
 * configured values so tests can verify `RainSdkManager` routes to the active
 * `WalletProvider` and surfaces its result, independent of Portal- or
 * Turnkey-specific behavior.
 */
internal open class StubWalletProvider : WalletProvider {

    data class SendTokenCall(
        val chainId: Int,
        val contractAddress: String,
        val toAddress: String,
        val amount: Double,
        val decimals: Int
    )

    data class GetBalanceCall(
        val chainId: Int,
        val token: Token
    )

    data class GetTransactionsCall(
        val chainId: Int,
        val limit: Int?,
        val offset: Int?,
        val order: RainTransactionOrder?
    )

    data class SignTypedDataCall(
        val chainId: Int,
        val walletAddress: String,
        val typedDataJson: String
    )

    data class SendTransactionCall(
        val chainId: Int,
        val from: String,
        val to: String,
        val data: String,
        val value: String
    )

    data class EstimateTransactionFeeCall(
        val chainId: Int,
        val from: String,
        val to: String,
        val data: String,
        val value: String
    )

    var addressToReturn: String = TestFixtures.WALLET_ADDRESS
    var balanceToReturn: Balance = Balance(
        token = Token.Native,
        chainId = 1,
        rawAmount = BigInteger.ZERO,
        decimals = 18,
        symbol = "ETH",
        name = "Ether"
    )
    var balancesToReturn: List<Balance> = emptyList()
    var transactionsToReturn: RainTransactionResult = RainTransactionResult(transactions = emptyList())
    var sendNativeTokenHashToReturn: String = "0x" + "0".repeat(64)
    var sendTokenHashToReturn: String = "0x" + "0".repeat(64)
    var sendTransactionHashToReturn: String = "0x" + "0".repeat(64)
    var signTypedDataToReturn: String = "0x" + "0".repeat(130)
    var estimateTransactionFeeToReturn: Double = 0.0

    val sendNativeTokenCalls = mutableListOf<SendTokenCall>()
    val sendTokenCalls = mutableListOf<SendTokenCall>()
    val getBalanceCalls = mutableListOf<GetBalanceCall>()
    val getBalancesCalls = mutableListOf<Int>()
    val getTransactionsCalls = mutableListOf<GetTransactionsCall>()
    val signTypedDataCalls = mutableListOf<SignTypedDataCall>()
    val sendTransactionCalls = mutableListOf<SendTransactionCall>()
    val estimateTransactionFeeCalls = mutableListOf<EstimateTransactionFeeCall>()

    override suspend fun getAddress(): String = addressToReturn

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: Double
    ): String {
        sendNativeTokenCalls += SendTokenCall(
            chainId = chainId,
            contractAddress = "",
            toAddress = toAddress,
            amount = amountInEth,
            decimals = 18
        )
        return sendNativeTokenHashToReturn
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: Double,
        decimals: Int
    ): String {
        sendTokenCalls += SendTokenCall(chainId, contractAddress, toAddress, amount, decimals)
        return sendTokenHashToReturn
    }

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        getBalanceCalls += GetBalanceCall(chainId, token)
        return balanceToReturn
    }

    override suspend fun getBalances(chainId: Int): List<Balance> {
        getBalancesCalls += chainId
        return balancesToReturn
    }

    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): RainTransactionResult {
        getTransactionsCalls += GetTransactionsCall(chainId, limit, offset, order)
        return transactionsToReturn
    }

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        signTypedDataCalls += SignTypedDataCall(chainId, walletAddress, typedDataJson)
        return signTypedDataToReturn
    }

    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String {
        sendTransactionCalls += SendTransactionCall(chainId, from, to, data, value)
        return sendTransactionHashToReturn
    }

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): Double {
        estimateTransactionFeeCalls += EstimateTransactionFeeCall(chainId, from, to, data, value)
        return estimateTransactionFeeToReturn
    }
}
