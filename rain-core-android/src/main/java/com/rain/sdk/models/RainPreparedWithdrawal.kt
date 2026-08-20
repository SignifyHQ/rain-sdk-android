package com.rain.sdk.models

import com.rain.sdk.internal.solana.UnsignedSolanaTransfer

/**
 * A collateral withdrawal built but not broadcast, in the shape the target chain requires.
 *
 * Not offline: building this still prompts the wallet to sign EIP-712 (EVM) and reads the
 * collateral's admin set on chain. A Solana blockhash lasts ~150 slots (60-90s) — submit
 * promptly or re-prepare.
 */
sealed interface RainPreparedWithdrawal {

    /** A complete, submittable EVM transaction — from/to/value/data, not bare calldata. */
    data class Evm(val parameters: RainTransactionParameters) : RainPreparedWithdrawal

    /** The serialized unsigned Solana transaction, already simulated, with its recent blockhash. */
    data class Solana(val transfer: UnsignedSolanaTransfer) : RainPreparedWithdrawal

    /** The EVM parameters, or null on a Solana withdrawal. */
    val evmParameters: RainTransactionParameters?
        get() = (this as? Evm)?.parameters

    /** The unsigned Solana transfer, or null on an EVM withdrawal. */
    val solanaTransfer: UnsignedSolanaTransfer?
        get() = (this as? Solana)?.transfer
}
