package com.rain.sdk.internal.network

import com.rain.sdk.internal.constants.RainConstants
import okhttp3.OkHttpClient
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object Web3jProvider {
  private val cache = ConcurrentHashMap<String, Web3j>()

  private val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(RainConstants.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(RainConstants.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(RainConstants.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

  /**
   * Return instance Web3j.
   */
  fun getOrCreate(rpcUrl: String): Web3j {
    return cache.computeIfAbsent(rpcUrl) { url ->
      Web3j.build(HttpService(url, sharedHttpClient, false))
    }
  }

  fun shutDownAll() {
    cache.values.forEach {
      try {
        it.shutdown()
      } catch (e: Exception) {
        // Ignore shutdown errors, only use when mock test
      }
    }
    cache.clear()
  }
}
