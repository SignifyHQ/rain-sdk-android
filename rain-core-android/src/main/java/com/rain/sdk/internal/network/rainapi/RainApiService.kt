package com.rain.sdk.internal.network.rainapi

import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainCollateralContract
import com.rain.sdk.models.RainCollateralToken
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.math.BigInteger

/**
 * Orchestrates the Rain issuing API: composes credentials ([RainApiConfigStore]) with the HTTP
 * client ([RainApiClient]) and enriches contract tokens through the SDK token store.
 *
 * Every call authenticates directly with the Api-Key header; there is no client session token
 * layer, because client session tokens are not enabled for Rain's tenants. A
 * [RainError.Unauthorized] is therefore terminal: retrying with the same key cannot succeed, so
 * nothing here retries.
 */
internal class RainApiService(
    private val configStore: RainApiConfigStore,
    private val tokenStore: TokenMetadataStore,
    private val chainReader: ChainReader,
    private val client: RainApiClient = RainApiClient(),
) {
    suspend fun fetchCollateralContracts(): List<RainCollateralContract> {
        val contracts = client.getContracts(configStore.baseUrl, configStore.credentials())
        return contracts.map { enrichTokens(it) }
    }

    suspend fun fetchAdminSignature(
        chainId: Int,
        tokenAddress: String,
        amountBaseUnits: BigInteger,
        adminAddress: String,
        recipientAddress: String,
        isAmountNative: Boolean,
    ): RainAdminSignature = client.getWithdrawalSignature(
        baseUrl = configStore.baseUrl,
        credentials = configStore.credentials(),
        chainId = chainId,
        tokenAddress = tokenAddress,
        amountBaseUnits = amountBaseUnits,
        adminAddress = adminAddress,
        recipientAddress = recipientAddress,
        isAmountNative = isAmountNative,
    )

    // ---------- Internals ----------

    /**
     * Fills token `name`/`symbol`/`decimals`: known tokens (registry + host-registered) first,
     * else direct on-chain reads. Best-effort and concurrent per token: a failed read leaves
     * that field null — never a fabricated default, since wrong decimals would corrupt the
     * caller's base-unit math. (This is deliberately NOT `tokenStore.tokenInfo`, whose
     * enrichment falls back to 18 decimals on failure.) On Solana chains only the registry
     * is consulted — the on-chain read path is EVM-only, and an SPL mint carries no on-chain
     * symbol anyway, so host-registered metadata is the sole naming source there.
     */
    private suspend fun enrichTokens(contract: RainCollateralContract): RainCollateralContract {
        if (contract.tokens.isEmpty()) return contract
        val known = tokenStore.registeredTokens(contract.chainId)
        if (SolanaChains.isSolanaChain(contract.chainId)) {
            return contract.copy(
                tokens = contract.tokens.map { token ->
                    known.firstOrNull { it.address.equals(token.address, ignoreCase = true) }
                        ?.let { token.copy(name = it.name, symbol = it.symbol, decimals = it.decimals) }
                        ?: token
                }
            )
        }
        val enriched = coroutineScope {
            contract.tokens.map { token ->
                async { enrichToken(contract.chainId, token, known) }
            }.awaitAll()
        }
        return contract.copy(tokens = enriched)
    }

    private suspend fun enrichToken(
        chainId: Int,
        token: RainCollateralToken,
        known: List<TokenInfo>,
    ): RainCollateralToken {
        known.firstOrNull { it.address.equals(token.address, ignoreCase = true) }?.let {
            return token.copy(name = it.name, symbol = it.symbol, decimals = it.decimals)
        }
        return coroutineScope {
            val decimals = async { readOrNull { chainReader.getDecimals(chainId, token.address) } }
            val symbol = async { readOrNull { chainReader.getSymbol(chainId, token.address) } }
            val name = async { readOrNull { chainReader.getName(chainId, token.address) } }
            token.copy(name = name.await(), symbol = symbol.await(), decimals = decimals.await())
        }
    }

    private suspend fun <T> readOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
