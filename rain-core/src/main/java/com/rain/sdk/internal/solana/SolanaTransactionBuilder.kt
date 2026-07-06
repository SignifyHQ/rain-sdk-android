package com.rain.sdk.internal.solana

import java.io.ByteArrayOutputStream

/**
 * Builds the hex-encoded **unsigned** Solana transaction that Turnkey's `sol_send_transaction`
 * activity expects (it hex-decodes and parses the unsigned payload, signs it with the wallet's
 * ed25519 key, and broadcasts). NOTE: the Turnkey type docstring says "base64", but the live
 * API hex-decodes the field (`encoding/hex`), so this emits hex.
 *
 * Scope is a single native SOL transfer (System Program `transfer`). The wire format matches
 * `@solana/web3.js` `Transaction.serialize({ requireAllSignatures: false })`: a legacy
 * transaction = compact-u16 signature count + one zero-filled 64-byte signature placeholder +
 * the serialized message. Turnkey fills the placeholder with the real signature.
 *
 * Pure and dependency-free so it can be unit-tested byte-for-byte; see SolanaTransactionBuilderTest.
 */
internal object SolanaTransactionBuilder {
    private const val PUBLIC_KEY_LENGTH = 32
    private const val SIGNATURE_LENGTH = 64
    private val SYSTEM_PROGRAM_ID = ByteArray(PUBLIC_KEY_LENGTH) // all-zero account = 1111...1111
    private const val SYSTEM_TRANSFER_INSTRUCTION_INDEX = 2 // SystemInstruction::Transfer
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /**
     * @param fromAddress  fee-payer / sender, Base58
     * @param toAddress    recipient, Base58
     * @param lamports     amount in lamports
     * @param recentBlockhash recent blockhash, Base58 (from `getLatestBlockhash`)
     * @return lowercase hex of the serialized unsigned transaction
     */
    fun buildTransferHex(
        fromAddress: String,
        toAddress: String,
        lamports: Long,
        recentBlockhash: String
    ): String = toHex(buildTransferBytes(fromAddress, toAddress, lamports, recentBlockhash))

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun buildTransferBytes(
        fromAddress: String,
        toAddress: String,
        lamports: Long,
        recentBlockhash: String
    ): ByteArray {
        require(lamports >= 0) { "lamports must be non-negative: $lamports" }
        val from = decodeKey(fromAddress, "from")
        val to = decodeKey(toAddress, "to")
        val blockhash = decodeKey(recentBlockhash, "recentBlockhash")

        val message = serializeMessage(from, to, blockhash, lamports)

        val tx = ByteArrayOutputStream()
        // Signature section: one required signature (the fee payer), zero-filled placeholder.
        tx.write(compactU16(1))
        tx.write(ByteArray(SIGNATURE_LENGTH))
        tx.write(message)
        return tx.toByteArray()
    }

    private fun serializeMessage(
        from: ByteArray,
        to: ByteArray,
        blockhash: ByteArray,
        lamports: Long
    ): ByteArray {
        // Account ordering rule: writable-signers, readonly-signers, writable-nonsigners,
        // readonly-nonsigners. For a transfer that's [from, to, systemProgram].
        val out = ByteArrayOutputStream()

        // Message header.
        out.write(1) // numRequiredSignatures (fee payer)
        out.write(0) // numReadonlySignedAccounts
        out.write(1) // numReadonlyUnsignedAccounts (the System Program)

        // Account keys.
        out.write(compactU16(3))
        out.write(from)
        out.write(to)
        out.write(SYSTEM_PROGRAM_ID)

        // Recent blockhash.
        out.write(blockhash)

        // Instructions (exactly one: the transfer).
        out.write(compactU16(1))
        out.write(2) // programIdIndex -> SYSTEM_PROGRAM_ID at account index 2
        out.write(compactU16(2)) // account indices used by the instruction
        out.write(0) // from
        out.write(1) // to
        val data = transferInstructionData(lamports)
        out.write(compactU16(data.size))
        out.write(data)

        return out.toByteArray()
    }

    /** SystemProgram transfer data: u32 LE instruction index (2) followed by u64 LE lamports. */
    private fun transferInstructionData(lamports: Long): ByteArray {
        val data = ByteArray(12)
        writeU32LE(data, 0, SYSTEM_TRANSFER_INSTRUCTION_INDEX.toLong())
        writeU64LE(data, 4, lamports)
        return data
    }

    private fun decodeKey(address: String, label: String): ByteArray {
        val bytes = try {
            Base58.decode(address)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Solana $label address: $address", e)
        }
        require(bytes.size == PUBLIC_KEY_LENGTH) {
            "Invalid Solana $label address (expected 32 bytes, got ${bytes.size}): $address"
        }
        return bytes
    }

    /** Solana short-vec (compact-u16) length encoding: 7 bits per byte, MSB = continuation. */
    internal fun compactU16(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "compact-u16 out of range: $value" }
        val out = ByteArrayOutputStream(3)
        var remaining = value
        while (true) {
            val low = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.write(low)
                break
            }
            out.write(low or 0x80)
        }
        return out.toByteArray()
    }

    private fun writeU32LE(target: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until 4) {
            target[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun writeU64LE(target: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until 8) {
            target[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }
}
