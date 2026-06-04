package com.rain.sdk.internal.solana

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.network.chainreader.JsonRpcClient
import java.math.BigInteger

/**
 * Thin Solana JSON-RPC helper layered on the shared [JsonRpcClient]. Solana RPC returns JSON
 * objects/numbers rather than the hex strings EVM uses, so it needs the raw `call()` surface.
 *
 * Covers the three calls the Solana wallet path needs: the native balance, a recent blockhash
 * for an outgoing transfer, and the signature of a just-submitted transfer.
 */
internal class SolanaRpcClient(
    private val jsonRpcClient: JsonRpcClient = JsonRpcClient()
) {
    /** Native SOL balance in lamports via `getBalance`. */
    suspend fun getBalanceLamports(rpcUrl: String, address: String): BigInteger {
        val response = jsonRpcClient.call(rpcUrl, "getBalance", listOf(address))
        val result = response.optJSONObject("result")
            ?: throw RainError.InternalError("Unexpected getBalance response for $address")
        return BigInteger.valueOf(result.getLong("value"))
    }

    /** Most recent blockhash via `getLatestBlockhash`, used as a transfer's `recentBlockhash`. */
    suspend fun getLatestBlockhash(rpcUrl: String): String {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getLatestBlockhash",
            listOf(mapOf("commitment" to "finalized"))
        )
        val value = response.optJSONObject("result")?.optJSONObject("value")
            ?: throw RainError.InternalError("Unexpected getLatestBlockhash response")
        return value.optString("blockhash", "").ifEmpty {
            throw RainError.InternalError("getLatestBlockhash returned no blockhash")
        }
    }

    /**
     * The most recent transaction signature involving [address], or null if none. Turnkey's
     * `sol_send_transaction` returns a status id rather than the signature, so the signature of a
     * just-submitted transfer is recovered from the chain.
     */
    suspend fun getLatestSignature(rpcUrl: String, address: String): String? {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getSignaturesForAddress",
            listOf(address, mapOf("limit" to 1))
        )
        val results = response.optJSONArray("result") ?: return null
        if (results.length() == 0) return null
        return results.getJSONObject(0).optString("signature", "").ifEmpty { null }
    }
}
