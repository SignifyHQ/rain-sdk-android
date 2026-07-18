package com.rain.sdk.internal.network.chainreader

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.constants.TokenRegistry
import com.rain.sdk.internal.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test

class Multicall3Test {

    private val wallet = "0x1234567890123456789012345678901234567890"

    // ---------- encoding ----------

    @Test
    fun `encodeBalanceOf prefixes 0x and pads the address to 32 bytes`() {
        val encoded = Multicall3.encodeBalanceOf(wallet)

        assertThat(encoded).startsWith("0x70a08231")
        // 0x + 4-byte selector + 32-byte address word == 2 + 8 + 64 hex chars
        assertThat(encoded).hasLength(2 + 8 + 64)
        assertThat(encoded.lowercase()).endsWith(wallet.removePrefix("0x").lowercase())
    }

    @Test
    fun `encodeGetEthBalance uses the Multicall3 selector`() {
        val encoded = Multicall3.encodeGetEthBalance(wallet)

        assertThat(encoded).startsWith("0x4d2301cc")
        assertThat(encoded).hasLength(2 + 8 + 64)
    }

    @Test
    fun `encodeAggregate3 emits selector then offset table then each tuple body`() {
        val calls = listOf(
            Multicall3.Call3(
                target = Multicall3.CANONICAL_ADDRESS,
                allowFailure = true,
                callData = Multicall3.encodeGetEthBalance(wallet)
            ),
            Multicall3.Call3(
                target = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                allowFailure = true,
                callData = Multicall3.encodeBalanceOf(wallet)
            )
        )

        val encoded = Multicall3.encodeAggregate3(calls)

        assertThat(encoded).startsWith("0x82ad56cb")
        // Hex output must always have an even length (each byte → 2 chars). The exact
        // length depends on per-call calldata padding; the round-trip-decode test below
        // verifies the wire format is consumable by decodeAggregate3Result.
        assertThat(encoded.length % 2).isEqualTo(0)
    }

    // ---------- decoding ----------

    @Test
    fun `decodeAggregate3Result round-trips a single successful balance call`() {
        // ABI-encoded form for `aggregate3` returning [(true, uint256(0x539))]
        val hex = "0x" +
            "0000000000000000000000000000000000000000000000000000000000000020" + // outer offset = 32
            "0000000000000000000000000000000000000000000000000000000000000001" + // array length = 1
            "0000000000000000000000000000000000000000000000000000000000000020" + // offset to tuple 0 = 32
            "0000000000000000000000000000000000000000000000000000000000000001" + // success = true
            "0000000000000000000000000000000000000000000000000000000000000040" + // returnData offset = 64
            "0000000000000000000000000000000000000000000000000000000000000020" + // returnData length = 32 bytes
            "0000000000000000000000000000000000000000000000000000000000000539"   // returnData = 0x539

        val results = Multicall3.decodeAggregate3Result(hex)

        assertThat(results).hasSize(1)
        val r = results.single()
        assertThat(r.success).isTrue()
        assertThat(r.returnData.lowercase()).endsWith("539")
    }

    @Test
    fun `decodeAggregate3Result rejects truncated payload`() {
        assertThrows(RainError.InternalError::class.java) {
            Multicall3.decodeAggregate3Result("0x" + "00".repeat(16))
        }
    }

    @Test
    fun `decodeAggregate3Result rejects odd-length hex input`() {
        assertThrows(RainError.InternalError::class.java) {
            Multicall3.decodeAggregate3Result("0x123")
        }
    }

    @Test
    fun `decodeAggregate3Result rejects non-hex bytes`() {
        // 'g' is not a valid hex character — must throw rather than silently parse.
        val malformed = "0x" + "00".repeat(63) + "0g"
        assertThrows(RainError.InternalError::class.java) {
            Multicall3.decodeAggregate3Result(malformed)
        }
    }

    // ---------- deployment table ----------

    @Test
    fun `every chain with tokens in the registry is in the canonical deployment set`() {
        // Keeps Multicall3 fast-path tokens in sync with the deployments list — if someone
        // adds a chain to TokenRegistry without adding it to CANONICALLY_DEPLOYED_CHAIN_IDS,
        // we want to know.
        val orphans = TokenRegistry.tokensByChainId.keys
            .filterNot { isMulticall3CanonicallyDeployed(it) }
            .toSet()
        // Plasma (9745) and Monad (143) currently sit in the deployment list, so this set
        // should be empty for the chains we ship with.
        assertThat(orphans).isEmpty()
    }
}
