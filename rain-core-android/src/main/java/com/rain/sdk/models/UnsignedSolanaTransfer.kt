package com.rain.sdk.models

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.solana.SolanaTransactionBuilder

/**
 * An unsigned Solana transfer ready for a wallet provider to sign. A public model: hosts read it
 * through [RainPreparedWithdrawal.Solana.transfer], and adapters receive it from the SDK's Solana
 * support; the constructor is adapter API ([RainAdapterApi]).
 */
class UnsignedSolanaTransfer @RainAdapterApi constructor(
    transaction: ByteArray,
    val recentBlockhash: String,
    val createsRecipientAccount: Boolean = false
) {
    private val transactionBytes = transaction

    /** Defensive copy: the bytes were already simulated, so callers must not mutate them. */
    val transaction: ByteArray get() = transactionBytes.copyOf()

    val transactionHex: String get() = SolanaTransactionBuilder.hexEncode(transactionBytes)
}
