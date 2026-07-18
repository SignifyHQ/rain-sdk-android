package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.utils.strippingHexPrefix

/**
 * ABI encoding/decoding for the [Multicall3](https://www.multicall3.com) contract, limited
 * to the functions the SDK actually uses: `aggregate3`, `getEthBalance`, and ERC-20
 * `balanceOf` (encoded against a token contract, not Multicall3 itself).
 *
 * Pure functions — no I/O — so unit tests can lock in calldata against fixtures.
 */
internal object Multicall3 {

    /**
     * Canonical Multicall3 deployment address (https://www.multicall3.com), deployed at the
     * same address on most major EVM chains. The set of chains where this address is
     * known-deployed lives in `Multicall3Deployments.kt`.
     */
    const val CANONICAL_ADDRESS = "0xcA11bde05977b3631167028862bE2a173976CA11"

    // Multicall3-specific function selectors (first 4 bytes of keccak256(signature)).
    // ERC-20 selectors live in `ERC20Selectors`.
    private const val AGGREGATE3_SELECTOR = "82ad56cb"     // aggregate3((address,bool,bytes)[])
    private const val GET_ETH_BALANCE_SELECTOR = "4d2301cc" // getEthBalance(address)

    /** One entry in an `aggregate3` batch. */
    data class Call3(
        /** Target contract address (the token contract for `balanceOf`, or `CANONICAL_ADDRESS` for `getEthBalance`). */
        val target: String,
        /** If true, a revert in this individual call doesn't fail the whole batch. */
        val allowFailure: Boolean,
        /** Pre-encoded calldata (hex string, with or without `0x` prefix). */
        val callData: String
    )

    /** One entry in the `aggregate3` response. */
    data class Result(
        val success: Boolean,
        /** Hex-encoded return data, with `0x` prefix. Empty (`"0x"`) when the call reverted with no return data. */
        val returnData: String
    )

    /**
     * Encodes calldata for `aggregate3((address,bool,bytes)[])`. Returns a hex string with
     * the `0x` prefix, suitable as the `data` field of `eth_call`.
     */
    fun encodeAggregate3(calls: List<Call3>): String {
        val out = StringBuilder("0x").append(AGGREGATE3_SELECTOR)
        // Outer ABI layout for a single dynamic argument:
        //   [0x20 offset to array][array body...]
        out.append(hex32(32))
        // Array body: [length][offset_1]...[offset_N][tuple_1]...[tuple_N]
        out.append(hex32(calls.size))

        val offsets = mutableListOf<Int>()
        val bodies = mutableListOf<String>()
        // Offsets are measured from the start of the array body (the position immediately
        // after the length slot). The offsets table itself sits at positions 0..32*N within
        // that body, so the first tuple begins at offset = 32*N.
        var runningOffset = 32 * calls.size

        for (call in calls) {
            offsets += runningOffset
            val callDataLenBytes = hexByteCount(call.callData)
            val paddedBytes = ((callDataLenBytes + 31) / 32) * 32
            // Tuple body layout:
            //   [target(32)][allowFailure(32)][callData_offset(=0x60)(32)][callData_length(32)][callData_padded(paddedBytes)]
            val bodySize = 96 + 32 + paddedBytes
            runningOffset += bodySize

            val body = StringBuilder()
            body.append(hex32Address(call.target))
            body.append(hex32(if (call.allowFailure) 1 else 0))
            body.append(hex32(96))
            body.append(hex32(callDataLenBytes))
            body.append(rightPad32(call.callData.strippingHexPrefix()))
            bodies += body.toString()
        }

        for (offset in offsets) out.append(hex32(offset))
        for (body in bodies) out.append(body)
        return out.toString()
    }

    /**
     * Decodes the return value of `aggregate3` — an array of `(bool, bytes)`. Throws
     * [RainError.InternalError] on a malformed payload.
     */
    fun decodeAggregate3Result(hex: String): List<Result> {
        val bytes = decodeHex(hex)
        // Layout: [0x20 offset to array][array body...]
        // Array body: [length][offset_1]...[offset_N][tuple_1]...[tuple_N]
        if (bytes.size < 64) {
            throw RainError.InternalError("Multicall3 result too short (<64 bytes)")
        }
        val count = parseBe(bytes, 32, 64)
        val arrayBodyStart = 64
        val offsetsTableEnd = arrayBodyStart + 32 * count
        if (bytes.size < offsetsTableEnd) {
            throw RainError.InternalError("Multicall3 result truncated at offsets table")
        }

        val results = ArrayList<Result>(count)
        for (i in 0 until count) {
            val offsetSlot = arrayBodyStart + 32 * i
            val tupleOffset = arrayBodyStart + parseBe(bytes, offsetSlot, offsetSlot + 32)
            // Each tuple: [success(32)][returnData_offset(=0x40)(32)][returnData_length(32)][returnData_padded]
            if (bytes.size < tupleOffset + 96) {
                throw RainError.InternalError("Multicall3 tuple #$i truncated")
            }
            val success = bytes[tupleOffset + 31].toInt() == 1
            val dataLen = parseBe(bytes, tupleOffset + 64, tupleOffset + 96)
            val dataStart = tupleOffset + 96
            val dataEnd = dataStart + dataLen
            if (bytes.size < dataEnd) {
                throw RainError.InternalError("Multicall3 tuple #$i returnData truncated")
            }
            val dataHex = buildString(2 + dataLen * 2) {
                append("0x")
                for (idx in dataStart until dataEnd) {
                    append(String.format("%02x", bytes[idx].toInt() and 0xFF))
                }
            }
            results += Result(success = success, returnData = dataHex)
        }
        return results
    }

    /**
     * Encodes calldata for `getEthBalance(address)` — the Multicall3 helper that returns
     * the native balance, so native + ERC-20 can ride in one batch.
     */
    fun encodeGetEthBalance(address: String): String =
        "0x" + GET_ETH_BALANCE_SELECTOR + hex32Address(address)

    /** Encodes calldata for ERC-20 `balanceOf(address)`. */
    fun encodeBalanceOf(address: String): String =
        "0x" + ERC20Selectors.BALANCE_OF + hex32Address(address)

    // ---------- Hex helpers ----------

    private fun hexByteCount(s: String): Int = s.strippingHexPrefix().length / 2

    private fun hex32(value: Int): String {
        val h = value.toString(16)
        return "0".repeat(maxOf(0, 64 - h.length)) + h
    }

    /** Left-pads a 20-byte address to 32 bytes (64 hex chars). */
    private fun hex32Address(address: String): String {
        val clean = address.strippingHexPrefix().lowercase()
        return "0".repeat(maxOf(0, 64 - clean.length)) + clean
    }

    /**
     * Right-pads a hex string with zeros so its byte length is a multiple of 32. Empty
     * input returns empty (no padding needed for a zero-length `bytes`).
     */
    private fun rightPad32(hex: String): String {
        val chars = hex.length
        if (chars == 0) return ""
        val mod = chars % 64
        val padChars = if (mod == 0) 0 else 64 - mod
        return hex + "0".repeat(padChars)
    }

    /**
     * Strict hex decoder. Rejects odd-length input or non-hex bytes rather than silently
     * truncating, so callers see a clean error instead of corrupted decoded data.
     */
    private fun decodeHex(hex: String): ByteArray {
        val clean = hex.strippingHexPrefix()
        if (clean.length % 2 != 0) {
            throw RainError.InternalError("Multicall3 result has odd hex length")
        }
        val out = ByteArray(clean.length / 2)
        var i = 0
        var b = 0
        while (i < clean.length) {
            val high = hexNibble(clean[i])
            val low = hexNibble(clean[i + 1])
            if (high < 0 || low < 0) {
                throw RainError.InternalError("Multicall3 result contains invalid hex byte")
            }
            out[b] = ((high shl 4) or low).toByte()
            i += 2
            b += 1
        }
        return out
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> 10 + (c - 'a')
        in 'A'..'F' -> 10 + (c - 'A')
        else -> -1
    }

    /**
     * Parses a big-endian byte slice as `Int`. Values read here (counts, offsets, lengths)
     * are always < 2^31 in practice for any realistic batch.
     */
    private fun parseBe(bytes: ByteArray, fromInclusive: Int, toExclusive: Int): Int {
        var v = 0
        for (i in fromInclusive until toExclusive) {
            v = (v shl 8) or (bytes[i].toInt() and 0xFF)
        }
        return v
    }
}
