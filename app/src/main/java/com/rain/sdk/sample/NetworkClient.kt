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
  private const val BASE_URL = "https://service-platform.dev.liquidity-financial.com"
  private const val PRODUCT_ID = "fb352b08-c759-4a6c-8a63-d9d190265447"
  private const val DEVICE_ID = "f28e7e83ac19d565"

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
  ): NetworkResponse<Pair<SignatureDetails, String>> = suspendCancellableCoroutine { continuation ->

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
      val expiresAt: String 
    )

    val payload = SignatureRequest(
      SignatureData(chainId, token, amount, recipientAddress)
    )

    val requestBody = gson.toJson(payload).toRequestBody(jsonMediaType)



    val request = Request.Builder()
      .url("$BASE_URL/v1/rain/person/withdrawal/signature")
      .addHeader("Authorization", "Bearer $accessToken")
      .addHeader("Content-Type", "application/json")
      .addHeader("accept", "application/json")
      .addHeader("productId", PRODUCT_ID)
      .addHeader("ld-device-id", DEVICE_ID)
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
            val errorBody = response.body?.string() ?: ""
            continuation.resume(
                NetworkResponse(
                    curlCommand, 
                    Result.failure(IOException("API Error ${response.code}: $errorBody"))
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


  suspend fun fetchCollateralContract(
    accessToken: String
  ): NetworkResponse<CollateralContractData> = suspendCancellableCoroutine { continuation ->


    val request = Request.Builder()
      .url("$BASE_URL/v1/rain/person/credit-contracts")
      .addHeader("Authorization", "Bearer $accessToken")
      .addHeader("productId", PRODUCT_ID)
      .addHeader("ld-device-id", DEVICE_ID)
      .get()
      .build()

    val curl = request.toCurl()
    client.newCall(request).enqueue(object : okhttp3.Callback {
      override fun onFailure(call: okhttp3.Call, e: IOException) {
        continuation.resume(NetworkResponse(curl, Result.failure(e)))
      }

      override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        response.use {
          if (response.isSuccessful) {
            try {
              val body = response.body?.string() ?: ""
              val data = gson.fromJson(body, CollateralContractData::class.java)
              continuation.resume(NetworkResponse(curl, Result.success(data)))
            } catch (e: Exception) {
              continuation.resume(NetworkResponse(curl, Result.failure(e)))
            }
          } else {
            continuation.resume(NetworkResponse(curl, Result.failure(IOException("Error ${response.code}"))))
          }
        }
      }
    })
  }

  data class CollateralContractData(
    val address: String,
    val controllerAddress: String,
    val chainId: Long,
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

  suspend fun fetchBackupShare(accessToken: String): NetworkResponse<String> = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder()
      .url("$BASE_URL/v1/portal/backup?backupMethod=PASSWORD")
      .addHeader("Authorization", "Bearer $accessToken")
      .get()
      .build()

    val curl = request.toCurl()
    client.newCall(request).enqueue(object : okhttp3.Callback {
      override fun onFailure(call: okhttp3.Call, e: IOException) {
        continuation.resume(NetworkResponse(curl, Result.failure(e)))
      }
      override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        response.use {
          if (response.isSuccessful) {
            val body = response.body?.string() ?: ""
            val data = gson.fromJson(body, Map::class.java)
            val cipherText = data["cipherText"] as? String ?: ""
            continuation.resume(NetworkResponse(curl, Result.success(cipherText)))
          } else continuation.resume(NetworkResponse(curl, Result.failure(IOException("Error ${response.code}"))))
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

  data class NetworkResponse<T>(
    val curl: String,
    val result: Result<T>
  )

  data class SignatureDetails(
    val data: String,
    val salt: String
  )
}

