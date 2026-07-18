package com.rain.sdk.internal.utils

/** Chain-ID format types (e.g. EIP-155: "eip155:1"). */
internal enum class ChainIdFormat(val prefix: String) {
    EIP155("eip155");

    /** Format a chain ID as a string in this format (e.g. `"eip155:1"`). */
    fun format(chainId: Int): String = "$prefix:$chainId"

    /** Parse a formatted string into its chain ID, or `null` if the format doesn't match. */
    fun parse(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2 || parts[0] != prefix) return null
        return parts[1].toIntOrNull()
    }
}
