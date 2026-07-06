package com.rain.sdk.internal.helpers

import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import java.math.BigInteger

/**
 * In-memory [ChainReader] stub for routing tests in `TurnkeyWalletProvider*Test` and
 * `RainSdkManager*Test`. Records every call so tests can assert that the adapter routed the
 * request through here rather than through Turnkey's balance indexer.
 */
internal class MockChainReader(
    var nativeBalance: Double = 0.0,
    var erc20Balance: Double = 0.0,
    var balances: List<Balance> = emptyList(),
    var balance: Balance? = null,
    var decimals: Int = 18,
    var symbol: String? = null,
    var name: String? = null
) : ChainReader {

    data class NativeCall(val chainId: Int, val walletAddress: String)
    data class Erc20Call(
        val chainId: Int,
        val tokenAddress: String,
        val walletAddress: String,
        val decimals: Int?
    )
    data class BalancesCall(
        val chainId: Int,
        val walletAddress: String,
        val tokens: List<TokenInfo>
    )
    data class BalanceCall(
        val chainId: Int,
        val walletAddress: String,
        val token: Token,
        val tokenInfo: TokenInfo?
    )
    data class DecimalsCall(val chainId: Int, val tokenAddress: String)
    data class SymbolCall(val chainId: Int, val tokenAddress: String)
    data class NameCall(val chainId: Int, val tokenAddress: String)

    val nativeCalls = mutableListOf<NativeCall>()
    val erc20Calls = mutableListOf<Erc20Call>()
    val balancesCalls = mutableListOf<BalancesCall>()
    val balanceCalls = mutableListOf<BalanceCall>()
    val decimalsCalls = mutableListOf<DecimalsCall>()
    val symbolCalls = mutableListOf<SymbolCall>()
    val nameCalls = mutableListOf<NameCall>()

    override suspend fun getNativeBalance(chainId: Int, walletAddress: String): Double {
        nativeCalls += NativeCall(chainId, walletAddress)
        return nativeBalance
    }

    override suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): Double {
        erc20Calls += Erc20Call(chainId, tokenAddress, walletAddress, decimals)
        return erc20Balance
    }

    override suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> {
        balancesCalls += BalancesCall(chainId, walletAddress, tokens)
        return balances
    }

    override suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance {
        balanceCalls += BalanceCall(chainId, walletAddress, token, tokenInfo)
        return balance ?: Balance(
            token = token,
            chainId = chainId,
            rawAmount = BigInteger.ZERO,
            decimals = tokenInfo?.decimals ?: 18,
            symbol = tokenInfo?.symbol,
            name = tokenInfo?.name
        )
    }

    override suspend fun getDecimals(chainId: Int, tokenAddress: String): Int {
        decimalsCalls += DecimalsCall(chainId, tokenAddress)
        return decimals
    }

    override suspend fun getSymbol(chainId: Int, tokenAddress: String): String? {
        symbolCalls += SymbolCall(chainId, tokenAddress)
        return symbol
    }

    override suspend fun getName(chainId: Int, tokenAddress: String): String? {
        nameCalls += NameCall(chainId, tokenAddress)
        return name
    }
}
