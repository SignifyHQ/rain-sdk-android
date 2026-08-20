package com.rain.sdk.internal.utils

import com.rain.sdk.internal.error.RainError
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
