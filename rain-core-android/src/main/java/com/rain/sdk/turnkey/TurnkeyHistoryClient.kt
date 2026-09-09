package com.rain.sdk.turnkey

import com.turnkey.stamper.Stamper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turnkey's indexed transaction-history queries (`list_eth_transaction_history` /
 * `list_sol_transaction_history`). These cover the wallet's full on-chain history, receives
 * included, unlike the activity log, which only records what was sent through Turnkey.
 *
 * The Turnkey Kotlin SDK does not expose these endpoints yet, so this client issues the
 * stamped REST calls directly, signing each request body with the session's stored P-256 key
 * exactly as the SDK's own client does.
 *
 * Note: Turnkey gates these queries behind a per-organization feature flag; a 403 with
 * "transaction history feature is not enabled" means the flag is off for the parent org.
 */
internal interface TurnkeyHistoryProtocol {
    suspend fun listEthTransactionHistory(
        organizationId: String,
        sessionPublicKey: String,
        address: String,
        caip2: String,
        limit: Int
    ): TurnkeyEthHistoryResponse

    suspend fun listSolTransactionHistory(
        organizationId: String,
        sessionPublicKey: String,
        address: String,
        caip2: String,
        limit: Int
    ): TurnkeySolHistoryResponse
}

/** Signs a request body, returning the stamp header as (name, value). Seam for unit tests. */
internal fun interface TurnkeyRequestStamper {
    suspend fun stamp(sessionPublicKey: String, payload: String): Pair<String, String>
}

/** Raised when Turnkey answers a history query with a non-2xx status. */
internal class TurnkeyHistoryError(
    val statusCode: Int,
    message: String
) : Exception("Turnkey history query failed with HTTP $statusCode: $message")

// ---------- wire models (hand-rolled; the Kotlin SDK has no types for these queries) ----------

@Serializable
internal data class TurnkeyEthHistoryResponse(
    val transactions: List<TurnkeyEthHistoryTransaction> = emptyList()
)

@Serializable
internal data class TurnkeyEthHistoryTransaction(
    val transactionHash: String,
    val block: TurnkeyHistoryBlock? = null,
    val status: String? = null,
    val from: String? = null,
    val to: String? = null,
    val transfers: List<TurnkeyHistoryTransfer> = emptyList(),
    val turnkey: TurnkeyHistoryOrigin? = null
)

@Serializable
internal data class TurnkeySolHistoryResponse(
    val transactions: List<TurnkeySolHistoryTransaction> = emptyList()
)

@Serializable
internal data class TurnkeySolHistoryTransaction(
    val signature: String,
    val block: TurnkeyHistoryBlock? = null,
    val status: String? = null,
    val feePayer: String? = null,
    val transfers: List<TurnkeyHistoryTransfer> = emptyList(),
    val turnkey: TurnkeyHistoryOrigin? = null
)

@Serializable
internal data class TurnkeyHistoryBlock(
    val number: String? = null,
    val hash: String? = null,
    /** RFC 3339 block timestamp. */
    val timestamp: String? = null
)

@Serializable
internal data class TurnkeyHistoryTransfer(
    /** `IN` or `OUT`, relative to the queried address. */
    val direction: String? = null,
    val asset: TurnkeyHistoryAsset? = null,
    /** Amount in the asset's atomic units, as a decimal string. */
    val amount: String? = null,
    val counterparty: String? = null,
    val display: TurnkeyHistoryDisplay? = null
)

@Serializable
internal data class TurnkeyHistoryAsset(
    val caip19: String? = null,
    val symbol: String? = null,
    val name: String? = null,
    val decimals: Int? = null
)

@Serializable
internal data class TurnkeyHistoryDisplay(
    val crypto: String? = null,
    val usd: String? = null
)

@Serializable
internal data class TurnkeyHistoryOrigin(
    val sponsored: Boolean? = null
)

@Serializable
private data class HistoryRequest(
    val organizationId: String,
    val address: String,
    val caip2: String,
    val paginationOptions: HistoryPagination
)

@Serializable
private data class HistoryPagination(
    // The API is proto3-JSON and rejects a numeric limit; it must be a string.
    val limit: String
)

/**
 * [apiBaseUrl] defaults to Turnkey's public API. A `TurnkeyContext` configured with a custom
 * `apiUrl` is not visible from here, so a host on a non-default Turnkey endpoint must pass the
 * matching base URL when this becomes constructible from the outside.
 */
internal class TurnkeyHistoryClient(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val stamper: TurnkeyRequestStamper = TurnkeyRequestStamper { publicKey, payload ->
        Stamper.fromPublicKey(publicKey).stamp(payload)
    }
) : TurnkeyHistoryProtocol {

    private companion object {
        const val DEFAULT_API_BASE_URL = "https://api.turnkey.com"
        const val ETH_HISTORY_PATH = "/public/v1/query/list_eth_transaction_history"
        const val SOL_HISTORY_PATH = "/public/v1/query/list_sol_transaction_history"
        const val ERROR_BODY_PREVIEW_CHARS = 500

        // Lenient + coercing: proto3-JSON quirks (a number arriving quoted, an explicit null for
        // a repeated field) must not take down the whole page.
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    override suspend fun listEthTransactionHistory(
        organizationId: String,
        sessionPublicKey: String,
        address: String,
        caip2: String,
        limit: Int
    ): TurnkeyEthHistoryResponse = json.decodeFromString(
        TurnkeyEthHistoryResponse.serializer(),
        post(ETH_HISTORY_PATH, sessionPublicKey, organizationId, address, caip2, limit)
    )

    override suspend fun listSolTransactionHistory(
        organizationId: String,
        sessionPublicKey: String,
        address: String,
        caip2: String,
        limit: Int
    ): TurnkeySolHistoryResponse = json.decodeFromString(
        TurnkeySolHistoryResponse.serializer(),
        post(SOL_HISTORY_PATH, sessionPublicKey, organizationId, address, caip2, limit)
    )

    private suspend fun post(
        path: String,
        sessionPublicKey: String,
        organizationId: String,
        address: String,
        caip2: String,
        limit: Int
    ): String {
        val body = json.encodeToString(
            HistoryRequest.serializer(),
            HistoryRequest(
                organizationId = organizationId,
                address = address,
                caip2 = caip2,
                paginationOptions = HistoryPagination(limit = limit.toString())
            )
        )
        return withContext(Dispatchers.IO) {
            // Stamping reads the key from secure storage and signs; keep it off the caller's
            // thread. The stamp covers the exact bytes sent, so the same string is posted.
            val (headerName, headerValue) = stamper.stamp(sessionPublicKey, body)
            val request = Request.Builder()
                .url(apiBaseUrl + path)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header(headerName, headerValue)
                .build()

            httpClient.newCall(request).await().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw TurnkeyHistoryError(response.code, text.take(ERROR_BODY_PREVIEW_CHARS))
                }
                text
            }
        }
    }

    /** Suspends without holding a thread and aborts the HTTP call if the coroutine is cancelled. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
        continuation.invokeOnCancellation { cancel() }
    }
}
