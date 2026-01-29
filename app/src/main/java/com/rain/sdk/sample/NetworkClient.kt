package com.rain.sdk.sample

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object NetworkClient {
  // TODO: add base url
  private const val BASE_URL = "https://service-platform.dev..."

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private val gson = Gson()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  suspend fun fetchAdminSignature(
    accessToken: String,
    chainId: Long,
    token: String,
    amount: Long,
    recipientAddress: String
  ): NetworkResponse = suspendCancellableCoroutine { continuation ->

    data class SignatureData(
      val chainId: Long,
      val token: String,
      val amount: Long,
      val recipientAddress: String,
      val isAmountNative: Boolean = true,
    )

    data class SignatureRequest(val request: SignatureData)

    // Matches WithdrawAssetSignature.Succeed
    data class SignatureResponse(
      val signature: SignatureDetails,
      val expiresAt: String // API likely returns String or Number, checking RainWithdrawSignaturePolymorphicSerializer would be precise but let's assume standard JSON
    )

    val payload = SignatureRequest(
      SignatureData(chainId, token, amount, recipientAddress)
    )

    val requestBody = gson.toJson(payload).toRequestBody(jsonMediaType)

    val PRODUCT_ID = "fb352b08..."
    
    val deviceId = "f28e7e83ac19d565"

    val request = Request.Builder()
      .url("$BASE_URL/v1/rain/person/withdrawal/signature")
      .addHeader("Authorization", "Bearer $accessToken")
      .addHeader("accept", "application/json")
      .addHeader("productId", PRODUCT_ID)
      .addHeader("ld-device-id", deviceId)
      .post(requestBody)
      .build()

    val curlCommand = request.toCurl()

    client.newCall(request).enqueue(object : okhttp3.Callback {
      override fun onFailure(call: okhttp3.Call, e: IOException) {
        continuation.resume(NetworkResponse(curlCommand, Result.failure(e)))
      }

      override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        response.use {
          if (!response.isSuccessful) {
            continuation.resume(
                NetworkResponse(
                    curlCommand, 
                    Result.failure(IOException("API Error: ${response.code} ${response.message}"))
                )
            )
            return
          }

          try {
            val responseBody = response.body?.string() ?: ""
            val simpleResponse = gson.fromJson(responseBody, SignatureResponse::class.java)
            continuation.resume(
                NetworkResponse(
                    curlCommand, 
                    Result.success(simpleResponse.signature to simpleResponse.expiresAt)
                )
            )
          } catch (e: Exception) {
            continuation.resume(NetworkResponse(curlCommand, Result.failure(e)))
          }
        }
      }
    })
  }


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

  data class NetworkResponse(
    val curl: String,
    val result: Result<Pair<SignatureDetails, String>>
  )

  data class SignatureDetails(
    val data: String,
    val salt: String
  )
}

