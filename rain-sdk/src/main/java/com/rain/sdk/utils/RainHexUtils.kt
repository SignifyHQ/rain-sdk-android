package com.rain.sdk.utils

import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

object RainHexUtils {
    private const val ADDRESS_LENGTH_IN_HEX = 40

    /**
     * Converts a hex string to a byte array.
     * Handles optional "0x" prefix.
     */
    fun hexToBytes(s: String): ByteArray {
        val cleanS = Numeric.cleanHexPrefix(s)
        val len = cleanS.length
        val data = ByteArray(len / 2)
        for (i in cleanS.indices step 2) {
            data[i / 2] = (
                (Character.digit(cleanS[i], 16) shl 4) +
                    Character.digit(cleanS[i + 1], 16)
                ).toByte()
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
