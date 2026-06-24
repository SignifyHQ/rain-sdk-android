package com.rain.sdk.sample

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Talks to the Rain dev API (`api-dev.rain.xyz`).
 *
 * Auth model (replaces the old Liquidity Financial bearer flow):
 *  1. The host supplies a program-level **Api-Key** and a Rain **userId** via [configure].
 *     In production this minting happens server-to-server; the sample mints in-app for
 *     convenience, exactly like the previous demo hard-coded its product/device ids.
 *  2. [session] exchanges the Api-Key for a short-lived **Client Session Token** (CST) at
 *     `POST /v1/issuing/users/{userId}/sessions`. The CST is cached and reused until it
 *     nears expiry, then re-minted.
 *  3. Data endpoints (contracts, signatures) are called with `Authorization: Bearer cst_…`.
 */
object NetworkClient {
  private const val BASE_URL = "https://api-dev.rain.xyz"

  /** Re-mint the CST when it is within this window of expiring. */
  private const val CST_REFRESH_BUFFER_SECONDS = 60L

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private val gson = Gson()

  // --- Host-supplied credentials -------------------------------------------------------

  @Volatile private var apiKey: String = ""
  @Volatile private var userId: String = ""

  // --- Cached client session token -----------------------------------------------------

  private val sessionMutex = Mutex()
  @Volatile private var cachedCst: String? = null
  @Volatile private var cstExpiresAt: Instant? = null

  /**
   * Registers the Rain program Api-Key and the Rain userId the demo acts on behalf of.
   * Clears any cached CST so the next call re-mints against the new credentials.
   */
  fun configure(apiKey: String, userId: String) {
    this.apiKey = apiKey.trim()
    this.userId = userId.trim()
    cachedCst = null
    cstExpiresAt = null
  }

  /** True once both an Api-Key and a userId have been supplied via [configure]. */
  val isConfigured: Boolean
    get() = apiKey.isNotBlank() && userId.isNotBlank()

  // --- Session (CST) -------------------------------------------------------------------

  private data class SessionResponse(val token: String, val expiresAt: String, val userId: String)

  /**
   * Returns a valid CST, minting a new one if none is cached or the cached one is near
   * expiry. Serialized through [sessionMutex] so concurrent callers share one mint.
   */
  private suspend fun session(): String = sessionMutex.withLock {
    require(isConfigured) { "NetworkClient is not configured — set Rain Api-Key and User ID first." }

    val cached = cachedCst
    val expiry = cstExpiresAt
    val stillValid = cached != null && expiry != null &&
      Instant.now().plusSeconds(CST_REFRESH_BUFFER_SECONDS).isBefore(expiry)
    if (cached != null && stillValid) return@withLock cached

    val response = createSession()
    cachedCst = response.token
    cstExpiresAt = runCatching { Instant.parse(response.expiresAt) }.getOrNull()
    response.token
  }

  private suspend fun createSession(): SessionResponse = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder()
      .url("$BASE_URL/v1/issuing/users/$userId/sessions")
      .addHeader("Api-Key", apiKey)
      .addHeader("accept", "application/json")
      .post(ByteArray(0).toRequestBody())
      .build()

    client.newCall(request).enqueue(object : okhttp3.Callback {
      override fun onFailure(call: okhttp3.Call, e: IOException) {
        continuation.resumeWith(Result.failure(e))
      }

      override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        response.use {
          val body = response.body?.string() ?: ""
          if (!response.isSuccessful) {
            continuation.resumeWith(
              Result.failure(IOException("Create session failed ${response.code}: $body"))
            )
            return
          }
          try {
            continuation.resume(gson.fromJson(body, SessionResponse::class.java))
          } catch (e: Exception) {
            continuation.resumeWith(Result.failure(e))
          }
        }
      }
    })
  }

  // --- Contracts -----------------------------------------------------------------------

  /**
   * GET `/v1/issuing/users/{userId}/contracts` (CST auth).
   *
   * Rain returns an array of collateral contracts; the demo uses the first. Note the Rain
   * contracts endpoint returns only token `address`/`balance`/`exchangeRate`/`advanceRate`
   * — no symbol/decimals metadata (unlike the old LF endpoint), so those fields are null.
   */
  suspend fun fetchCollateralContract(): NetworkResponse<CollateralContractData> {
    val cst = try {
      session()
    } catch (e: Exception) {
      return NetworkResponse("", Result.failure(e))
    }

    return suspendCancellableCoroutine { continuation ->
      val request = Request.Builder()
        .url("$BASE_URL/v1/issuing/users/$userId/contracts")
        .addHeader("Authorization", "Bearer $cst")
        .addHeader("accept", "application/json")
        .get()
        .build()

      val curl = request.toCurl()
      client.newCall(request).enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
          continuation.resume(NetworkResponse(curl, Result.failure(e)))
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
          response.use {
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
              continuation.resume(
                NetworkResponse(curl, Result.failure(IOException("Error ${response.code}: $body")))
              )
              return
            }
            try {
              val contracts = gson.fromJson(body, Array<RainContractDto>::class.java)
              val first = contracts?.firstOrNull()
                ?: throw IOException("No collateral contracts returned for user")
              continuation.resume(NetworkResponse(curl, Result.success(first.toCollateralContractData())))
            } catch (e: Exception) {
              continuation.resume(NetworkResponse(curl, Result.failure(e)))
            }
          }
        }
      })
    }
  }

  // --- Withdrawal signature ------------------------------------------------------------

  /**
   * GET `/v1/issuing/users/{userId}/signatures/withdrawals` (CST auth).
   *
   * Differs from the old LF withdrawal/signature POST: it is a GET with query params, now
   * requires [adminAddress] (an admin of the collateral contract — see
   * [CollateralContractData.adminAddresses]), and returns a `status` envelope. A non-"ready"
   * status (or a missing signature) is surfaced as a failure.
   */
  suspend fun fetchAdminSignature(
    chainId: Long,
    token: String,
    amount: java.math.BigInteger,
    adminAddress: String,
    recipientAddress: String,
    isAmountNative: Boolean = true
  ): NetworkResponse<Pair<SignatureDetails, String>> {
    val cst = try {
      session()
    } catch (e: Exception) {
      return NetworkResponse("", Result.failure(e))
    }

    val url = "$BASE_URL/v1/issuing/users/$userId/signatures/withdrawals".toHttpUrl()
      .newBuilder()
      .addQueryParameter("chainId", chainId.toString())
      .addQueryParameter("token", token)
      .addQueryParameter("amount", amount.toString())
      .addQueryParameter("adminAddress", adminAddress)
      .addQueryParameter("recipientAddress", recipientAddress)
      .addQueryParameter("isAmountNative", isAmountNative.toString())
      .build()

    return suspendCancellableCoroutine { continuation ->
      val request = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $cst")
        .addHeader("accept", "application/json")
        .get()
        .build()

      val curl = request.toCurl()
      client.newCall(request).enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
          continuation.resume(NetworkResponse(curl, Result.failure(e)))
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
          response.use {
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
              continuation.resume(
                NetworkResponse(curl, Result.failure(IOException("API Error ${response.code}: $body")))
              )
              return
            }
            try {
              val parsed = gson.fromJson(body, RainSignatureResponse::class.java)
              val signature = parsed.signature
              if (!parsed.status.equals("ready", ignoreCase = true) || signature == null) {
                val retry = parsed.retryAfter?.let { " (retry after ${it}s)" } ?: ""
                continuation.resume(
                  NetworkResponse(curl, Result.failure(IOException("Signature not ready: status=${parsed.status}$retry")))
                )
                return
              }
              continuation.resume(
                NetworkResponse(curl, Result.success(signature to (parsed.expiresAt ?: "")))
              )
            } catch (e: Exception) {
              continuation.resume(NetworkResponse(curl, Result.failure(e)))
            }
          }
        }
      })
    }
  }

  // --- Wire DTOs (Rain dev API) --------------------------------------------------------

  private data class RainContractDto(
    val id: String? = null,
    val chainId: Long = 0,
    val controllerAddress: String = "",
    val proxyAddress: String = "",
    val depositAddress: String? = null,
    val adminAddresses: List<String> = emptyList(),
    val contractVersion: Int? = null,
    val tokens: List<RainTokenDto> = emptyList()
  ) {
    fun toCollateralContractData() = CollateralContractData(
      address = proxyAddress,
      controllerAddress = controllerAddress,
      chainId = chainId,
      adminAddresses = adminAddresses,
      tokens = tokens.map { it.toCollateralTokenData() }
    )
  }

  private data class RainTokenDto(
    val address: String = "",
    // Rain returns balance as a string (e.g. "0.0"); the demo model keeps it as a Double.
    val balance: String? = null,
    val exchangeRate: Double = 0.0,
    val advanceRate: Double = 0.0
  ) {
    fun toCollateralTokenData() = CollateralTokenData(
      address = address,
      // Rain's contracts endpoint omits token symbol/decimals/logo; left null for callers
      // to resolve (e.g. via the SDK token registry / chain reader) if they need them.
      name = null,
      symbol = null,
      logo = null,
      decimals = null,
      balance = balance?.toDoubleOrNull() ?: 0.0,
      exchangeRate = exchangeRate,
      advanceRate = advanceRate
    )
  }

  private data class RainSignatureResponse(
    val status: String = "",
    val retryAfter: Int? = null,
    val signature: SignatureDetails? = null,
    val expiresAt: String? = null
  )

  // --- Public demo models (unchanged shape; populated from the Rain API) ---------------

  data class CollateralContractData(
    val address: String,
    val controllerAddress: String,
    val chainId: Long,
    val adminAddresses: List<String> = emptyList(),
    val tokens: List<CollateralTokenData> = emptyList()
  )

  data class CollateralTokenData(
    val name: String? = null,
    val address: String,
    val symbol: String? = null,
    val logo: String? = null,
    val decimals: Int? = null,
    val balance: Double = 0.0,
    val exchangeRate: Double = 0.0,
    val advanceRate: Double = 0.0
  )

  data class NetworkResponse<T>(
    val curl: String,
    val result: Result<T>
  )

  data class SignatureDetails(
    val data: String,
    val salt: String
  )

  // --- Helpers -------------------------------------------------------------------------

  private fun Request.toCurl(): String {
    val builder = StringBuilder("curl -X ${method} \"${url}\"")
    headers.forEach { pair ->
      builder.append(" \\\n -H \"${pair.first}: ${pair.second}\"")
    }
    body?.let {
      try {
        val buffer = okio.Buffer()
        it.writeTo(buffer)
        val bodyString = buffer.readUtf8()
        builder.append(" \\\n -d '$bodyString'")
      } catch (e: Exception) {
        builder.append(" \\\n -d [Error reading body]")
      }
    }
    return builder.toString()
  }
}
