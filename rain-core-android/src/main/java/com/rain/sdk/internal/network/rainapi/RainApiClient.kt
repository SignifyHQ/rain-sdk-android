package com.rain.sdk.internal.network.rainapi

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainCollateralContract
import com.rain.sdk.models.RainCollateralToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.math.BigInteger
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Rain issuing REST API.
 *
 * Auth model:
 *  1. `POST /v1/issuing/users/{userId}/sessions` with an `Api-Key` header exchanges the
 *     program key for a short-lived client session token (CST).
 *  2. Data endpoints (contracts, withdrawal signatures) are called with
 *     `Authorization: Bearer cst_…`.
 *
 * Stateless: base URL and auth material are passed per call; caching lives in
 * [RainSessionStore] and orchestration in [RainApiService]. Follows the [com.rain.sdk.internal.network.chainreader.JsonRpcClient]
 * conventions — blocking OkHttp `execute()` on [Dispatchers.IO], `org.json` parsing, typed
 * [RainError]s.
 */
internal class RainApiClient(
    httpClient: OkHttpClient? = null,
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    /**
     * Mints a client session token.
     *
     * The request body must be empty AND carry no `Content-Type` header — Rain rejects an
     * empty body that declares a content type with a 400. An OkHttp request body built from
     * an empty byte array with a null media type produces exactly that shape.
     */
    suspend fun createSession(baseUrl: String, credentials: RainApiCredentials): RainSession {
        val request = Request.Builder()
            .url(url(baseUrl, "v1/issuing/users/${credentials.userId}/sessions"))
            .addHeader("Api-Key", credentials.apiKey)
            .addHeader("accept", "application/json")
            .post(ByteArray(0).toRequestBody())
            .build()

        val body = executeChecked(request, "create session")
        val json = parseObject(body, "create session")
        val token = json.stringOrNull("token")
        if (token.isNullOrBlank()) {
            throw RainError.NetworkError("Create session returned no token")
        }
        val expiresAt = json.stringOrNull("expiresAt")?.let(::parseInstantLenient)
        return RainSession(token = token, expiresAt = expiresAt)
    }

    /**
     * Parses an ISO-8601 timestamp accepting both `Z` and offset forms (`+00:00`); a strict
     * [Instant.parse] alone would silently degrade offset forms to the fallback session TTL.
     */
    private fun parseInstantLenient(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()

    /** `GET /v1/issuing/users/{userId}/contracts` (CST auth). Tokens are not yet enriched. */
    suspend fun getContracts(baseUrl: String, cst: String, userId: String): List<RainCollateralContract> {
        val request = Request.Builder()
            .url(url(baseUrl, "v1/issuing/users/$userId/contracts"))
            .addHeader("Authorization", "Bearer $cst")
            .addHeader("accept", "application/json")
            .get()
            .build()

        val body = executeChecked(request, "fetch contracts")
        return try {
            val array = JSONArray(body)
            (0 until array.length()).map { parseContract(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            Timber.e(e, "Rain SDK: contracts endpoint returned a malformed body")
            throw RainError.NetworkError("Rain API request failed for fetch contracts", e)
        }
    }

    /**
     * `GET /v1/issuing/users/{userId}/signatures/withdrawals` (CST auth).
     *
     * @param amountBaseUnits Withdrawal amount in the token's base units.
     * @throws RainError.SignatureNotReady when `status != "ready"` or the signature is missing.
     */
    suspend fun getWithdrawalSignature(
        baseUrl: String,
        cst: String,
        userId: String,
        chainId: Int,
        tokenAddress: String,
        amountBaseUnits: BigInteger,
        adminAddress: String,
        recipientAddress: String,
        isAmountNative: Boolean,
    ): RainAdminSignature {
        val url = url(baseUrl, "v1/issuing/users/$userId/signatures/withdrawals")
            .newBuilder()
            .addQueryParameter("chainId", chainId.toString())
            .addQueryParameter("token", tokenAddress)
            .addQueryParameter("amount", amountBaseUnits.toString())
            .addQueryParameter("adminAddress", adminAddress)
            .addQueryParameter("recipientAddress", recipientAddress)
            .addQueryParameter("isAmountNative", isAmountNative.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $cst")
            .addHeader("accept", "application/json")
            .get()
            .build()

        val body = executeChecked(request, "fetch withdrawal signature")
        val json = parseObject(body, "fetch withdrawal signature")

        val status = json.stringOrNull("status").orEmpty()
        val signature = json.optJSONObject("signature")
        val signatureData = signature?.stringOrNull("data")
        // "ready" without usable signature bytes is still not-ready — returning an empty
        // signature would only surface later as a confusing on-chain revert.
        if (!status.equals("ready", ignoreCase = true) || signatureData.isNullOrBlank()) {
            throw RainError.SignatureNotReady(
                status = status.ifBlank { "unknown" },
                retryAfter = if (json.has("retryAfter") && !json.isNull("retryAfter")) {
                    json.optInt("retryAfter")
                } else null,
            )
        }
        return RainAdminSignature(
            salt = signature.stringOrNull("salt").orEmpty(),
            signature = signatureData,
            expiresAt = json.stringOrNull("expiresAt").orEmpty(),
        )
    }

    // ---------- Shared request/response handling ----------

    private fun url(baseUrl: String, path: String): HttpUrl {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw RainError.InvalidConfig("Invalid Rain API base URL: $baseUrl")
        return base.newBuilder().addPathSegments(path).build()
    }

    /**
     * Executes [request], returning the body on 2xx. 401/403 map to [RainError.Unauthorized],
     * other non-2xx to [RainError.ApiError], transport failures to [RainError.NetworkError].
     */
    private suspend fun executeChecked(request: Request, operation: String): String {
        val (code, body) = try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    response.code to (response.body?.string() ?: "")
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Rain SDK: Rain API transport failure for $operation")
            throw RainError.NetworkError("Rain API request failed for $operation", e)
        }

        if (code == 401 || code == 403) {
            Timber.w("Rain SDK: Rain API $operation rejected with $code")
            throw RainError.Unauthorized("Rain API $operation rejected ($code): ${body.snippet()}")
        }
        if (code !in 200..299) {
            Timber.w("Rain SDK: Rain API $operation failed with $code")
            throw RainError.ApiError(statusCode = code, details = body.snippet())
        }
        return body
    }

    private fun parseObject(body: String, operation: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        Timber.e(e, "Rain SDK: Rain API returned non-JSON body for $operation")
        throw RainError.NetworkError("Rain API request failed for $operation", e)
    }

    private fun parseContract(json: JSONObject): RainCollateralContract {
        val tokensArray = json.optJSONArray("tokens") ?: JSONArray()
        val tokens = (0 until tokensArray.length()).map { index ->
            val token = tokensArray.getJSONObject(index)
            RainCollateralToken(
                address = token.stringOrNull("address").orEmpty(),
                balance = token.stringOrNull("balance").orEmpty(),
                exchangeRate = token.optDouble("exchangeRate", 0.0),
                advanceRate = token.optDouble("advanceRate", 0.0),
            )
        }
        val adminsArray = json.optJSONArray("adminAddresses") ?: JSONArray()
        return RainCollateralContract(
            id = json.stringOrNull("id")?.takeIf { it.isNotBlank() },
            chainId = json.optInt("chainId", 0),
            proxyAddress = json.stringOrNull("proxyAddress").orEmpty(),
            controllerAddress = json.stringOrNull("controllerAddress").orEmpty(),
            depositAddress = json.stringOrNull("depositAddress")?.takeIf { it.isNotBlank() },
            adminAddresses = (0 until adminsArray.length()).mapNotNull { index ->
                if (adminsArray.isNull(index)) null else adminsArray.optString(index)
            },
            contractVersion = if (json.has("contractVersion") && !json.isNull("contractVersion")) {
                json.optInt("contractVersion")
            } else null,
            tokens = tokens,
        )
    }

    /**
     * Null-safe string read: absent key AND explicit JSON null both yield Kotlin null.
     * (Plain `optString` stringifies `JSONObject.NULL` into the literal "null".)
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun String.snippet(): String = take(MAX_ERROR_BODY_CHARS).trim()

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val MAX_ERROR_BODY_CHARS = 300
    }
}
