package com.rain.sdk.turnkey

import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.internal.solana.SolanaTransferComposer
import com.rain.sdk.internal.solana.UnsignedSolanaTransfer
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.utils.EthereumConverter
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Turnkey-based implementation of [WalletProvider]. Materialized by [TurnkeyProvider] when a
 * registered Turnkey provider is resolved.
 *
 * Balance reads route through Turnkey's `get_wallet_address_balances` when the chain is in
 * [TurnkeyBroadcastChains.BALANCE_API_CHAIN_IDS]; everything else falls through to the injected
 * [ChainReader] (parallel `eth_call` + Multicall3 where deployed).
 */
internal class TurnkeyWalletProvider(
    private val manager: TurnkeyManager,
    private val chainReader: ChainReader,
    private val solanaChainReader: ChainReader,
    private val solanaTransferComposer: SolanaTransferComposer,
    private val tokenStore: TokenMetadataStore,
) : WalletProvider {

    override val id: ProviderId get() = ProviderId.TURNKEY

    /**
     * Turnkey holds EVM + Solana accounts (multi-chain) and gates signing behind passkeys/biometrics.
     * With [sponsorGas] on it also advertises [Capability.GAS_SPONSORSHIP], which tells core to
     * skip the self-paid preflights that would charge the fee to the wallet (the Solana
     * collateral-withdrawal dry run) when composing transactions this provider will sign.
     * Shared with [TurnkeyProvider] through [capabilitiesFor]: `RainSdk` copies the descriptor's
     * set onto the resolved client, so the two must never disagree.
     */
    override val capabilities: Set<Capability> get() = capabilitiesFor(sponsorGas)

    /** One value for the whole adapter: the manager's flag decides the send body, this decides the estimate. */
    private val sponsorGas: Boolean get() = manager.sponsorGas

    /** Core's up-front gate for withdrawals and approvals; the registry answers, as for transfers. */
    override fun requireSendSupport(chainId: Int) = TurnkeyBroadcastChains.requireSendSupport(chainId)

    /** Sponsorship applies exactly where Turnkey can broadcast; elsewhere the wallet pays. */
    override fun sponsorsFees(chainId: Int): Boolean =
        sponsorGas && TurnkeyBroadcastChains.supportsSend(chainId)

    /** Picks the reader for [chainId]'s chain family. */
    private fun chainReaderFor(chainId: Int): ChainReader =
        if (SolanaChains.isSolanaChain(chainId)) solanaChainReader else chainReader

    internal companion object {
        /**
         * The capabilities a Turnkey provider advertises for a given [sponsorGas] setting. The one
         * source for both the [TurnkeyProvider] descriptor (what hosts see through
         * `client.capabilities` and `rain.first { }`) and the wallet provider it creates (what core
         * reads when composing transactions), so a host and core can never disagree about
         * whether this provider's sends are sponsored.
         */
        fun capabilitiesFor(sponsorGas: Boolean): Set<Capability> = buildSet {
            add(Capability.MULTI_CHAIN)
            add(Capability.BIOMETRIC_GATE)
            if (sponsorGas) add(Capability.GAS_SPONSORSHIP)
        }
    }

    /** True when Turnkey's `get-balances` API covers [chainId] (EVM allowlist or any Solana cluster). */
    private fun usesTurnkeyForBalances(chainId: Int): Boolean =
        chainId in TurnkeyBroadcastChains.BALANCE_API_CHAIN_IDS || SolanaChains.isSolanaChain(chainId)

    // ---------- address ----------

    override suspend fun getWalletAddress(): String = manager.getAddress()

    /**
     * Chain-aware address. Solana chains resolve the Turnkey Solana account (base58, ed25519);
     * every other chain shares the Ethereum account. Internal balance / send paths use this so
     * a Solana request never reads or signs with the EVM address.
     */
    override suspend fun getWalletAddress(chainId: Int): String = manager.getWalletAddress(chainId)

    // ---------- high-level send ----------

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal
    ): String {
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        if (SolanaChains.isSolanaChain(chainId)) {
            return sendSolanaNative(chainId, toAddress, amountInEth)
        }
        val from = getWalletAddress(chainId)
        val decimals = tokenStore.nativeCurrency(chainId).decimals
        val valueHex = EthereumConverter.convertEthToWeiHex(amountInEth, decimals)
        return manager.sendEvmTransaction(
            chainId = chainId,
            from = from,
            to = toAddress,
            data = "0x",
            value = valueHex
        )
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int
    ): String {
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        if (SolanaChains.isSolanaChain(chainId)) {
            // `decimals` is deliberately unread — it is not authoritative here. sendSolanaSplToken
            // reads the mint's own scale from the chain, which `TransferChecked` then enforces.
            return sendSolanaSplToken(chainId, contractAddress, toAddress, amount)
        }
        val from = getWalletAddress(chainId)
        val data = Erc20Abi.encodeTransfer(toAddress, amount, decimals)
        return manager.sendEvmTransaction(
            chainId = chainId,
            from = from,
            to = contractAddress,
            data = data,
            value = "0x0"
        )
    }

    // ---------- low-level send / sign / fee ----------

    /**
     * Raw sends follow [sponsorGas] exactly like the transfer entries. Withdrawals, Auth Pull
     * approvals, and host-composed calldata arrive here, and Turnkey sponsors any
     * `ethSendTransaction`, not just plain transfers. A zero-balance card user's first action
     * is often the Auth Pull approval, so leaving these self-paid would defeat the feature and
     * would make the zero fee estimate wrong for exactly these flows. Sponsorship cost passes
     * through to the partner that turned the flag on.
     */
    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String = manager.sendEvmTransaction(chainId, from, to, data, value)

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        requireEvmChain(chainId, "signTypedData")
        return manager.signTypedData(walletAddress, typedDataJson)
    }

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): BigDecimal {
        requireEvmChain(chainId, "estimateTransactionFee")
        if (sponsorsFees(chainId)) {
            // Every EVM send is sponsored under this flag, so zero is the honest quote for
            // transfers, withdrawals, and approvals alike (product decision: pass through what
            // Turnkey charges the sender, which is nothing). Estimating as if the sender paid
            // would also reject the zero-balance wallets sponsorship serves.
            return BigDecimal.ZERO
        }
        return manager.estimateTransactionFee(chainId, from, to, data, value)
    }

    // ---------- balances ----------

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        val walletAddress = getWalletAddress(chainId)

        // Solana has its own balance policy (Turnkey-first with an RPC fallback),
        // so it branches out before the EVM logic below.
        if (SolanaChains.isSolanaChain(chainId)) {
            return solanaBalance(chainId, walletAddress, token)
        }

        return when (token) {
            is Token.Contract -> {
                // `eth_call balanceOf` is the same operation everywhere — delegate to the
                // chain reader so the SDK has one implementation rather than per-adapter copies.
                val info = tokenStore.tokenInfo(chainId, token.address)
                chainReaderFor(chainId).getBalance(
                    chainId = chainId,
                    walletAddress = walletAddress,
                    token = token,
                    tokenInfo = info
                )
            }
            is Token.Native -> {
                if (!usesTurnkeyForBalances(chainId)) {
                    chainReaderFor(chainId).getBalance(
                        chainId = chainId,
                        walletAddress = walletAddress,
                        token = Token.Native,
                        tokenInfo = null
                    )
                } else {
                    manager.evmNativeBalance(chainId, walletAddress, tokenStore)
                }
            }
        }
    }

    /**
     * Solana balance read. Turnkey is the primary source, with the Solana RPC reader as the
     * fallback for both native SOL and SPL tokens — Turnkey does not index every cluster (devnet
     * in particular), and the node always does.
     */
    private suspend fun solanaBalance(chainId: Int, walletAddress: String, token: Token): Balance =
        runCatching {
            // Turnkey omits zero balances, and on a cluster it does not index every mint looks
            // like a zero — so a missing SPL entry is re-read from the node rather than reported
            // as zero with unknown decimals.
            manager.solanaBalanceOrNull(chainId, walletAddress, token, tokenStore)
                ?: chainReaderFor(chainId).getBalance(
                    chainId = chainId,
                    walletAddress = walletAddress,
                    token = token,
                    tokenInfo = (token as? Token.Contract)?.let { registeredSplToken(chainId, it.address) }
                )
        }.getOrElse {
            if (it is CancellationException) throw it
            // A dead session must surface, not be masked by the node fallback — the coordinator
            // already tried a refresh before this error was thrown.
            if (it is RainError.TokenExpired) throw it
            chainReaderFor(chainId).getBalance(
                chainId = chainId,
                walletAddress = walletAddress,
                token = token,
                tokenInfo = (token as? Token.Contract)?.let { registeredSplToken(chainId, it.address) }
            )
        }

    /**
     * Host-registered metadata for [mint], if any.
     *
     * Reads only the registry — never [TokenMetadataStore.tokenInfo], whose enrichment path goes
     * through the EVM reader and cannot describe an SPL mint. This is how a caller names a token
     * that no indexer covers, via `registerTokens`.
     */
    private suspend fun registeredSplToken(chainId: Int, mint: String): TokenInfo? =
        tokenStore.registeredTokens(chainId).firstOrNull { it.address.equals(mint, ignoreCase = true) }

    override suspend fun getBalances(chainId: Int): List<Balance> {
        val walletAddress = getWalletAddress(chainId)

        if (!usesTurnkeyForBalances(chainId)) {
            val tokens = tokenStore.registeredTokens(chainId)
            val all = chainReaderFor(chainId).getBalances(chainId, walletAddress, tokens)
            return all.filter { balance ->
                balance.token is Token.Native || balance.rawAmount > BigInteger.ZERO
            }
        }

        if (SolanaChains.isSolanaChain(chainId)) {
            return manager.solanaBalancesOrNull(chainId, walletAddress, tokenStore)
                ?: solanaBalancesFromNode(chainId, walletAddress)
        }

        return manager.evmBalances(chainId, walletAddress, tokenStore)
    }

    /**
     * Native SOL plus the SPL tokens the wallet holds, read from the node. Zero balances are
     * dropped, matching every other chain. Naming falls back to host-registered tokens, so a
     * mint no indexer covers can still be labelled by the caller rather than shown as a bare
     * address.
     */
    private suspend fun solanaBalancesFromNode(chainId: Int, walletAddress: String): List<Balance> {
        val all = chainReaderFor(chainId).getBalances(
            chainId,
            walletAddress,
            tokenStore.registeredTokens(chainId)
        )
        return all.filter { balance ->
            balance.token is Token.Native || balance.rawAmount > BigInteger.ZERO
        }
    }

    // ---------- transactions ----------

    /**
     * Transaction history. Turnkey's indexed history queries are the primary source, since they
     * cover the wallet's full on-chain history (receives and externally-submitted transactions
     * included). When the indexed query is unavailable, most commonly because the history feature
     * is not enabled for the Turnkey organization, the provider falls back to the activity log,
     * which lists only transactions sent through Turnkey.
     */
    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        try {
            return if (SolanaChains.isSolanaChain(chainId)) {
                manager.indexedSolanaTransactions(chainId, limit, offset, order)
            } else {
                manager.indexedEvmTransactions(chainId, limit, offset, order)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError.TokenExpired) {
            // The activity path needs the same session, so falling back would only fail again.
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Rain SDK: Turnkey indexed history unavailable, falling back to activities")
        }
        return if (SolanaChains.isSolanaChain(chainId)) {
            manager.getSolanaTransactionsFromActivities(chainId, limit, offset, order)
        } else {
            manager.getEvmTransactionsFromActivities(chainId, limit, offset, order)
        }
    }

    // ---------- solana sends ----------

    private suspend fun sendSolanaNative(
        chainId: Int,
        toAddress: String,
        amountInSol: BigDecimal
    ): String {
        val from = getWalletAddress(chainId)
        val unsigned = solanaTransferComposer.composeNative(chainId, from, toAddress, amountInSol)
        return manager.submitSolanaTransaction(chainId, from, unsigned)
    }

    /**
     * Sends SPL tokens. Composition and every preflight (mint resolution, token-account
     * derivation/creation, balance and fee checks, simulation) live in [SolanaTransferComposer];
     * this method only signs and broadcasts through Turnkey.
     */
    private suspend fun sendSolanaSplToken(
        chainId: Int,
        mintAddress: String,
        toAddress: String,
        amount: BigDecimal
    ): String {
        val from = getWalletAddress(chainId)
        val unsigned = solanaTransferComposer.composeSplToken(
            chainId,
            from,
            mintAddress,
            toAddress,
            amount,
            sponsoredFees = sponsorGas
        )
        return manager.submitSolanaTransaction(chainId, from, unsigned)
    }

    /**
     * Signs and broadcasts a core-composed Solana transaction (e.g. a collateral withdrawal)
     * with the Turnkey Solana account. Follows [sponsorGas] like every other send: with it on,
     * Turnkey covers the fee, and because this provider then advertises
     * [Capability.GAS_SPONSORSHIP], core composes the withdrawal without its self-paid dry run
     * (which would charge the fee to a wallet that pays none). The composed message is submitted
     * as-is either way.
     */
    override suspend fun sendSolanaTransaction(
        chainId: Int,
        unsigned: UnsignedSolanaTransfer
    ): String {
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        return manager.submitSolanaTransaction(chainId, getWalletAddress(chainId), unsigned)
    }
}

/** Rejects a Solana chain id on the EVM-only entry points, which have no Solana equivalent. */
internal fun requireEvmChain(chainId: Int, operation: String) {
    if (SolanaChains.isSolanaChain(chainId)) {
        throw RainError.InvalidConfig(
            "$operation is EVM-only; use sendNativeToken/sendToken on Solana chainId=$chainId"
        )
    }
}
