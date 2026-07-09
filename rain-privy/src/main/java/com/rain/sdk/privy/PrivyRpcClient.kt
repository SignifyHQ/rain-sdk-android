package com.rain.sdk.privy

import com.rain.sdk.internal.error.RainError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal JSON-RPC 2.0 client for Privy's read path (balances, gas, fee estimates).
 *
 * Core's `JsonRpcClient` is module-internal, so this out-of-module adapter carries its own. Privy's
 * EIP-1193 provider is reserved for custody (sign/send); everything read-only goes through here
 * against the RPC endpoints Rain was configured with. Wire format / error mapping mirror core.
 */
internal class PrivyRpcClient(
    httpClient: OkHttpClient? = null,
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10L
        const val JSON_MEDIA_TYPE = "application/json"
    }

    /** Sends a single JSON-RPC 2.0 request and returns the `result` field as a hex string. */
    suspend fun callForHexResult(
        rpcUrl: String,
        method: String,
        params: List<Any>
    ): String {
        val parsedUrl = rpcUrl.toHttpUrlOrNull()
            ?: throw RainError.InvalidRpcUrl(rpcUrl)

        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", paramsToJsonArray(params))
        }

        val request = Request.Builder()
            .url(parsedUrl)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaTypeOrNull()))
            .addHeader("Content-Type", JSON_MEDIA_TYPE)
            .build()

        val raw = try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body?.string() ?: "{}" }
            }
        } catch (e: IOException) {
            Timber.e(e, "Rain SDK: Privy JSON-RPC transport failure for $method")
            throw RainError.NetworkError(message = "RPC request failed for $method", cause = e)
        }

        val response = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Timber.e(e, "Rain SDK: Privy JSON-RPC returned non-JSON body for $method")
            throw RainError.NetworkError(message = "RPC request failed for $method", cause = e)
        }

        if (response.has("error") && !response.isNull("error")) {
            val err = response.getJSONObject("error")
            val code = err.optInt("code", -1)
            val message = err.optString("message", "Unknown RPC error")
            throw RainError.InternalError("RPC error [$code]: $message")
        }

        val result = response.opt("result")
        if (result !is String) {
            throw RainError.InternalError("Unexpected RPC result for method $method")
        }
        return result
    }

    private fun paramsToJsonArray(params: List<Any>): JSONArray {
        val array = JSONArray()
        params.forEach { value ->
            when (value) {
                is Map<*, *> -> {
                    val obj = JSONObject()
                    value.forEach { (k, v) -> obj.put(k.toString(), v) }
                    array.put(obj)
                }
                else -> array.put(value)
            }
        }
        return array
    }
}
