package com.rain.sdk.internal.solana

import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.error.RainError
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/**
 * An unsigned Solana transfer ready for a wallet provider to sign. Public so out-of-module
 * adapters can receive it through [SolanaSupport]; see that class for the visibility rationale.
 */
class UnsignedSolanaTransfer internal constructor(
    transaction: ByteArray,
    val recentBlockhash: String,
    val createsRecipientAccount: Boolean = false
) {
    private val transactionBytes = transaction

    /** Defensive copy — the bytes were already simulated, so callers must not mutate them. */
    val transaction: ByteArray get() = transactionBytes.copyOf()

    val transactionHex: String get() = SolanaTransactionBuilder.hexEncode(transactionBytes)
}

/**
 * Composes unsigned Solana transfers, running every preflight the SDK requires before a wallet
 * provider signs: address validation, mint resolution, associated-token-account derivation and
 * creation, balance and fee checks, and a simulation dry run for SPL transfers. Providers only
 * sign and broadcast the result, so the checks cannot drift between them.
 */
internal class SolanaTransferComposer(
    private val solanaRpcClient: SolanaRpcClient,
    private val rpcUrlResolver: (Int) -> String?
) {
    /** A native SOL transfer. [amountInSol] is converted exactly; sub-lamport precision is rejected. */
    suspend fun composeNative(
        chainId: Int,
        fromAddress: String,
        toAddress: String,
        amountInSol: BigDecimal
    ): UnsignedSolanaTransfer {
        if (amountInSol.signum() < 0) {
            throw RainError.InvalidAmount(amountInSol.toPlainString(), "must not be negative")
        }
        val lamports = try {
            SolanaConverter.solToLamports(amountInSol)
        } catch (e: ArithmeticException) {
            throw RainError.InvalidAmount(
                amountInSol.toPlainString(),
                "SOL supports at most ${SolanaConverter.SOL_DECIMALS} decimal places"
            )
        }
        val rpcUrl = resolveRpcUrl(chainId)
        val blockhash = solanaRpcClient.getLatestBlockhash(rpcUrl)
        val transaction = SolanaTransactionBuilder.buildTransferBytes(
            fromAddress = fromAddress,
            toAddress = toAddress,
            lamports = lamports,
            recentBlockhash = blockhash
        )
        return UnsignedSolanaTransfer(transaction, blockhash)
    }

    /**
     * An SPL transfer, creating the recipient's token account in the same transaction when they
     * do not have one yet.
     *
     * Tokens live in per-mint *token accounts*, not in the wallet itself, so [toAddress] is the
     * recipient's wallet and the account that actually receives the transfer is derived from it.
     * Creating a missing recipient account costs the sender rent (~0.002 SOL).
     *
     * The mint is read from the chain first: its decimals scale [amount], and the token program
     * that owns it both receives the transfer and seeds the token-account derivation. The
     * transfer is simulated against the cluster while still unsigned, so a failure surfaces as a
     * typed error rather than as a broadcast that quietly fails on chain.
     */
    suspend fun composeSplToken(
        chainId: Int,
        fromAddress: String,
        mintAddress: String,
        toAddress: String,
        amount: BigDecimal
    ): UnsignedSolanaTransfer {
        val rpcUrl = resolveRpcUrl(chainId)

        val ownerKey = decodeAddress(fromAddress, "sender")
        val recipientKey = decodeAddress(toAddress, "recipient")
        val mintKey = decodeAddress(mintAddress, "mint")
        if (fromAddress == toAddress) {
            throw RainError.InvalidRecipient(toAddress, "the recipient is this wallet")
        }

        val mint = solanaRpcClient.getMintInfo(rpcUrl, mintAddress)
            ?: throw RainError.TokenNotFound(mintAddress, chainId)
        val tokenProgramKey = Base58.decode(mint.tokenProgram)
        val baseUnits = toBaseUnits(amount, mint.decimals)

        // The recipient must be a wallet. Passing a token account instead would derive a token
        // account *of* a token account — a well-formed address nobody can spend from.
        val recipientAccount = solanaRpcClient.getAccountInfo(rpcUrl, toAddress)
        if (recipientAccount != null && SolanaPrograms.isTokenProgram(recipientAccount.ownerProgram)) {
            throw RainError.InvalidRecipient(
                toAddress,
                "this address belongs to a token program (a token account or a mint); " +
                    "pass the recipient's wallet address instead"
            )
        }

        val sourceAta = SolanaAddresses.associatedTokenAddress(ownerKey, mintKey, tokenProgramKey)
        val destinationAta =
            SolanaAddresses.associatedTokenAddress(recipientKey, mintKey, tokenProgramKey)

        val source = solanaRpcClient.getTokenAccount(rpcUrl, Base58.encode(sourceAta))
            ?: throw RainError.TokenAccountNotFound(fromAddress, mintAddress)
        if (source.amount < baseUnits) {
            throw RainError.InsufficientTokenBalance(
                requested = amount.stripTrailingZeros().toPlainString(),
                available = BigDecimal(source.amount).movePointLeft(mint.decimals)
                    .stripTrailingZeros().toPlainString(),
                token = mintAddress
            )
        }

        val createDestination = !solanaRpcClient.accountExists(rpcUrl, Base58.encode(destinationAta))
        requireLamportsForFees(rpcUrl, fromAddress, includeAccountRent = createDestination)

        val instructions = buildList {
            if (createDestination) {
                add(
                    SolanaInstructions.createAssociatedTokenAccountIdempotent(
                        tokenProgramId = tokenProgramKey,
                        payer = ownerKey,
                        associatedAccount = destinationAta,
                        owner = recipientKey,
                        mint = mintKey
                    )
                )
            }
            add(
                SolanaInstructions.transferChecked(
                    tokenProgramId = tokenProgramKey,
                    source = sourceAta,
                    mint = mintKey,
                    destination = destinationAta,
                    owner = ownerKey,
                    amount = baseUnits,
                    decimals = mint.decimals
                )
            )
        }

        val blockhash = solanaRpcClient.getLatestBlockhash(rpcUrl)
        val transaction = SolanaTransactionBuilder.buildUnsignedTransaction(
            feePayer = ownerKey,
            recentBlockhash = blockhash,
            instructions = instructions
        )
        simulate(rpcUrl, transaction)

        Timber.d(
            "Rain SDK: sending %s of mint %s on chainId=%d (creating recipient token account: %b)",
            baseUnits.toString(),
            mintAddress,
            chainId,
            createDestination
        )
        return UnsignedSolanaTransfer(transaction, blockhash, createsRecipientAccount = createDestination)
    }

    private fun resolveRpcUrl(chainId: Int): String = rpcUrlResolver(chainId)
        ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")

    /** Base58-decodes a Solana address, rejecting anything that isn't a 32-byte key. */
    private fun decodeAddress(address: String, label: String): ByteArray {
        val bytes = runCatching { Base58.decode(address) }.getOrNull()
        if (bytes == null || bytes.size != SOLANA_PUBLIC_KEY_LENGTH) {
            throw RainError.InvalidConfig("Invalid Solana $label address: $address")
        }
        return bytes
    }

    /**
     * Scales a human-readable [amount] to the mint's base units, rejecting anything finer than
     * the mint can represent rather than silently truncating it.
     */
    private fun toBaseUnits(amount: BigDecimal, decimals: Int): BigInteger {
        if (amount.signum() <= 0) {
            throw RainError.InvalidAmount(amount.toPlainString(), "must be greater than zero")
        }
        return try {
            amount.movePointRight(decimals).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw RainError.InvalidAmount(
                amount.toPlainString(),
                "this token supports at most $decimals decimal places"
            )
        }
    }

    /**
     * Fails early when the wallet cannot cover the network fee (plus token-account rent when the
     * transfer has to create one). Simulation would catch this too, but only as an opaque program
     * error — SOL for fees is a distinct problem from the token balance itself.
     */
    private suspend fun requireLamportsForFees(
        rpcUrl: String,
        address: String,
        includeAccountRent: Boolean
    ) {
        val required = SOLANA_FEE_LAMPORTS +
            if (includeAccountRent) SOLANA_TOKEN_ACCOUNT_RENT_LAMPORTS else 0L
        val lamports = solanaRpcClient.getBalanceLamports(rpcUrl, address)
        if (lamports < BigInteger.valueOf(required)) {
            Timber.w(
                "Rain SDK: wallet %s holds %s lamports, needs %d for this transfer",
                address,
                lamports.toString(),
                required
            )
            throw RainError.InsufficientFunds()
        }
    }

    /** Dry-runs the unsigned transaction, translating a simulation failure into a typed error. */
    private suspend fun simulate(rpcUrl: String, transaction: ByteArray) {
        val simulation = solanaRpcClient.simulateTransaction(
            rpcUrl,
            Base64.getEncoder().encodeToString(transaction)
        )
        if (simulation.succeeded) return

        Timber.e(
            "Rain SDK: Solana transaction simulation failed: %s; logs: %s",
            simulation.error,
            simulation.logs.joinToString(" | ")
        )
        throw RainError.TransactionSimulationFailed(
            IllegalStateException(simulation.error ?: "Solana transaction simulation failed")
        )
    }

    private companion object {
        const val SOLANA_PUBLIC_KEY_LENGTH = 32

        /** Base fee for a single-signature Solana transaction. */
        const val SOLANA_FEE_LAMPORTS = 5_000L

        /**
         * Rent-exempt minimum for a 165-byte SPL token account (~0.00204 SOL), paid by the sender
         * when a transfer has to create the recipient's account. Read from the chain it would be
         * `getMinimumBalanceForRentExemption(165)`; this constant only gates a friendlier
         * up-front error, and the transaction is simulated afterwards regardless.
         */
        const val SOLANA_TOKEN_ACCOUNT_RENT_LAMPORTS = 2_039_280L
    }
}
