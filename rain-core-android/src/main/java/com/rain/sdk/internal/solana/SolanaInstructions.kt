package com.rain.sdk.internal.solana

import com.rain.sdk.internal.constants.SolanaPrograms
import java.math.BigInteger

/**
 * An account referenced by an instruction, with the privileges that instruction needs.
 *
 * `isSigner` / `isWritable` are not properties of the account itself but of its use here:
 * [SolanaTransactionBuilder] merges the flags across every instruction in a transaction to build
 * the message's account table and header.
 *
 * [pubkey] is held by reference, not copied — treat it as immutable.
 */
internal class AccountMeta(
    val pubkey: ByteArray,
    val isSigner: Boolean = false,
    val isWritable: Boolean = false
) {
    companion object {
        fun readonly(pubkey: ByteArray) = AccountMeta(pubkey)
        fun writable(pubkey: ByteArray) = AccountMeta(pubkey, isWritable = true)
        fun signer(pubkey: ByteArray) = AccountMeta(pubkey, isSigner = true)
        fun signerAndWritable(pubkey: ByteArray) = AccountMeta(pubkey, isSigner = true, isWritable = true)
    }
}

/** A single program invocation: which program, which accounts, and the program's own payload. */
internal class Instruction(
    val programId: ByteArray,
    val accounts: List<AccountMeta>,
    val data: ByteArray
)

/**
 * Builders for the instructions the SDK sends on Solana.
 *
 * Account order within each instruction is fixed by the target program's interface and must not
 * be rearranged — programs read their accounts positionally.
 */
internal object SolanaInstructions {
    /** `SystemInstruction::Transfer`. Also matched by [SolanaTransactionDecoder]. */
    const val SYSTEM_TRANSFER_INDEX = 2

    /**
     * `TokenInstruction::TransferChecked`. Preferred over `Transfer` (3): the program verifies the
     * mint and decimals on chain, so a wrong-scale amount fails instead of moving the wrong value.
     * Also matched by [SolanaTransactionDecoder].
     */
    const val TOKEN_TRANSFER_CHECKED_INDEX = 12

    /**
     * `AssociatedTokenAccountInstruction::CreateIdempotent`. Preferred over `Create` (0), which
     * fails if the account already exists.
     */
    private const val ATA_CREATE_IDEMPOTENT_INDEX = 1

    private val U64_LIMIT = BigInteger.TWO.pow(64)

    /** Moves native SOL between system accounts. */
    fun systemTransfer(from: ByteArray, to: ByteArray, lamports: Long): Instruction {
        require(lamports >= 0) { "lamports must be non-negative: $lamports" }
        val data = ByteArray(12)
        writeU32LE(data, 0, SYSTEM_TRANSFER_INDEX.toLong())
        writeU64LE(data, 4, BigInteger.valueOf(lamports))
        return Instruction(
            programId = SolanaPrograms.SYSTEM,
            accounts = listOf(AccountMeta.signerAndWritable(from), AccountMeta.writable(to)),
            data = data
        )
    }

    /**
     * Moves SPL tokens between two token accounts of the same mint.
     *
     * @param tokenProgramId program that owns [mint] — [SolanaPrograms.TOKEN] or
     *                       [SolanaPrograms.TOKEN_2022]
     * @param source      sender's token account (not their wallet)
     * @param destination recipient's token account (not their wallet)
     * @param owner       wallet authorising the transfer; signs the transaction
     * @param amount      amount in the mint's base units
     * @param decimals    the mint's decimals, verified on chain against [mint]
     */
    fun transferChecked(
        tokenProgramId: ByteArray,
        source: ByteArray,
        mint: ByteArray,
        destination: ByteArray,
        owner: ByteArray,
        amount: BigInteger,
        decimals: Int
    ): Instruction {
        require(amount.signum() >= 0 && amount < U64_LIMIT) {
            "SPL amount out of u64 range: $amount"
        }
        require(decimals in 0..255) { "Invalid mint decimals: $decimals" }

        val data = ByteArray(10)
        data[0] = TOKEN_TRANSFER_CHECKED_INDEX.toByte()
        writeU64LE(data, 1, amount)
        data[9] = decimals.toByte()

        return Instruction(
            programId = tokenProgramId,
            accounts = listOf(
                AccountMeta.writable(source),
                AccountMeta.readonly(mint),
                AccountMeta.writable(destination),
                AccountMeta.signer(owner)
            ),
            data = data
        )
    }

    /**
     * Creates [owner]'s associated token account for [mint], or does nothing if it already exists.
     *
     * [payer] funds the account's rent-exempt balance, so on a transfer this is the *sender*, not
     * the recipient who ends up owning the account.
     */
    fun createAssociatedTokenAccountIdempotent(
        tokenProgramId: ByteArray,
        payer: ByteArray,
        associatedAccount: ByteArray,
        owner: ByteArray,
        mint: ByteArray
    ): Instruction = Instruction(
        programId = SolanaPrograms.ASSOCIATED_TOKEN,
        accounts = listOf(
            AccountMeta.signerAndWritable(payer),
            AccountMeta.writable(associatedAccount),
            AccountMeta.readonly(owner),
            AccountMeta.readonly(mint),
            AccountMeta.readonly(SolanaPrograms.SYSTEM),
            AccountMeta.readonly(tokenProgramId)
        ),
        data = byteArrayOf(ATA_CREATE_IDEMPOTENT_INDEX.toByte())
    )

    private fun writeU32LE(target: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until 4) {
            target[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun writeU64LE(target: ByteArray, offset: Int, value: BigInteger) {
        for (i in 0 until 8) {
            target[offset + i] = value.shiftRight(8 * i).and(BigInteger.valueOf(0xFF)).toByte()
        }
    }
}
