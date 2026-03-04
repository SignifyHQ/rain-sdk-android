package com.rain.sdk.utils

import io.portalhq.android.provider.data.PortalProviderResult
import io.portalhq.android.provider.data.PortalProviderRpcResponse
import io.portalhq.android.utils.ethRequests.EthRequestUtils
import java.math.BigInteger

/**
 * Utility for converting between different Ethereum units and formats.
 */
object EthereumConverter {

  /**
   * Extracts a hex string from a Portal provider result.
   */
  fun convertPortalResultToHexString(portalResult: Any): String {
    val providerResult = portalResult as? PortalProviderResult
    val hex = when (val result = providerResult?.result) {
      is String -> result
      is PortalProviderRpcResponse -> result.result as? String
      else -> null
    }

    return hex
      ?.takeIf { it.startsWith("0x") && it.length > 2 }
      ?: "0x0"
  }

  /**
   * Extracts a transaction hash from a Portal provider result.
   *
   * @param portalResult The result object from Portal SDK
   * @return The transaction hash as String
   * @throws com.rain.sdk.internal.error.RainError.ProviderError if the result is not a valid transaction hash string
   */
  fun convertPortalResultToTransactionHash(portalResult: Any): String {
    val result = (portalResult as? PortalProviderResult)?.result
    if (result !is String) {
      throw com.rain.sdk.internal.error.RainError.ProviderError(
        IllegalStateException("Portal returned invalid transaction result: $result")
      )
    }
    return result
  }

  /**
   * Converts a Wei hex string to ETH (Double).
   */
  fun convertWeiHexToEth(ethBalanceHexValue: String): Double {
    return try {
      EthRequestUtils().hexStringToNumber(ethBalanceHexValue)
    } catch (ex: Exception) {
      // Manual conversion fallback
      val cleanedHex = ethBalanceHexValue.removePrefix("0x")
      val decimalValue = BigInteger(cleanedHex, 16).toBigDecimal()
      return decimalValue.movePointLeft(18).toDouble()
    }
  }

  /**
   * Converts a Wei hex string to its unit-less Double value.
   */
  fun convertWeiHexToDouble(ethBalanceHexValue: String): Double {
    return try {
      EthRequestUtils().hexStringToNumber(ethBalanceHexValue)
    } catch (ex: Exception) {
      val cleanedHex = ethBalanceHexValue.removePrefix("0x")
      return BigInteger(cleanedHex, 16).toDouble()
    }
  }

  /**
   * Converts a Wei BigInteger to ETH (Double).
   */
  fun convertWeiToEth(wei: BigInteger): Double {
    return wei.toBigDecimal().movePointLeft(18).toDouble()
  }

  /**
   * Converts a ETH Double value to a Wei hex string.
   */
  fun convertEthToWeiHex(ethBalance: Double): String {
    val wei = (ethBalance.toBigDecimal().multiply(1e18.toBigDecimal())).toBigInteger()
    return "0x${wei.toString(16)}"
  }

  /**
   * Converts a hex string to a Double with the specified number of decimals.
   *
   * @param hex The hex string (e.g. "0x...")
   * @param decimals The number of decimals
   * @return The Double value
   */
  fun convertHexToDouble(hex: String, decimals: Int): Double {
    val cleanedHex = hex.removePrefix("0x")
    val decimalValue = BigInteger(cleanedHex, 16).toBigDecimal()
    return decimalValue.movePointLeft(decimals).toDouble()
  }
}
