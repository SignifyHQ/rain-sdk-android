package com.rain.sdk.internal.solana

import com.rain.sdk.internal.constants.SolanaPrograms
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.sol4k.PublicKey
import org.sol4k.Transaction
import org.sol4k.instruction.BaseInstruction
import java.math.BigInteger
import org.sol4k.AccountMeta as Sol4kAccountMeta

/**
 * Pins the exact byte layout of the unsigned legacy transaction handed to Turnkey, matching
 * `@solana/web3.js` `Transaction.serialize({ requireAllSignatures: false })`: compact-u16(1) +
 * 64-byte zero signature placeholder + message (header, account keys, blockhash, instructions).
 *
 * The native-transfer layout is asserted field by field — it is already in production, so the
 * generalized serializer must reproduce it byte for byte. The multi-instruction cases add a
 * cross-check against sol4k (test-only dependency), which independently orders the account table.
 */
class SolanaTransactionBuilderTest {

    private val fromBytes = ByteArray(32) { (it + 1).toByte() }
    private val toBytes = ByteArray(32) { (it + 33).toByte() }
    private val blockhashBytes = ByteArray(32) { (it + 65).toByte() }
    private val mintBytes = ByteArray(32) { (it + 97).toByte() }

    private val from = Base58.encode(fromBytes)
    private val to = Base58.encode(toBytes)
    private val blockhash = Base58.encode(blockhashBytes)

    @Test
    fun `compactU16 encodes small and multi-byte lengths`() {
        assertThat(SolanaTransactionBuilder.compactU16(0)).isEqualTo(byteArrayOf(0))
        assertThat(SolanaTransactionBuilder.compactU16(1)).isEqualTo(byteArrayOf(1))
        assertThat(SolanaTransactionBuilder.compactU16(127)).isEqualTo(byteArrayOf(127))
        // 128 -> 0x80 0x01 (7 bits per byte, continuation bit on the first)
        assertThat(SolanaTransactionBuilder.compactU16(128))
            .isEqualTo(byteArrayOf(0x80.toByte(), 0x01))
    }

    @Test
    fun `serialized transfer has the exact expected layout`() {
        val lamports = 1_000_000_000L // 1 SOL
        val tx = SolanaTransactionBuilder.buildTransferBytes(from, to, lamports, blockhash)

        var i = 0
        // Signature section: count = 1, then a 64-byte zero placeholder.
        assertThat(tx[i++]).isEqualTo(1.toByte())
        assertThat(tx.copyOfRange(i, i + 64)).isEqualTo(ByteArray(64)); i += 64

        // Message header: 1 required signature, 0 readonly signed, 1 readonly unsigned.
        assertThat(tx[i++]).isEqualTo(1.toByte())
        assertThat(tx[i++]).isEqualTo(0.toByte())
        assertThat(tx[i++]).isEqualTo(1.toByte())

        // Account keys: count = 3, [from, to, systemProgram(all-zero)].
        assertThat(tx[i++]).isEqualTo(3.toByte())
        assertThat(tx.copyOfRange(i, i + 32)).isEqualTo(fromBytes); i += 32
        assertThat(tx.copyOfRange(i, i + 32)).isEqualTo(toBytes); i += 32
        assertThat(tx.copyOfRange(i, i + 32)).isEqualTo(ByteArray(32)); i += 32

        // Recent blockhash.
        assertThat(tx.copyOfRange(i, i + 32)).isEqualTo(blockhashBytes); i += 32

        // Instructions: count = 1; programIdIndex = 2; accounts [0, 1]; data (12 bytes).
        assertThat(tx[i++]).isEqualTo(1.toByte())
        assertThat(tx[i++]).isEqualTo(2.toByte())
        assertThat(tx[i++]).isEqualTo(2.toByte())
        assertThat(tx[i++]).isEqualTo(0.toByte())
        assertThat(tx[i++]).isEqualTo(1.toByte())
        assertThat(tx[i++]).isEqualTo(12.toByte())
        // Transfer instruction data: u32 LE (2) + u64 LE lamports.
        assertThat(tx.copyOfRange(i, i + 4)).isEqualTo(byteArrayOf(2, 0, 0, 0)); i += 4
        assertThat(tx.copyOfRange(i, i + 8))
            .isEqualTo(byteArrayOf(0x00, 0xCA.toByte(), 0x9A.toByte(), 0x3B, 0, 0, 0, 0)); i += 8

        assertThat(i).isEqualTo(tx.size)
        assertThat(tx.size).isEqualTo(215)
    }

    @Test
    fun `buildTransferHex emits lowercase hex of the serialized bytes`() {
        val lamports = 1_000_000_000L
        val bytes = SolanaTransactionBuilder.buildTransferBytes(from, to, lamports, blockhash)
        val hex = SolanaTransactionBuilder.buildTransferHex(from, to, lamports, blockhash)

        // Turnkey hex-decodes this field (not base64), so it must be valid lowercase hex.
        assertThat(hex).matches("[0-9a-f]+")
        assertThat(hex.length).isEqualTo(bytes.size * 2)
        assertThat(hex).isEqualTo(bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `rejects non-32-byte addresses`() {
        val tooShort = Base58.encode(ByteArray(31))
        assertThrows(IllegalArgumentException::class.java) {
            SolanaTransactionBuilder.buildTransferHex(tooShort, to, 1L, blockhash)
        }
    }

    @Test
    fun `rejects negative lamports`() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaTransactionBuilder.buildTransferHex(from, to, -1L, blockhash)
        }
    }

    // ---------- SPL transfers ----------

    @Test
    fun `spl transfer instruction data is transfer_checked with amount and decimals`() {
        val instruction = SolanaInstructions.transferChecked(
            tokenProgramId = SolanaPrograms.TOKEN,
            source = sourceAta(),
            mint = mintBytes,
            destination = destinationAta(),
            owner = fromBytes,
            amount = BigInteger.valueOf(1_500_000L), // 1.5 USDC at 6dp
            decimals = 6
        )

        // tag 12 (TransferChecked) + u64 LE amount + u8 decimals.
        assertThat(instruction.data).isEqualTo(
            byteArrayOf(12, 0x60, 0xE3.toByte(), 0x16, 0, 0, 0, 0, 0, 6)
        )
        assertThat(instruction.programId).isEqualTo(SolanaPrograms.TOKEN)
        // Positional account order is part of the token program's interface.
        assertThat(instruction.accounts.map { Base58.encode(it.pubkey) }).containsExactly(
            Base58.encode(sourceAta()),
            Base58.encode(mintBytes),
            Base58.encode(destinationAta()),
            from
        ).inOrder()
        assertThat(instruction.accounts.map { it.isWritable })
            .containsExactly(true, false, true, false).inOrder()
        assertThat(instruction.accounts.map { it.isSigner })
            .containsExactly(false, false, false, true).inOrder()
    }

    @Test
    fun `create associated token account instruction is idempotent`() {
        val instruction = SolanaInstructions.createAssociatedTokenAccountIdempotent(
            tokenProgramId = SolanaPrograms.TOKEN,
            payer = fromBytes,
            associatedAccount = destinationAta(),
            owner = toBytes,
            mint = mintBytes
        )

        // Discriminator 1 = CreateIdempotent (0 would fail when the account already exists).
        assertThat(instruction.data).isEqualTo(byteArrayOf(1))
        assertThat(instruction.programId).isEqualTo(SolanaPrograms.ASSOCIATED_TOKEN)
        assertThat(instruction.accounts.map { Base58.encode(it.pubkey) }).containsExactly(
            from,
            Base58.encode(destinationAta()),
            to,
            Base58.encode(mintBytes),
            SolanaPrograms.SYSTEM_ADDRESS,
            SolanaPrograms.TOKEN_ADDRESS
        ).inOrder()
    }

    @Test
    fun `spl transfer without account creation matches the reference serialization`() {
        val instructions = listOf(splTransfer())

        assertThat(buildOurs(instructions)).isEqualTo(buildReference(instructions))
    }

    @Test
    fun `spl transfer that also creates the recipient account matches the reference`() {
        val instructions = listOf(createRecipientAccount(), splTransfer())

        assertThat(buildOurs(instructions)).isEqualTo(buildReference(instructions))
    }

    @Test
    fun `account table dedupes shared accounts and keeps privileges`() {
        val tx = SolanaTransactionBuilder.buildUnsignedTransaction(
            feePayer = fromBytes,
            recentBlockhash = blockhash,
            instructions = listOf(createRecipientAccount(), splTransfer())
        )

        // Skip the signature section and read the header + account table.
        var i = 1 + 64
        val requiredSignatures = tx[i++].toInt()
        val readonlySigned = tx[i++].toInt()
        val readonlyUnsigned = tx[i++].toInt()
        val accountCount = tx[i++].toInt()
        val accounts = (0 until accountCount).map {
            tx.copyOfRange(i + it * 32, i + it * 32 + 32)
        }

        // The mint and the destination ATA are used by both instructions but appear once here,
        // and the ordering is fee payer, writable non-signers, readonly non-signers, programs.
        // Note the recipient's wallet and its token account are distinct accounts.
        assertThat(accounts.map { Base58.encode(it) }).containsExactly(
            from,
            Base58.encode(destinationAta()),
            Base58.encode(sourceAta()),
            to,
            Base58.encode(mintBytes),
            SolanaPrograms.SYSTEM_ADDRESS,
            SolanaPrograms.ASSOCIATED_TOKEN_ADDRESS,
            SolanaPrograms.TOKEN_ADDRESS
        ).inOrder()
        assertThat(accountCount).isEqualTo(8)

        // Only the fee payer signs. The readonly non-signers are the recipient wallet, the mint,
        // and the three programs.
        assertThat(requiredSignatures).isEqualTo(1)
        assertThat(readonlySigned).isEqualTo(0)
        assertThat(readonlyUnsigned).isEqualTo(5)
    }

    @Test
    fun `rejects a transaction needing a signer other than the fee payer`() {
        val other = ByteArray(32) { (it + 129).toByte() }
        val instruction = Instruction(
            programId = SolanaPrograms.SYSTEM,
            accounts = listOf(
                AccountMeta.signerAndWritable(fromBytes),
                AccountMeta.signer(other)
            ),
            data = byteArrayOf(0)
        )

        assertThrows(IllegalArgumentException::class.java) {
            SolanaTransactionBuilder.buildUnsignedTransaction(fromBytes, blockhash, listOf(instruction))
        }
    }

    @Test
    fun `rejects an empty instruction list`() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaTransactionBuilder.buildUnsignedTransaction(fromBytes, blockhash, emptyList())
        }
    }

    @Test
    fun `rejects an spl amount that does not fit a u64`() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaInstructions.transferChecked(
                tokenProgramId = SolanaPrograms.TOKEN,
                source = sourceAta(),
                mint = mintBytes,
                destination = destinationAta(),
                owner = fromBytes,
                amount = BigInteger.TWO.pow(64),
                decimals = 6
            )
        }
    }

    // ---------- helpers ----------

    private fun sourceAta() =
        SolanaAddresses.associatedTokenAddress(fromBytes, mintBytes, SolanaPrograms.TOKEN)

    private fun destinationAta() =
        SolanaAddresses.associatedTokenAddress(toBytes, mintBytes, SolanaPrograms.TOKEN)

    private fun splTransfer() = SolanaInstructions.transferChecked(
        tokenProgramId = SolanaPrograms.TOKEN,
        source = sourceAta(),
        mint = mintBytes,
        destination = destinationAta(),
        owner = fromBytes,
        amount = BigInteger.valueOf(1_500_000L),
        decimals = 6
    )

    private fun createRecipientAccount() = SolanaInstructions.createAssociatedTokenAccountIdempotent(
        tokenProgramId = SolanaPrograms.TOKEN,
        payer = fromBytes,
        associatedAccount = destinationAta(),
        owner = toBytes,
        mint = mintBytes
    )

    private fun buildOurs(instructions: List<Instruction>): ByteArray =
        SolanaTransactionBuilder.buildUnsignedTransaction(fromBytes, blockhash, instructions)

    /**
     * The same instructions serialized by sol4k. Its instruction catalogue differs slightly
     * (no idempotent-create), so the payloads are replayed through [BaseInstruction] — what is
     * being compared is the account-table ordering and message framing, not the payloads, which
     * the tests above pin directly.
     */
    private fun buildReference(instructions: List<Instruction>): ByteArray {
        val reference = Transaction(
            blockhash,
            instructions.map { instruction ->
                BaseInstruction(
                    instruction.data,
                    instruction.accounts.map {
                        Sol4kAccountMeta(PublicKey(it.pubkey), it.isSigner, it.isWritable)
                    },
                    PublicKey(instruction.programId)
                )
            },
            PublicKey(fromBytes)
        )
        // sol4k writes one signature slot per registered signature; Turnkey expects exactly one
        // zero-filled placeholder for the fee payer, which it overwrites.
        reference.addSignature(Base58.encode(ByteArray(64)))
        return reference.serialize()
    }
}
