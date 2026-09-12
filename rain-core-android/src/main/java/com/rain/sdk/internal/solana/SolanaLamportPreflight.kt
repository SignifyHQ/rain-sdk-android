package com.rain.sdk.internal.solana

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.error.RainError
import timber.log.Timber
import java.math.BigInteger

/**
 * The SOL a wallet must hold before a composed transaction is handed to a provider, shared by the
 * transfer and collateral-withdrawal composers so the two cannot drift.
 *
 * Fee sponsorship covers the network fee only. Rent for a token account the transaction creates is
 * a separate vendor setting (off by default): unless that is on, the sender pays it, so
 * it is always required here. The constants gate a friendlier up-front error than a broadcast that
 * fails on chain; a self-paid transaction is simulated afterwards as well.
 */
@RainAdapterApi
object SolanaLamportPreflight {
    /** Base fee for a single-signature Solana transaction. */
    const val FEE_LAMPORTS = 5_000L

    /**
     * Rent-exempt minimum for a 165-byte SPL token account (~0.00204 SOL), paid by the sender when
     * a transaction has to create the recipient's account. Read from the chain it would be
     * `getMinimumBalanceForRentExemption(165)`.
     */
    const val TOKEN_ACCOUNT_RENT_LAMPORTS = 2_039_280L

    /**
     * Throws [RainError.InsufficientFunds] when [address] cannot cover what this transaction
     * charges it: the fee unless [feesSponsored], plus rent when [includeAccountRent]. Makes no
     * RPC call when nothing is required.
     */
    suspend fun require(
        rpc: SolanaRpcClient,
        rpcUrl: String,
        address: String,
        includeAccountRent: Boolean,
        feesSponsored: Boolean
    ) {
        val required = (if (feesSponsored) 0L else FEE_LAMPORTS) +
            if (includeAccountRent) TOKEN_ACCOUNT_RENT_LAMPORTS else 0L
        if (required == 0L) return
        val lamports = rpc.getBalanceLamports(rpcUrl, address)
        if (lamports < BigInteger.valueOf(required)) {
            Timber.w(
                "Rain SDK: wallet %s holds %s lamports, needs %d for this transaction",
                address,
                lamports.toString(),
                required
            )
            throw RainError.InsufficientFunds()
        }
    }
}
