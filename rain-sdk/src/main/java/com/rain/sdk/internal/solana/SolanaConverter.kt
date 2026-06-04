package com.rain.sdk.internal.solana

import java.math.BigDecimal
import java.math.BigInteger

/**
 * SOL <-> lamports conversion, mirroring [com.rain.sdk.utils.EthereumConverter]'s role for
 * wei. 1 SOL = 1e9 lamports; SOL therefore has 9 decimals (vs 18 for EVM native currencies).
 */
internal object SolanaConverter {
    const val SOL_DECIMALS = 9
    const val LAMPORTS_PER_SOL = 1_000_000_000L

    private val LAMPORTS_PER_SOL_BD = BigDecimal(LAMPORTS_PER_SOL)

    /**
     * Converts a human-readable SOL amount to whole lamports, truncating any fraction below
     * one lamport. Parsed via [BigDecimal] string to avoid binary floating-point drift.
     */
    fun solToLamports(sol: Double): Long {
        require(sol >= 0.0) { "SOL amount must be non-negative: $sol" }
        return BigDecimal(sol.toString())
            .multiply(LAMPORTS_PER_SOL_BD)
            .toBigInteger()
            .longValueExact()
    }

    /** Formats raw lamports as a human-readable SOL [BigDecimal] (`lamports / 1e9`). */
    fun lamportsToSol(lamports: BigInteger): BigDecimal =
        lamports.toBigDecimal().movePointLeft(SOL_DECIMALS)
}
