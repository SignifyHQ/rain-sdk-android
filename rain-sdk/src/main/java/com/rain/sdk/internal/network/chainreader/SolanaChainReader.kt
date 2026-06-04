package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Solana implementation of [ChainReader] — the "future Solana reader" anticipated by the
 * interface docs. Reads native SOL balances over Solana JSON-RPC (`getBalance`).
 *
 * SPL token balances are out of scope for now: the contract/ERC-20 paths throw, mirroring how
 * the EVM reader rejects non-EVM input rather than returning misleading data.
 */
internal class SolanaChainReader(
    private val solanaRpcClient: SolanaRpcClient = SolanaRpcClient(),
    private val rpcUrlResolver: (Int) -> String?
) : ChainReader {

    /** Convenience constructor backed by a static `chainId → rpcUrl` map. */
    constructor(
        rpcEndpoints: Map<Int, String>,
        solanaRpcClient: SolanaRpcClient = SolanaRpcClient()
    ) : this(solanaRpcClient, { rpcEndpoints[it] })

    override suspend fun getNativeBalance(chainId: Int, walletAddress: String): Double {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress)
        val lamports = solanaRpcClient.getBalanceLamports(rpcUrl, walletAddress)
        return com.rain.sdk.internal.solana.SolanaConverter.lamportsToSol(lamports).toDouble()
    }

    override suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress)
        return when (token) {
            is Token.Native -> {
                val lamports = solanaRpcClient.getBalanceLamports(rpcUrl, walletAddress)
                val native = SolanaChains.NATIVE_CURRENCY
                Balance(
                    token = Token.Native,
                    chainId = chainId,
                    rawAmount = lamports,
                    decimals = native.decimals,
                    symbol = native.symbol,
                    name = native.name
                )
            }
            is Token.Contract -> throw RainError.InternalError(
                "SPL token balances are not supported on Solana chainId=$chainId"
            )
        }
    }

    override suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> = listOf(getBalance(chainId, walletAddress, Token.Native, null))

    override suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): Double = throw RainError.InternalError("ERC-20 reads are not supported on Solana")

    override suspend fun getDecimals(chainId: Int, tokenAddress: String): Int =
        throw RainError.InternalError("getDecimals is not supported on Solana")

    override suspend fun getSymbol(chainId: Int, tokenAddress: String): String? =
        throw RainError.InternalError("getSymbol is not supported on Solana")

    private fun resolveRpcUrl(chainId: Int): String {
        val rpcUrl = rpcUrlResolver(chainId)
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")
        if (rpcUrl.toHttpUrlOrNull() == null) {
            throw RainError.InvalidConfig("Invalid RPC URL for chainId=$chainId: $rpcUrl")
        }
        return rpcUrl
    }

    private fun validateAddress(address: String) {
        val valid = runCatching { Base58.decode(address).size == 32 }.getOrDefault(false)
        if (!valid) {
            throw RainError.InternalError("Invalid Solana wallet address: $address")
        }
    }
}
