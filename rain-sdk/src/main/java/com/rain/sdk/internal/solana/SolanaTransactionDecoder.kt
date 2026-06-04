package com.rain.sdk.internal.solana

/**
 * Minimal decoder for the unsigned legacy Solana transactions produced by
 * [SolanaTransactionBuilder]. Turnkey's `sol_send_transaction` activity stores only the
 * (hex) unsigned transaction — no recipient or amount — so transaction history recovers the
 * System-transfer's destination and lamports by parsing that blob.
 *
 * Reverses the builder's wire format: compact-u16 signature count + zero-filled signatures +
 * message (header, account keys, blockhash, instructions). Returns null if the bytes aren't a
 * decodable transaction containing a System `transfer`.
 */
internal object SolanaTransactionDecoder {
    private const val PUBLIC_KEY_LENGTH = 32
    private const val SIGNATURE_LENGTH = 64
    private val SYSTEM_PROGRAM_ID = ByteArray(PUBLIC_KEY_LENGTH)
    private const val SYSTEM_TRANSFER_INSTRUCTION_INDEX = 2

    data class Transfer(val from: String, val to: String, val lamports: Long)

    fun decodeTransfer(unsignedTransactionHex: String): Transfer? {
        val bytes = hexToBytes(unsignedTransactionHex) ?: return null
        return runCatching { parse(bytes) }.getOrNull()
    }

    private fun parse(bytes: ByteArray): Transfer? {
        val reader = Reader(bytes)

        // Signature section: count + count * 64-byte placeholders.
        val signatureCount = reader.readCompactU16()
        reader.skip(signatureCount * SIGNATURE_LENGTH)

        // Message header (3 bytes), unused here.
        reader.readByte(); reader.readByte(); reader.readByte()

        // Account keys.
        val accountCount = reader.readCompactU16()
        val accounts = ArrayList<ByteArray>(accountCount)
        repeat(accountCount) { accounts += reader.readBytes(PUBLIC_KEY_LENGTH) }

        // Recent blockhash.
        reader.skip(PUBLIC_KEY_LENGTH)

        // Instructions — find the System transfer.
        val instructionCount = reader.readCompactU16()
        repeat(instructionCount) {
            val programIdIndex = reader.readByte()
            val accountIndexCount = reader.readCompactU16()
            val accountIndices = IntArray(accountIndexCount) { reader.readByte() }
            val dataLen = reader.readCompactU16()
            val data = reader.readBytes(dataLen)

            val programId = accounts.getOrNull(programIdIndex)
            val isSystemTransfer = programId != null &&
                programId.contentEquals(SYSTEM_PROGRAM_ID) &&
                data.size >= 12 &&
                readU32LE(data, 0) == SYSTEM_TRANSFER_INSTRUCTION_INDEX.toLong()
            if (isSystemTransfer) {
                val from = accounts.getOrNull(accountIndices.getOrElse(0) { -1 }) ?: return null
                val to = accounts.getOrNull(accountIndices.getOrElse(1) { -1 }) ?: return null
                return Transfer(
                    from = Base58.encode(from),
                    to = Base58.encode(to),
                    lamports = readU64LE(data, 4)
                )
            }
        }
        return null
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun readByte(): Int {
            require(pos < bytes.size) { "Unexpected end of transaction" }
            return bytes[pos++].toInt() and 0xFF
        }

        fun readBytes(length: Int): ByteArray {
            require(pos + length <= bytes.size) { "Unexpected end of transaction" }
            return bytes.copyOfRange(pos, pos + length).also { pos += length }
        }

        fun skip(length: Int) {
            require(pos + length <= bytes.size) { "Unexpected end of transaction" }
            pos += length
        }

        /** Solana short-vec (compact-u16): 7 bits per byte, MSB = continuation. */
        fun readCompactU16(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = readByte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun readU64LE(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = if (hex.startsWith("0x", ignoreCase = true)) hex.substring(2) else hex
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
