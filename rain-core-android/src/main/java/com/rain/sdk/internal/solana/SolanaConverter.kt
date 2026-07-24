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

    /**
     * Converts a human-readable SOL amount to whole lamports, exactly: an amount finer than one
     * lamport or beyond the lamport range throws [ArithmeticException] rather than truncating.
     */
    fun solToLamports(sol: BigDecimal): Long {
        require(sol.signum() >= 0) { "SOL amount must be non-negative: ${sol.toPlainString()}" }
        return sol.movePointRight(SOL_DECIMALS).toBigIntegerExact().longValueExact()
    }

    /** Formats raw lamports as a human-readable SOL [BigDecimal] (`lamports / 1e9`). */
    fun lamportsToSol(lamports: BigInteger): BigDecimal =
        lamports.toBigDecimal().movePointLeft(SOL_DECIMALS)
}
