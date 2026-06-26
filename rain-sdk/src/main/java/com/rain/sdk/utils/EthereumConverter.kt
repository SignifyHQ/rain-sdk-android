package com.rain.sdk.utils

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.toHexString
import com.rain.sdk.internal.provider.toTransactionHash
import io.portalhq.android.provider.data.PortalProviderResult
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Utility for converting between different Ethereum units and formats.
 *
 * The unit-conversion functions are pure (no Portal/Turnkey/web3j dependencies) and safe to
 * call from any layer. The only exceptions are the two `@Deprecated` `convertPortalResult*`
 * shims, kept solely for backward compatibility; they delegate to internal Portal extensions.
 * Prefer the non-Portal entry points (e.g. [normalizedHexString]) in new code.
 */
object EthereumConverter {

    /**
     * Normalizes an optional hex string by stripping invalid values down to `"0x0"`.
     * Used by Portal/Turnkey adapters before parsing.
     */
    fun normalizedHexString(hex: String?): String =
        hex?.takeIf { it.startsWith("0x") && it.length > 2 } ?: "0x0"

    /**
     * Converts a Wei hex string to ETH (Double). Falls back to manual BigInteger parsing
     * if the input has odd formatting.
     */
    fun convertWeiHexToEth(ethBalanceHexValue: String): Double {
        val cleanedHex = ethBalanceHexValue.removePrefix("0x").ifEmpty { "0" }
        return BigInteger(cleanedHex, 16).toBigDecimal().movePointLeft(18).toDouble()
    }

    /**
     * Converts a Wei hex string to its unit-less Double value.
     */
    fun convertWeiHexToDouble(ethBalanceHexValue: String): Double {
        val cleanedHex = ethBalanceHexValue.removePrefix("0x").ifEmpty { "0" }
        return BigInteger(cleanedHex, 16).toDouble()
    }

    /** Converts a Wei BigInteger to ETH (Double). */
    fun convertWeiToEth(wei: BigInteger): Double =
        wei.toBigDecimal().movePointLeft(18).toDouble()

    /** Converts an ETH BigDecimal value to a Wei hex string (exact base-10 scaling). */
    fun convertEthToWeiHex(ethBalance: BigDecimal): String {
        val wei = ethBalance.multiply(BigDecimal.TEN.pow(18)).toBigInteger()
        return "0x${wei.toString(16)}"
    }

    /**
     * Converts a hex string to a Double with the specified number of decimals.
     *
     * @param hex The hex string (e.g. "0x...")
     * @param decimals The number of decimal places
     */
    fun convertHexToDouble(hex: String, decimals: Int): Double {
        val cleanedHex = hex.removePrefix("0x").ifEmpty { "0" }
        return BigInteger(cleanedHex, 16).toBigDecimal().movePointLeft(decimals).toDouble()
    }

    /** Converts a hex string to an exact [BigDecimal] with the specified number of decimals. */
    fun convertHexToDecimal(hex: String, decimals: Int): BigDecimal {
        val cleanedHex = hex.removePrefix("0x").ifEmpty { "0" }
        return BigInteger(cleanedHex, 16).toBigDecimal().movePointLeft(decimals)
    }

    /**
     * Converts a hex-encoded uint256 string to an exact [BigInteger] (no precision loss).
     *
     * Used for balances read directly from chain (`eth_getBalance`, `eth_call balanceOf`),
     * where the raw base-unit value must be preserved. Returns [BigInteger.ZERO] on a
     * malformed payload rather than throwing.
     */
    fun parseHexToBigInteger(hex: String): BigInteger {
        val cleaned = hex.removePrefix("0x").removePrefix("0X").ifEmpty { "0" }
        return try {
            BigInteger(cleaned, 16)
        } catch (e: NumberFormatException) {
            BigInteger.ZERO
        }
    }

    /**
     * Converts a hex-encoded uint256 string to an [Int] (e.g. for ERC-20 `decimals()`
     * responses). Returns 0 on a malformed payload.
     */
    fun parseHexToInt(hex: String): Int {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return 0
        return try {
            val value = BigInteger(cleaned, 16)
            // ERC-20 decimals are small non-negative numbers. Reject negative or
            // out-of-Int-range values: a malformed/hostile `decimals()` could otherwise
            // narrow to a negative Int and silently flip `Balance.decimalAmount` from a
            // divide into a multiply.
            if (value.signum() < 0 || value.bitLength() > 31) 0 else value.toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    /**
     * Decodes an ABI-encoded string returned by `eth_call` (e.g. from ERC-20 `symbol()`).
     *
     * ABI string layout (each slot = 32 bytes = 64 hex chars):
     *   slot 0 — offset to data (usually 0x20)
     *   slot 1 — byte length of string
     *   slot 2+ — UTF-8 string bytes, right-padded to a 32-byte boundary
     *
     * Returns `null` if the payload is too short or malformed.
     */
    fun parseHexToString(hex: String): String? {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        if (cleaned.length < 128) return null

        val lengthHex = cleaned.substring(64, 128)
        val byteLength = try {
            BigInteger(lengthHex, 16).toInt()
        } catch (e: NumberFormatException) {
            return null
        }
        if (byteLength <= 0) return null

        val dataEnd = 128 + byteLength * 2
        if (cleaned.length < dataEnd) return null
        val dataHex = cleaned.substring(128, dataEnd)

        val bytes = ByteArray(byteLength)
        var i = 0
        while (i < byteLength) {
            val byteHex = dataHex.substring(i * 2, i * 2 + 2)
            bytes[i] = try {
                byteHex.toInt(16).toByte()
            } catch (e: NumberFormatException) {
                return null
            }
            i += 1
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Reconstructs an exact base-unit [BigInteger] from a human-readable decimal string.
     *
     * The inverse of [convertHexToDouble]: multiplies by `10^decimals` and truncates any
     * remaining fractional part (round-down). Used where a provider only exposes a formatted
     * decimal balance (e.g. Portal's `getAssets`, Turnkey's supported-chain API) rather than
     * raw hex. Returns [BigInteger.ZERO] on parse failure or a non-positive result.
     */
    fun decimalStringToBigInteger(decimalString: String?, decimals: Int): BigInteger {
        if (decimalString.isNullOrEmpty()) return BigInteger.ZERO
        return try {
            val baseUnits = BigDecimal(decimalString)
                .movePointRight(decimals)
                .setScale(0, RoundingMode.DOWN)
                .toBigInteger()
            if (baseUnits > BigInteger.ZERO) baseUnits else BigInteger.ZERO
        } catch (e: NumberFormatException) {
            BigInteger.ZERO
        }
    }

    // ---------------------------------------------------------------------------------------
    // Deprecated Portal-result converters. The logic moved to internal extensions on
    // PortalProviderResult so the converter could shed its Portal dependency; these shims are
    // retained only so existing call sites keep compiling and linking. Slated for removal in
    // the next major version.
    // ---------------------------------------------------------------------------------------

    /**
     * Extracts a normalized hex string from a Portal provider result.
     *
     * @param portalResult Expected to be a `PortalProviderResult`; anything else yields `"0x0"`.
     */
    @Deprecated(
        message = "Portal-result handling is now internal. This shim accepts a PortalProviderResult " +
            "only; for plain hex normalization use normalizedHexString(hex) instead."
    )
    fun convertPortalResultToHexString(portalResult: Any): String =
        (portalResult as? PortalProviderResult)?.toHexString() ?: normalizedHexString(null)

    /**
     * Extracts a transaction hash from a Portal provider result.
     *
     * @param portalResult Expected to be a `PortalProviderResult`.
     * @throws RainError.ProviderError if the result is not a `PortalProviderResult` carrying a
     *         String hash.
     */
    @Deprecated(
        message = "Portal-result handling is now internal. This shim accepts a PortalProviderResult only."
    )
    fun convertPortalResultToTransactionHash(portalResult: Any): String {
        val providerResult = portalResult as? PortalProviderResult
            ?: throw RainError.ProviderError(
                IllegalStateException("Portal returned invalid transaction result: null")
            )
        return providerResult.toTransactionHash()
    }
}
