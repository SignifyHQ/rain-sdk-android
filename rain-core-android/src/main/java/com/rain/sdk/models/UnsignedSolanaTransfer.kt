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
    /** The blockhash the transaction was built against, in base58; it stays valid for about 60 to 90 seconds. */
    val recentBlockhash: String,
    /** True when the transfer creates the recipient's token account, whose rent the sender pays even when the fee is sponsored. */
    val createsRecipientAccount: Boolean = false
) {
    private val transactionBytes = transaction

    /**
     * Defensive copy: the bytes were simulated (unless the provider sponsors the fee, which skips the
     * dry run), so callers must not mutate them.
     */
    val transaction: ByteArray get() = transactionBytes.copyOf()

    /** [transaction] as hex without a `0x` prefix. */
    val transactionHex: String get() = SolanaTransactionBuilder.hexEncode(transactionBytes)
}
