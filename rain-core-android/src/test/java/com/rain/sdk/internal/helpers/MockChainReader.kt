package com.rain.sdk.internal.helpers

import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import java.math.BigDecimal
import java.math.BigInteger

/**
 * In-memory [ChainReader] stub for routing tests in `TurnkeyWalletProvider*Test` and
 * `RainSdkManager*Test`. Records every call so tests can assert that the adapter routed the
 * request through here rather than through Turnkey's balance indexer.
 */
internal class MockChainReader(
    var nativeBalance: BigDecimal = BigDecimal.ZERO,
    var erc20Balance: BigDecimal = BigDecimal.ZERO,
    var balances: List<Balance> = emptyList(),
    var balance: Balance? = null,
    var decimals: Int = 18,
    var symbol: String? = null,
    var name: String? = null,
    /** When set, every metadata read (decimals/symbol/name) throws it — drives failed-read paths. */
    var metadataError: Throwable? = null,
    var allowance: BigInteger = BigInteger.ZERO,
    /** When set, the allowance read throws it. */
    var allowanceError: Throwable? = null
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
    data class AllowanceCall(
        val chainId: Int,
        val tokenAddress: String,
        val owner: String,
        val spender: String
    )

    val nativeCalls = mutableListOf<NativeCall>()
    val erc20Calls = mutableListOf<Erc20Call>()
    val balancesCalls = mutableListOf<BalancesCall>()
    val balanceCalls = mutableListOf<BalanceCall>()
    val decimalsCalls = mutableListOf<DecimalsCall>()
    val symbolCalls = mutableListOf<SymbolCall>()
    val nameCalls = mutableListOf<NameCall>()
    val allowanceCalls = mutableListOf<AllowanceCall>()

    override suspend fun getNativeBalance(chainId: Int, walletAddress: String): BigDecimal {
        nativeCalls += NativeCall(chainId, walletAddress)
        return nativeBalance
    }

    override suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): BigDecimal {
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
        metadataError?.let { throw it }
        return decimals
    }

    override suspend fun getSymbol(chainId: Int, tokenAddress: String): String? {
        symbolCalls += SymbolCall(chainId, tokenAddress)
        metadataError?.let { throw it }
        return symbol
    }

    override suspend fun getName(chainId: Int, tokenAddress: String): String? {
        nameCalls += NameCall(chainId, tokenAddress)
        metadataError?.let { throw it }
        return name
    }

    override suspend fun getErc20Allowance(
        chainId: Int,
        tokenAddress: String,
        owner: String,
        spender: String
    ): BigInteger {
        allowanceCalls += AllowanceCall(chainId, tokenAddress, owner, spender)
        allowanceError?.let { throw it }
        return allowance
    }
}
