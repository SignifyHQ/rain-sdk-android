package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the Turnkey managed-broadcast chain list (WALL-31). Pure JVM — no Turnkey SDK types —
 * so unlike the adapter tests this runs on any JDK.
 *
 * A failure here after editing the list means the send gate's coverage changed: confirm the
 * change against Turnkey's broadcasting docs and mirror it in the iOS SDK before fixing.
 */
class TurnkeyBroadcastChainsTest {

    @Test
    fun `pilot and mainnet chains are sendable`() {
        val sendable = listOf(
            1,        // Ethereum
            10,       // Optimism
            137,      // Polygon
            8453,     // Base
            42161,    // Arbitrum One
            84532,    // Base Sepolia — the sandbox demo chain
            421614,   // Arbitrum Sepolia
            4217,     // Tempo
            RainChain.SOLANA_MAINNET,
            RainChain.SOLANA_DEVNET,
        )
        sendable.forEach { chainId ->
            assertThat(TurnkeyBroadcastChains.supportsSend(chainId)).isTrue()
        }
    }

    @Test
    fun `read-only chains are not sendable`() {
        val readOnly = listOf(
            RainChain.AVALANCHE_MAINNET,
            RainChain.AVALANCHE_TESTNET,
            42220,    // Celo
            324,      // ZKsync
            RainChain.SOLANA_TESTNET, // Turnkey broadcasts mainnet + devnet only
            999_999,  // unknown chain
        )
        readOnly.forEach { chainId ->
            assertThat(TurnkeyBroadcastChains.supportsSend(chainId)).isFalse()
        }
    }

    @Test
    fun `requireSendSupport throws the published error with the chain id`() {
        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            TurnkeyBroadcastChains.requireSendSupport(RainChain.AVALANCHE_MAINNET)
        }
        assertThat(error.chainId).isEqualTo(RainChain.AVALANCHE_MAINNET)
        assertThat(error.errorCode.code).isEqualTo("RAIN_105")
        assertThat(error.message).contains("cannot send")
    }

    @Test
    fun `requireSendSupport passes silently for a covered chain`() {
        TurnkeyBroadcastChains.requireSendSupport(8453)
        TurnkeyBroadcastChains.requireSendSupport(RainChain.SOLANA_DEVNET)
    }
}
