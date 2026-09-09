package com.rain.sdk.turnkey

import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.RainProvider
import com.turnkey.core.TurnkeyContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Configuration for the Turnkey provider.
 *
 * Turnkey authentication (passkeys, auth proxy, OAuth, OTP) happens outside Rain via the official
 * Turnkey Kotlin SDK: initialize [TurnkeyContext] in `Application.onCreate()`, complete login, then
 * hand the singleton here.
 *
 * @param turnkey The authenticated `TurnkeyContext` singleton.
 * @param walletAddress Optional explicit EVM address override; when null Rain uses the first
 *                      available Ethereum account from the context.
 * @param sessionPolicy Expiry/refresh/retry behavior for the session guarding every wallet call.
 * @param onSessionExpired Re-auth hook: invoked once per session death when the Turnkey session
 *                         dies and cannot be refreshed — whether that is discovered during a
 *                         wallet call or by the passive session watcher. May run on the calling
 *                         coroutine's thread or a watcher thread; hop to the main thread before
 *                         touching UI, and never call back into the SDK synchronously from it.
 *                         Restart authentication from here.
 * @param sponsorGas When true, every EVM send on a Turnkey broadcast chain is sponsored —
 *                   transfers, collateral withdrawals, Auth Pull approvals, and raw
 *                   `sendTransaction` calls alike: Turnkey's Gas Station builds and pays the
 *                   fee (gasless for the end user) and fee estimates return zero. Sponsorship
 *                   cost passes through to the partner that turns this on. Solana sends are
 *                   sponsored too (network fee only: rent for a first-time recipient's token
 *                   account is a separate Turnkey toggle, off by default, so the sender must
 *                   still hold it). Turnkey does not document its Solana payer model, so run
 *                   the devnet validation before enabling this against Solana in sandbox.
 *                   Defaults to true: sponsorship is the product, and Turnkey enables it at the
 *                   parent-organization level. On an organization where it is not enabled,
 *                   Turnkey rejects sponsored sends, so pass false there (Rain ops enables it
 *                   per Turnkey organization). Sponsored sends have no client-side revert
 *                   preflight; failures surface through Turnkey's decoded FAILED status.
 */
class TurnkeyConfig(
    val turnkey: TurnkeyContext,
    val walletAddress: String? = null,
    val sessionPolicy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
    val onSessionExpired: (() -> Unit)? = null,
    val sponsorGas: Boolean = true,
)

/**
 * Turnkey adapter — the registrable [RainProvider] for Turnkey's P256-stamper signer.
 *
 * Per the modular-architecture migration this adapter currently lives inside `rain-core-android` (in the
 * `com.rain.sdk.turnkey` package) rather than a standalone `rain-turnkey` module, so core still
 * carries the Turnkey SDK as a dependency for now. The seam is otherwise identical to a true
 * out-of-module adapter: it implements the port and owns all Turnkey-specific wiring.
 */
class TurnkeyProvider internal constructor(
    private val config: TurnkeyConfig,
    private val contextOverride: TurnkeyContextProtocol?,
) : RainProvider {

    constructor(config: TurnkeyConfig) : this(config, contextOverride = null)

    override val id: ProviderId get() = ProviderId.TURNKEY

    /**
     * Turnkey holds EVM + Solana accounts and gates signing behind passkeys/biometrics; with
     * [TurnkeyConfig.sponsorGas] on it also advertises [Capability.GAS_SPONSORSHIP]. `RainSdk`
     * copies this set onto the resolved client, so it comes from the same function as the wallet
     * provider's own set and the two cannot drift.
     */
    override val capabilities: Set<Capability> =
        TurnkeyWalletProvider.capabilitiesFor(config.sponsorGas)

    private val turnkeyContext: TurnkeyContextProtocol by lazy {
        contextOverride ?: TurnkeyContextAdapter(config.turnkey)
    }

    private val coordinator: TurnkeySessionCoordinator by lazy {
        TurnkeySessionCoordinator(
            turnkey = turnkeyContext,
            policy = config.sessionPolicy,
            onSessionExpired = config.onSessionExpired,
        )
    }

    // Lives for the provider's lifetime; the Turnkey singleton it watches is process-wide anyway.
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The Turnkey session as seen at the Rain boundary, over time. Emits on every Turnkey
     * auth/session change and when an active session passes its expiry, so a host can react to
     * a session dying silently without waiting for a wallet call to fail.
     */
    val sessionState: Flow<TurnkeySessionState> get() = coordinator.sessionStates

    /** Snapshot of [sessionState] right now. */
    fun currentSessionState(): TurnkeySessionState = coordinator.currentState()

    /**
     * Forces a Turnkey session refresh (new JWT, extended expiry) regardless of remaining
     * lifetime. Throws `RainError.TokenExpired` when the session cannot be refreshed — the
     * host must re-authenticate.
     */
    suspend fun refreshSession() = coordinator.refreshNow()

    /**
     * Stops the passive session watcher. Call when discarding this provider (e.g. rebuilding
     * the SDK for a new login) so a stale provider stops observing the process-wide Turnkey
     * singleton and can never fire its expiry hook again.
     */
    override fun close() {
        coordinator.stop()
        monitorScope.cancel()
    }

    override suspend fun create(context: ProviderContext): WalletProvider {
        // Always watch: beyond the optional host hook, the watcher drives cached-account
        // eviction on session death so a re-login can never sign with the previous user's
        // cached address.
        coordinator.startMonitoring(monitorScope)

        val provider = TurnkeyWalletProvider(
            turnkey = turnkeyContext,
            rpcEndpoints = context.rpcEndpoints,
            walletAddressOverride = config.walletAddress,
            chainReader = context.evmChainReader,
            solanaSupport = context.solanaSupport,
            tokenStore = context.tokenStore,
            sessionCoordinator = coordinator,
            sponsorGas = config.sponsorGas,
        )

        // Probe — ensures Turnkey has an EVM wallet available before the provider is handed out.
        withContext(Dispatchers.IO) {
            provider.getWalletAddress()
        }

        return provider
    }
}
