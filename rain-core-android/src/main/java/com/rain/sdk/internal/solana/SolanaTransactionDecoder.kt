package com.rain.sdk.internal.solana

import com.rain.sdk.internal.constants.SolanaPrograms
import java.math.BigInteger

/**
 * Minimal decoder for the unsigned transactions produced by [SolanaTransactionBuilder].
 *
 * Turnkey's `sol_send_transaction` activity stores only the (hex) unsigned transaction — no
 * recipient, amount or asset — so transaction history recovers those by parsing the blob back.
 *
 * Reverses the builder's wire format: compact-u16 signature count + zero-filled signatures +
 * message (header, account keys, blockhash, instructions), then looks for the instruction that
 * moves value. A transaction may carry more than one instruction (an SPL transfer to a first-time
 * recipient is preceded by a create-token-account instruction), so every instruction is examined
 * and the non-transfer ones are skipped. Returns null if the bytes are not a decodable
 * transaction containing a transfer this SDK knows how to describe.
 */
internal object SolanaTransactionDecoder {
    private const val PUBLIC_KEY_LENGTH = 32
    private const val SIGNATURE_LENGTH = 64

    /** A value transfer recovered from an unsigned transaction. */
    sealed interface Transfer {
        /** The account authorising the transfer — the wallet, in every transaction the SDK builds. */
        val from: String
    }

    /** A System Program transfer of native SOL. */
    data class NativeTransfer(
        override val from: String,
        val to: String,
        val lamports: Long
    ) : Transfer

    /**
     * An SPL `TransferChecked`. [source] and [destination] are *token accounts*, not wallets —
     * recovering the recipient's wallet from [destination] requires reading that account.
     * [decimals] is carried by the instruction itself, so the amount can be scaled without
     * fetching the mint.
     */
    data class SplTransfer(
        override val from: String,
        val source: String,
        val destination: String,
        val mint: String,
        val amount: BigInteger,
        val decimals: Int
    ) : Transfer

    /** Decodes the first value transfer in [unsignedTransactionHex], or null if there is none. */
    fun decode(unsignedTransactionHex: String): Transfer? {
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

        // Instructions — return the first one that moves value.
        val instructionCount = reader.readCompactU16()
        repeat(instructionCount) {
            val programIdIndex = reader.readByte()
            val accountIndexCount = reader.readCompactU16()
            val accountIndices = IntArray(accountIndexCount) { reader.readByte() }
            val dataLen = reader.readCompactU16()
            val data = reader.readBytes(dataLen)

            val programId = accounts.getOrNull(programIdIndex)
            if (programId != null) {
                decodeInstruction(programId, accountIndices, data, accounts)?.let { return it }
            }
        }
        return null
    }

    private fun decodeInstruction(
        programId: ByteArray,
        accountIndices: IntArray,
        data: ByteArray,
        accounts: List<ByteArray>
    ): Transfer? {
        fun account(position: Int): String? =
            accounts.getOrNull(accountIndices.getOrElse(position) { -1 })?.let { Base58.encode(it) }

        val isSystem = programId.contentEquals(SolanaPrograms.SYSTEM)
        if (isSystem &&
            data.size >= 12 &&
            readU32LE(data, 0) == SolanaInstructions.SYSTEM_TRANSFER_INDEX.toLong()
        ) {
            return NativeTransfer(
                from = account(0) ?: return null,
                to = account(1) ?: return null,
                lamports = readU64LE(data, 4)
            )
        }

        val isTokenProgram = programId.contentEquals(SolanaPrograms.TOKEN) ||
            programId.contentEquals(SolanaPrograms.TOKEN_2022)
        if (isTokenProgram &&
            data.size >= 10 &&
            (data[0].toInt() and 0xFF) == SolanaInstructions.TOKEN_TRANSFER_CHECKED_INDEX
        ) {
            // TransferChecked accounts: [source, mint, destination, owner].
            return SplTransfer(
                from = account(3) ?: return null,
                source = account(0) ?: return null,
                mint = account(1) ?: return null,
                destination = account(2) ?: return null,
                amount = readU64Unsigned(data, 1),
                decimals = data[9].toInt() and 0xFF
            )
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

    /** Token amounts are u64; read unsigned so values above `Long.MAX_VALUE` stay accurate. */
    private fun readU64Unsigned(data: ByteArray, offset: Int): BigInteger {
        var value = BigInteger.ZERO
        for (i in 7 downTo 0) {
            value = value.shiftLeft(8).or(BigInteger.valueOf(data[offset + i].toLong() and 0xFF))
        }
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
