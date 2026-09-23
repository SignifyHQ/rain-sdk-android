package com.rain.sdk.internal.utils

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

internal object RainHexUtils {
    private const val ADDRESS_LENGTH_IN_HEX = 40

    /**
     * Validates and checksums an Ethereum address.
     * Throws [RainError.InvalidConfig] if the address is invalid.
     */
    fun validateAndChecksum(address: String, paramName: String): String {
        if (!isValidAddress(address)) {
            throw RainError.InvalidConfig("Invalid $paramName format: $address")
        }
        return toChecksumAddress(address)
    }

    /**
     * Converts a hex string (optional "0x" prefix) to bytes, rejecting odd lengths and non-hex
     * characters. Pass [expectedByteCount] to also enforce an exact size (e.g. 65 for signatures).
     */
    fun hexToBytes(s: String, expectedByteCount: Int? = null): ByteArray {
        val cleanS = Numeric.cleanHexPrefix(s)
        val len = cleanS.length
        if (len % 2 != 0) {
            throw RainError.InvalidConfig("Invalid hex string: odd length ($len)")
        }
        if (expectedByteCount != null && len / 2 != expectedByteCount) {
            throw RainError.InvalidConfig(
                "Invalid hex string: expected $expectedByteCount bytes, got ${len / 2}"
            )
        }
        val data = ByteArray(len / 2)
        for (i in cleanS.indices step 2) {
            val hi = Character.digit(cleanS[i], 16)
            val lo = Character.digit(cleanS[i + 1], 16)
            if (hi < 0 || lo < 0) {
                throw RainError.InvalidConfig("Invalid hex string: non-hex character at index $i")
            }
            data[i / 2] = ((hi shl 4) + lo).toByte()
        }
        return data
    }

    /**
     * Validates if the string is a valid Ethereum address format.
     */
    fun isValidAddress(address: String): Boolean {
        return try {
            val cleanAddress = Numeric.cleanHexPrefix(address)
            cleanAddress.length == ADDRESS_LENGTH_IN_HEX && cleanAddress.matches(Regex("^[0-9a-fA-F]+$"))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts an address to its checksummed format (EIP-55).
     */
    fun toChecksumAddress(address: String): String {
        return Keys.toChecksumAddress(address)
    }
}

/**
 * Validates an EVM address a host hands an adapter and returns it in EIP-55 checksum form. Adapters
 * call it on a `walletAddress` override at construction, so a typo fails there rather than as a
 * foreign address on every read. Beyond [RainHexUtils.validateAndChecksum]'s shape check, a
 * mixed-case input must already carry the right checksum; all-lowercase and all-uppercase inputs
 * carry no checksum and are normalized.
 *
 * @throws RainError.InvalidConfig (`RAIN_102`) when [address] is not 40 hex characters with an
 *   optional `0x` or `0X` prefix, or when its mixed-case checksum does not match.
 */
@RainAdapterApi
fun validateAndChecksumAddress(address: String, paramName: String): String {
    // The shape check below recognises only a lowercase prefix; normalise first so `0X` is accepted.
    val body = address.strippingHexPrefix()
    val checksummed = RainHexUtils.validateAndChecksum("0x$body", paramName)
    val mixedCase = body.any { it.isUpperCase() } && body.any { it.isLowerCase() }
    if (mixedCase && body != checksummed.removePrefix("0x")) {
        throw RainError.InvalidConfig("Invalid $paramName checksum: $address")
    }
    return checksummed
}
