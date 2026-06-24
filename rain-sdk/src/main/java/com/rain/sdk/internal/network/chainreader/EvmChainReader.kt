package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.constants.TokenRegistry
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.utils.isValidEthereumAddress
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.utils.EthereumConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/**
 * EVM implementation of [ChainReader].
 *
 * Primary path — Multicall3 (`aggregate3`) for batched balance reads, so a wallet holding
 * N tokens on a chain costs one RPC round-trip regardless of N. Used when
 * [isMulticall3CanonicallyDeployed] returns true for the target chain.
 *
 * Fallback — parallel `eth_call` (`balanceOf`) and `eth_getBalance`, used on chains outside
 * the canonical deployment list.
 *
 * Native balance failures are fatal — they indicate a chain-wide problem (bad RPC, wrong
 * chain ID). Per-token failures (a single `balanceOf` reverts) are logged via Timber and
 * the token is omitted from the result, so one bad [TokenRegistry] entry doesn't break
 * balance reads for the whole chain.
 */
internal class EvmChainReader(
    private val jsonRpcClient: JsonRpcClient = JsonRpcClient(),
    private val rpcUrlResolver: (Int) -> String?
) : ChainReader {

    private companion object {
        /** Decimals used for native balances. Every chain the SDK targets today uses 18. */
        const val DEFAULT_NATIVE_DECIMALS = 18
    }

    /** Convenience constructor backed by a static `chainId → rpcUrl` map. */
    constructor(
        rpcEndpoints: Map<Int, String>,
        jsonRpcClient: JsonRpcClient = JsonRpcClient()
    ) : this(jsonRpcClient, { rpcEndpoints[it] })

    override suspend fun getNativeBalance(chainId: Int, walletAddress: String): Double {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")
        val hex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_getBalance",
            params = listOf(walletAddress, "latest")
        )
        return EthereumConverter.convertHexToDouble(hex, DEFAULT_NATIVE_DECIMALS)
    }

    override suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): Double {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")
        validateAddress(tokenAddress, "token address")
        val callData = Multicall3.encodeBalanceOf(walletAddress)
        val callParams = mapOf("to" to tokenAddress, "data" to callData)
        val hex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_call",
            params = listOf(callParams, "latest")
        )
        return EthereumConverter.convertHexToDouble(
            hex,
            decimals ?: RainClient.DEFAULT_ERC20_DECIMALS
        )
    }

    override suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")
        return if (isMulticall3CanonicallyDeployed(chainId)) {
            fetchViaMulticall3(rpcUrl, chainId, walletAddress, tokens)
        } else {
            fetchViaParallelCalls(rpcUrl, chainId, walletAddress, tokens)
        }
    }

    override suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")

        return when (token) {
            is Token.Native -> {
                val hex = jsonRpcClient.callForHexResult(
                    rpcUrl = rpcUrl,
                    method = "eth_getBalance",
                    params = listOf(walletAddress, "latest")
                )
                nativeBalance(chainId, hex)
            }
            is Token.Contract -> {
                validateAddress(token.address, "token address")
                val callData = Multicall3.encodeBalanceOf(walletAddress)
                val hex = ethCall(rpcUrl, token.address, callData)
                val info = tokenInfo ?: TokenInfo(
                    chainId = chainId,
                    address = token.address,
                    symbol = null,
                    decimals = RainClient.DEFAULT_ERC20_DECIMALS
                )
                tokenBalance(chainId, info, hex)
            }
        }
    }

    override suspend fun getDecimals(chainId: Int, tokenAddress: String): Int {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(tokenAddress, "token address")
        val hex = ethCall(rpcUrl, tokenAddress, "0x" + ERC20Selectors.DECIMALS)
        return EthereumConverter.parseHexToInt(hex)
    }

    override suspend fun getSymbol(chainId: Int, tokenAddress: String): String? {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(tokenAddress, "token address")
        val hex = ethCall(rpcUrl, tokenAddress, "0x" + ERC20Selectors.SYMBOL)
        return EthereumConverter.parseHexToString(hex)
    }

    override suspend fun getName(chainId: Int, tokenAddress: String): String? {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(tokenAddress, "token address")
        val hex = ethCall(rpcUrl, tokenAddress, "0x" + ERC20Selectors.NAME)
        return EthereumConverter.parseHexToString(hex)
    }

    /**
     * Issues a raw `eth_call` and returns the hex result. For read functions with
     * pre-encoded [data] (no-arg selectors like `decimals()` / `symbol()`, or `balanceOf`).
     */
    private suspend fun ethCall(rpcUrl: String, to: String, data: String): String {
        val callParams = mapOf("to" to to, "data" to data)
        return jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_call",
            params = listOf(callParams, "latest")
        )
    }

    // ---------- Multicall3 path ----------

    private suspend fun fetchViaMulticall3(
        rpcUrl: String,
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> {
        // `allowFailure = true` so we get back per-call status. Native failure is fatal;
        // per-token failures are logged and omitted from the result (see decode loop below).
        val calls = buildList {
            add(
                Multicall3.Call3(
                    target = Multicall3.CANONICAL_ADDRESS,
                    allowFailure = true,
                    callData = Multicall3.encodeGetEthBalance(walletAddress)
                )
            )
            tokens.forEach { token ->
                add(
                    Multicall3.Call3(
                        target = token.address,
                        allowFailure = true,
                        callData = Multicall3.encodeBalanceOf(walletAddress)
                    )
                )
            }
        }

        val aggregateCallData = Multicall3.encodeAggregate3(calls)
        val callParams = mapOf("to" to Multicall3.CANONICAL_ADDRESS, "data" to aggregateCallData)
        val hex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_call",
            params = listOf(callParams, "latest")
        )
        val results = Multicall3.decodeAggregate3Result(hex)

        // Expect native + one entry per token.
        val expectedCount = tokens.size + 1
        if (results.size != expectedCount) {
            throw RainError.InternalError(
                "Multicall3 returned ${results.size} results, expected $expectedCount on chain $chainId"
            )
        }

        // Index 0 is the native balance.
        val nativeResult = results[0]
        if (!nativeResult.success) {
            throw RainError.InternalError(
                "Multicall3 native balance call reverted on chain $chainId"
            )
        }

        val output = mutableListOf(nativeBalance(chainId, nativeResult.returnData))
        tokens.forEachIndexed { i, token ->
            val result = results[i + 1]
            if (!result.success) {
                Timber.w(
                    "Rain SDK: balanceOf reverted for token ${token.symbol ?: token.address} (${token.address}) on chain $chainId — omitting from result"
                )
                return@forEachIndexed
            }
            output += tokenBalance(chainId, token, result.returnData)
        }
        return output
    }

    // ---------- Parallel fallback path ----------

    /**
     * Fans out `eth_getBalance` (native) and per-token `eth_call balanceOf` requests
     * concurrently via `async`. Native failure is fatal; per-token failures are logged and
     * the token is omitted from the result.
     */
    private suspend fun fetchViaParallelCalls(
        rpcUrl: String,
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> = coroutineScope {
        // Native first, on its own — its failure is fatal and shouldn't be swallowed by a
        // group that's also tolerating per-token errors.
        val nativeHex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_getBalance",
            params = listOf(walletAddress, "latest")
        )
        val native = nativeBalance(chainId, nativeHex)

        // `balanceOf(walletAddress)` calldata is identical across every token — encode once.
        val balanceOfCallData = Multicall3.encodeBalanceOf(walletAddress)

        val tokenJobs: List<Pair<TokenInfo, Deferred<Balance?>>> = tokens.map { token ->
            token to async {
                try {
                    val hex = ethCall(rpcUrl, token.address, balanceOfCallData)
                    tokenBalance(chainId, token, hex)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "Rain SDK: balanceOf failed for token ${token.symbol ?: token.address} (${token.address}) — omitting from result"
                    )
                    null
                }
            }
        }

        val output = mutableListOf(native)
        tokenJobs.forEach { (_, deferred) ->
            deferred.await()?.let { output += it }
        }
        output
    }

    // ---------- Balance builders ----------

    /**
     * Builds a native-currency [Balance] from a raw hex wei value, pulling symbol / name /
     * decimals from the static native-currency table.
     */
    private fun nativeBalance(chainId: Int, hex: String): Balance {
        val native = TokenRegistry.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigInteger(hex),
            decimals = native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    /** Builds a contract-token [Balance] from a raw hex base-unit value and the token's metadata. */
    private fun tokenBalance(chainId: Int, token: TokenInfo, hex: String): Balance =
        Balance(
            token = Token.Contract(token.address),
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigInteger(hex),
            decimals = token.decimals,
            symbol = token.symbol,
            name = token.name
        )

    // ---------- Helpers ----------

    /**
     * Resolves and validates the RPC URL for [chainId]. Throws [RainError.InvalidConfig] —
     * with the correct chain ID — if the chain isn't configured or its URL is unparseable,
     * so parse failures don't surface from [JsonRpcClient] without chain context.
     */
    private fun resolveRpcUrl(chainId: Int): String {
        val rpcUrl = rpcUrlResolver(chainId)
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")
        if (rpcUrl.toHttpUrlOrNull() == null) {
            throw RainError.InvalidConfig("Invalid RPC URL for chainId=$chainId: $rpcUrl")
        }
        return rpcUrl
    }

    private fun validateAddress(address: String, label: String) {
        if (!address.isValidEthereumAddress) {
            throw RainError.InternalError("Invalid Ethereum $label: $address")
        }
    }
}
