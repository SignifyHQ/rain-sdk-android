package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SolanaTransactionDecoderTest {

    private val from = Base58.encode(ByteArray(32) { (it + 1).toByte() })
    private val to = Base58.encode(ByteArray(32) { (it + 33).toByte() })
    private val blockhash = Base58.encode(ByteArray(32) { (it + 65).toByte() })

    @Test
    fun `decodes the transfer the builder produced`() {
        val lamports = 1_234_500_000L
        val hex = SolanaTransactionBuilder.buildTransferHex(from, to, lamports, blockhash)

        val decoded = SolanaTransactionDecoder.decodeTransfer(hex)

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.from).isEqualTo(from)
        assertThat(decoded.to).isEqualTo(to)
        assertThat(decoded.lamports).isEqualTo(lamports)
    }

    @Test
    fun `tolerates an optional 0x prefix`() {
        val hex = SolanaTransactionBuilder.buildTransferHex(from, to, 1L, blockhash)
        assertThat(SolanaTransactionDecoder.decodeTransfer("0x$hex")?.lamports).isEqualTo(1L)
    }

    @Test
    fun `returns null for non-hex or undecodable input`() {
        assertThat(SolanaTransactionDecoder.decodeTransfer("not-hex!!")).isNull()
        assertThat(SolanaTransactionDecoder.decodeTransfer("abcd")).isNull()
    }
}
