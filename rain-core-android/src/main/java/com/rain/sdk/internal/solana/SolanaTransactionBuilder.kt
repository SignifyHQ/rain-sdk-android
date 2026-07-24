package com.rain.sdk.internal.solana

import java.io.ByteArrayOutputStream

/**
 * Builds the hex-encoded **unsigned** Solana transaction that Turnkey's `sol_send_transaction`
 * activity expects (it hex-decodes and parses the unsigned payload, signs it with the wallet's
 * ed25519 key, and broadcasts). NOTE: the Turnkey type docstring says "base64", but the live
 * API hex-decodes the field (`encoding/hex`), so this emits hex.
 *
 * The wire format matches `@solana/web3.js` `Transaction.serialize({ requireAllSignatures: false })`:
 * a legacy transaction = compact-u16 signature count + one zero-filled 64-byte signature
 * placeholder + the serialized message. Turnkey fills the placeholder with the real signature.
 *
 * [buildUnsignedTransaction] takes an arbitrary instruction list, which is what an SPL transfer
 * needs — it may carry a create-token-account instruction ahead of the transfer. Native SOL keeps
 * its own [buildTransferHex] entry point.
 *
 * Pure and dependency-free so it can be unit-tested byte-for-byte; see SolanaTransactionBuilderTest.
 */
internal object SolanaTransactionBuilder {
    private const val PUBLIC_KEY_LENGTH = 32
    private const val SIGNATURE_LENGTH = 64
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
    ): String = hexEncode(buildTransferBytes(fromAddress, toAddress, lamports, recentBlockhash))

    fun buildTransferBytes(
        fromAddress: String,
        toAddress: String,
        lamports: Long,
        recentBlockhash: String
    ): ByteArray {
        val from = decodeKey(fromAddress, "from")
        val to = decodeKey(toAddress, "to")
        return buildUnsignedTransaction(
            feePayer = from,
            recentBlockhash = recentBlockhash,
            instructions = listOf(SolanaInstructions.systemTransfer(from, to, lamports))
        )
    }

    /** Lowercase hex of [buildUnsignedTransaction], ready for Turnkey's `unsignedTransaction`. */
    fun buildUnsignedHex(
        feePayer: ByteArray,
        recentBlockhash: String,
        instructions: List<Instruction>
    ): String = hexEncode(buildUnsignedTransaction(feePayer, recentBlockhash, instructions))

    /**
     * Serializes [instructions] into an unsigned legacy transaction paid for by [feePayer].
     *
     * Only the fee payer signs: every flow here authorises with the wallet's own key, so a
     * required signer other than [feePayer] would produce a transaction Turnkey cannot complete.
     * That is rejected rather than silently emitted.
     */
    fun buildUnsignedTransaction(
        feePayer: ByteArray,
        recentBlockhash: String,
        instructions: List<Instruction>
    ): ByteArray {
        require(instructions.isNotEmpty()) { "A transaction needs at least one instruction" }
        require(feePayer.size == PUBLIC_KEY_LENGTH) {
            "Invalid fee payer (expected 32 bytes, got ${feePayer.size})"
        }
        val blockhash = decodeKey(recentBlockhash, "recentBlockhash")

        val accounts = accountTable(feePayer, instructions)
        val extraSigners = accounts.count { it.isSigner } - 1
        require(extraSigners == 0) {
            "Transaction requires $extraSigners signer(s) besides the fee payer"
        }

        val message = serializeMessage(accounts, blockhash, instructions)

        val tx = ByteArrayOutputStream()
        // Signature section: one required signature (the fee payer), zero-filled placeholder.
        tx.write(compactU16(1))
        tx.write(ByteArray(SIGNATURE_LENGTH))
        tx.write(message)
        return tx.toByteArray()
    }

    /**
     * Orders the message's account table the way the runtime requires: fee payer first, then
     * writable signers, readonly signers, writable non-signers, readonly non-signers — with the
     * invoked programs last, since a program is always a readonly non-signer.
     *
     * An account used by several instructions appears once, holding the union of the privileges
     * those instructions asked for (a create-then-transfer pair, for instance, needs the new token
     * account writable in both).
     */
    private fun accountTable(feePayer: ByteArray, instructions: List<Instruction>): List<AccountMeta> {
        val programIds = LinkedHashMap<List<Byte>, ByteArray>()
        instructions.forEach { programIds.putIfAbsent(it.programId.toList(), it.programId) }

        val feePayerKey = feePayer.toList()
        val merged = LinkedHashMap<List<Byte>, AccountMeta>()
        for (instruction in instructions) {
            for (account in instruction.accounts) {
                require(account.pubkey.size == PUBLIC_KEY_LENGTH) {
                    "Invalid instruction account (expected 32 bytes, got ${account.pubkey.size})"
                }
                val key = account.pubkey.toList()
                // The fee payer and the programs are placed explicitly, below.
                if (key == feePayerKey || programIds.containsKey(key)) continue
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    account
                } else {
                    AccountMeta(
                        pubkey = existing.pubkey,
                        isSigner = existing.isSigner || account.isSigner,
                        isWritable = existing.isWritable || account.isWritable
                    )
                }
            }
        }

        // Stable sort, so accounts with equal privileges keep first-seen order.
        val ordered = merged.values.sortedWith(
            compareBy({ !it.isSigner }, { !it.isWritable })
        )

        return buildList {
            add(AccountMeta.signerAndWritable(feePayer))
            addAll(ordered)
            programIds.values.forEach { add(AccountMeta.readonly(it)) }
        }
    }

    private fun serializeMessage(
        accounts: List<AccountMeta>,
        blockhash: ByteArray,
        instructions: List<Instruction>
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // Message header. The account table is ordered so these counts describe it unambiguously.
        out.write(accounts.count { it.isSigner })
        out.write(accounts.count { it.isSigner && !it.isWritable })
        out.write(accounts.count { !it.isSigner && !it.isWritable })

        // Account keys.
        out.write(compactU16(accounts.size))
        accounts.forEach { out.write(it.pubkey) }

        // Recent blockhash.
        out.write(blockhash)

        // Instructions, each referencing accounts by index into the table above.
        val indexOf = HashMap<List<Byte>, Int>(accounts.size)
        accounts.forEachIndexed { index, account -> indexOf.putIfAbsent(account.pubkey.toList(), index) }

        out.write(compactU16(instructions.size))
        for (instruction in instructions) {
            out.write(indexOf.getValue(instruction.programId.toList()))
            out.write(compactU16(instruction.accounts.size))
            instruction.accounts.forEach { out.write(indexOf.getValue(it.pubkey.toList())) }
            out.write(compactU16(instruction.data.size))
            out.write(instruction.data)
        }

        return out.toByteArray()
    }

    /**
     * Lowercase hex, the encoding Turnkey's `unsignedTransaction` field expects. Exposed because
     * the same bytes are also base64-encoded for `simulateTransaction`, so callers serialize once.
     */
    internal fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
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
}
