package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the exact byte layout of the unsigned legacy transfer transaction handed to Turnkey,
 * matching `@solana/web3.js` `Transaction.serialize({ requireAllSignatures: false })`:
 * compact-u16(1) + 64-byte zero signature placeholder + message (header, account keys,
 * blockhash, one System transfer instruction).
 */
class SolanaTransactionBuilderTest {

    private val fromBytes = ByteArray(32) { (it + 1).toByte() }
    private val toBytes = ByteArray(32) { (it + 33).toByte() }
    private val blockhashBytes = ByteArray(32) { (it + 65).toByte() }

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
}
