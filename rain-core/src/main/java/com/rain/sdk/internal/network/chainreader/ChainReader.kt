package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo

/**
 * Provider-agnostic, read-only on-chain query surface.
 *
 * One place for reading state from any chain the SDK consumer has configured. Used by
 * `TurnkeyWalletProvider` to fill in balances on chains outside the Turnkey `get-balances`
 * allowlist, and available to any future wallet-provider adapter that needs the same
 * fallback.
 *
 * V1 surface is balances. Future reads (allowances, generic `eth_call` wrappers) belong on
 * this interface so call sites don't fragment.
 *
 * Implementations exist per chain family. [EvmChainReader] covers all EIP-155 chains via
 * JSON-RPC. A future Solana/Stellar reader can implement this alongside it; until then,
 * `chainId: Int` matches the rest of the SDK's EVM-centric typing.
 */
internal interface ChainReader {
    /**
     * Native balance (e.g. ETH on Ethereum, AVAX on Avalanche). Result is in
     * human-readable form (e.g. `1.5` for 1.5 ETH).
     */
    suspend fun getNativeBalance(chainId: Int, walletAddress: String): Double

    /**
     * Single ERC-20 balance via `balanceOf(address)`. [decimals] defaults to
     * [com.rain.sdk.interfaces.RainClient.Companion.DEFAULT_ERC20_DECIMALS] when null.
     */
    suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): Double

    /**
     * Batched balances for many tokens on one chain, in a single round-trip when possible.
     *
     * @param tokens ERC-20 tokens to query. The native balance is always included.
     * @return One [Balance] per successfully-read token plus the native balance
     *   ([Token.Native]). Tokens whose `balanceOf` reverts are omitted; zero balances are
     *   retained (zero-filtering is the caller's responsibility).
     */
    suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance>

    /**
     * Reads a single balance (native or a contract token) as a rich [Balance].
     *
     * @param tokenInfo Pre-resolved metadata for a [Token.Contract] (decimals / symbol /
     *   name); ignored for [Token.Native]. When `null` for a contract token, defaults are used.
     */
    suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance

    /** Reads an ERC-20 token's `decimals()`. Used to enrich tokens not in the registry. */
    suspend fun getDecimals(chainId: Int, tokenAddress: String): Int

    /**
     * Reads an ERC-20 token's `symbol()`. Returns `null` if the call reverts or returns an
     * undecodable payload. Used to enrich tokens not in the registry.
     */
    suspend fun getSymbol(chainId: Int, tokenAddress: String): String?

    /**
     * Reads an ERC-20 token's `name()`. Returns `null` if the call reverts or returns an
     * undecodable payload. Used to enrich tokens not in the registry.
     */
    suspend fun getName(chainId: Int, tokenAddress: String): String?
}
