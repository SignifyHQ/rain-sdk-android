package com.rain.sdk.internal.solana

import com.rain.sdk.internal.constants.SolanaPrograms
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigInteger

/**
 * Round-trips what [SolanaTransactionBuilder] emits, since transaction history reconstructs a
 * transfer's recipient, amount and asset purely from the stored unsigned transaction.
 */
class SolanaTransactionDecoderTest {

    private val fromBytes = ByteArray(32) { (it + 1).toByte() }
    private val toBytes = ByteArray(32) { (it + 33).toByte() }
    private val mintBytes = ByteArray(32) { (it + 97).toByte() }

    private val from = Base58.encode(fromBytes)
    private val to = Base58.encode(toBytes)
    private val mint = Base58.encode(mintBytes)
    private val blockhash = Base58.encode(ByteArray(32) { (it + 65).toByte() })

    // ---------- native SOL ----------

    @Test
    fun `decodes the transfer the builder produced`() {
        val lamports = 1_234_500_000L
        val hex = SolanaTransactionBuilder.buildTransferHex(from, to, lamports, blockhash)

        val decoded = SolanaTransactionDecoder.decode(hex)

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.NativeTransfer::class.java)
        decoded as SolanaTransactionDecoder.NativeTransfer
        assertThat(decoded.from).isEqualTo(from)
        assertThat(decoded.to).isEqualTo(to)
        assertThat(decoded.lamports).isEqualTo(lamports)
    }

    @Test
    fun `tolerates an optional 0x prefix`() {
        val hex = SolanaTransactionBuilder.buildTransferHex(from, to, 1L, blockhash)
        val decoded = SolanaTransactionDecoder.decode("0x$hex")
        assertThat((decoded as SolanaTransactionDecoder.NativeTransfer).lamports).isEqualTo(1L)
    }

    @Test
    fun `returns null for non-hex or undecodable input`() {
        assertThat(SolanaTransactionDecoder.decode("not-hex!!")).isNull()
        assertThat(SolanaTransactionDecoder.decode("abcd")).isNull()
    }

    // ---------- SPL ----------

    @Test
    fun `decodes an spl transfer including the mint and scale`() {
        val decoded = SolanaTransactionDecoder.decode(splTransferHex())

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.SplTransfer::class.java)
        decoded as SolanaTransactionDecoder.SplTransfer
        assertThat(decoded.from).isEqualTo(from)
        assertThat(decoded.mint).isEqualTo(mint)
        assertThat(decoded.amount).isEqualTo(BigInteger.valueOf(1_500_000L))
        // Carried by the instruction, so history needs no mint lookup to scale the amount.
        assertThat(decoded.decimals).isEqualTo(6)
        assertThat(decoded.source).isEqualTo(Base58.encode(sourceAta()))
        assertThat(decoded.destination).isEqualTo(Base58.encode(destinationAta()))
    }

    @Test
    fun `skips the create-account instruction and finds the transfer`() {
        // A transfer to a first-time recipient is preceded by a create-token-account instruction.
        val decoded = SolanaTransactionDecoder.decode(splTransferHex(createDestination = true))

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.SplTransfer::class.java)
        assertThat((decoded as SolanaTransactionDecoder.SplTransfer).amount)
            .isEqualTo(BigInteger.valueOf(1_500_000L))
    }

    @Test
    fun `decodes a token-2022 transfer`() {
        val decoded = SolanaTransactionDecoder.decode(
            splTransferHex(tokenProgram = SolanaPrograms.TOKEN_2022)
        )

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.SplTransfer::class.java)
        assertThat((decoded as SolanaTransactionDecoder.SplTransfer).mint).isEqualTo(mint)
    }

    @Test
    fun `reads token amounts above Long MAX_VALUE`() {
        val huge = BigInteger("18446744073709551615") // u64 max
        val decoded = SolanaTransactionDecoder.decode(splTransferHex(amount = huge))

        assertThat((decoded as SolanaTransactionDecoder.SplTransfer).amount).isEqualTo(huge)
    }

    // ---------- helpers ----------

    private fun sourceAta(tokenProgram: ByteArray = SolanaPrograms.TOKEN) =
        SolanaAddresses.associatedTokenAddress(fromBytes, mintBytes, tokenProgram)

    private fun destinationAta(tokenProgram: ByteArray = SolanaPrograms.TOKEN) =
        SolanaAddresses.associatedTokenAddress(toBytes, mintBytes, tokenProgram)

    private fun splTransferHex(
        createDestination: Boolean = false,
        tokenProgram: ByteArray = SolanaPrograms.TOKEN,
        amount: BigInteger = BigInteger.valueOf(1_500_000L)
    ): String {
        val instructions = buildList {
            if (createDestination) {
                add(
                    SolanaInstructions.createAssociatedTokenAccountIdempotent(
                        tokenProgramId = tokenProgram,
                        payer = fromBytes,
                        associatedAccount = destinationAta(tokenProgram),
                        owner = toBytes,
                        mint = mintBytes
                    )
                )
            }
            add(
                SolanaInstructions.transferChecked(
                    tokenProgramId = tokenProgram,
                    source = sourceAta(tokenProgram),
                    mint = mintBytes,
                    destination = destinationAta(tokenProgram),
                    owner = fromBytes,
                    amount = amount,
                    decimals = 6
                )
            )
        }
        return SolanaTransactionBuilder.buildUnsignedHex(fromBytes, blockhash, instructions)
    }
}
