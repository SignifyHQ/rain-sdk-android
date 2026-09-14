package com.rain.sdk.turnkey

import android.app.Application
import com.rain.sdk.internal.error.RainError
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Configuration for the Turnkey provider.
 *
 * - **Bring-your-own** — `TurnkeyConfig(turnkey)`, the public Turnkey integration: the host drives
 *   Turnkey's Kotlin SDK itself (passkeys, auth proxy, OAuth, OTP), completes login, and hands the
 *   authenticated [TurnkeyContext] singleton here. The SDK never touches authentication.
 * - **Managed** — `TurnkeyConfig(application, organizationId, authProxyConfigId)`: the SDK owns
 *   Turnkey authentication (one-time code by email or SMS through Turnkey's auth proxy, Ethereum
 *   and Solana accounts provisioned on first login). Internal API, marked
 *   [InternalRainTurnkeyApi]: the building block of the RainWallet provider, not a host-facing mode.
 *
 * @param turnkey The `TurnkeyContext` singleton every wallet call goes through — authenticated by
 *                the host in bring-your-own mode, configured and authenticated by the SDK in
 *                managed mode.
 * @param walletAddress Optional explicit EVM address override; when null Rain uses the first
 *                      available Ethereum account from the context.
 * @param sessionPolicy Expiry/refresh/retry behavior for the session guarding every wallet call.
 * @param onSessionExpired Re-auth hook: invoked once per session death when the Turnkey session
 *                         dies and cannot be refreshed — whether that is discovered during a
 *                         wallet call or by the passive session watcher. May run on the calling
 *                         coroutine's thread or a watcher thread; hop to the main thread before
 *                         touching UI, and never call back into the SDK synchronously from it.
 *                         Restart authentication from here (in managed mode: `sendLoginCode` /
 *                         `confirmLoginCode` again). A deliberate `logout()` does not fire it.
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
class TurnkeyConfig internal constructor(
    val turnkey: TurnkeyContext,
    val walletAddress: String?,
    val sessionPolicy: TurnkeySessionPolicy,
    val onSessionExpired: (() -> Unit)?,
    val sponsorGas: Boolean,
    /** Managed-mode ids; null in bring-your-own mode, where the host owns authentication. */
    internal val managed: ManagedIds?,
) {
    /** SDK-managed auth against the Turnkey organization + auth-proxy configuration. */
    internal class ManagedIds(
        val application: Application,
        val organizationId: String,
        val authProxyConfigId: String,
    )

    /**
     * Bring-your-own mode — the public Turnkey integration.
     *
     * @param turnkey The authenticated `TurnkeyContext` singleton.
     */
    constructor(
        turnkey: TurnkeyContext,
        walletAddress: String? = null,
        sessionPolicy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
        onSessionExpired: (() -> Unit)? = null,
        sponsorGas: Boolean = true,
    ) : this(turnkey, walletAddress, sessionPolicy, onSessionExpired, sponsorGas, managed = null)

    /**
     * Managed mode — internal API reserved for the RainWallet provider, see [InternalRainTurnkeyApi].
     *
     * The Turnkey configuration is one-shot per app launch: the SDK applies it on the first
     * authentication call (or at provider resolution). Blank ids, a second managed provider with
     * *different* ids, or a `TurnkeyContext` the app initialized itself make every auth call throw
     * `RainError.InvalidConfig` — relaunch the app to change them; a failed Turnkey initialization
     * makes them throw `RainError.InternalError` until relaunch.
     *
     * A successful [TurnkeyProvider.confirmLoginCode] revokes the user's other Turnkey sessions on
     * every device (`invalidateExisting`): logging in on a second phone signs the first one out, and
     * that device's [onSessionExpired] fires at its next call.
     *
     * @param application The host application; Turnkey's Kotlin SDK needs it for secure storage.
     * @param organizationId Your Turnkey parent organization id.
     * @param authProxyConfigId The auth-proxy configuration id from the Turnkey dashboard.
     */
    @InternalRainTurnkeyApi
    @Suppress("LongParameterList") // the BYO constructor's parameters plus the two managed ids; four have defaults
    constructor(
        application: Application,
        organizationId: String,
        authProxyConfigId: String,
        walletAddress: String? = null,
        sessionPolicy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
        onSessionExpired: (() -> Unit)? = null,
        sponsorGas: Boolean = true,
    ) : this(
        // The vendor context is a process-wide object; managed mode configures it lazily, on the
        // first authentication call, through the provider's controller.
        TurnkeyContext,
        walletAddress,
        sessionPolicy,
        onSessionExpired,
        sponsorGas,
        managed = ManagedIds(application, organizationId, authProxyConfigId),
    )
}

/**
 * Turnkey adapter — the registrable [RainProvider] for Turnkey's P256-stamper signer.
 *
 * Ships as `rain-turnkey-android` and owns the Turnkey SDK as its own dependency, so an app that
 * registers another provider never links Turnkey. It implements the port and owns all
 * Turnkey-specific wiring.
 *
 * In managed mode the provider is also the authentication surface: construct it, run
 * [sendLoginCode] / [confirmLoginCode] on it, then build the SDK and resolve. Resolving before a
 * session is live fails with `RainError.TokenExpired`.
 */
@Suppress("TooManyFunctions") // the descriptor is also the managed authentication surface, by design
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

    /** Present in managed mode only; owns the one-time-code flow. */
    private val managedAuth: TurnkeyManagedAuthController? by lazy {
        config.managed?.let { ids ->
            TurnkeyManagedAuthController(
                context = turnkeyContext,
                coordinator = coordinator,
                configure = {
                    TurnkeyManagedConfigurator.configure(ids.application, ids.organizationId, ids.authProxyConfigId)
                },
            )
        }
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
     * singleton and can never fire its expiry hook again. In managed mode the provider is inert
     * afterwards: every auth call throws `RainError.InvalidConfig`, [currentAuthState] reads
     * `Unauthenticated` and [hasActiveSession] is false.
     */
    override fun close() {
        coordinator.stop()
        monitorScope.cancel()
        managedAuth?.close()
    }

    override suspend fun create(context: ProviderContext): WalletProvider {
        // Always watch: beyond the optional host hook, the watcher drives cached-account
        // eviction on session death so a re-login can never sign with the previous user's
        // cached address.
        coordinator.startMonitoring(monitorScope)

        // Managed mode: a session whose provisioning failed after login heals here, before the
        // probe below could reject it for a missing account.
        managedAuth?.ensureAccounts()

        // Built per create(), never lazily on the descriptor: reset() re-runs create(), and the
        // manager's address cache belongs to the session that filled it.
        val manager = TurnkeyManager(
            turnkey = turnkeyContext,
            rpcEndpoints = context.rpcEndpoints,
            solanaRpcClient = context.solanaSupport.rpc,
            sponsorGas = config.sponsorGas,
            walletAddressOverride = config.walletAddress,
            sessionCoordinator = coordinator,
        )
        val provider = TurnkeyWalletProvider(
            manager = manager,
            chainReader = context.evmChainReader,
            solanaChainReader = context.solanaSupport.chainReader,
            solanaTransferComposer = context.solanaSupport.composer,
            tokenStore = context.tokenStore,
        )

        // Probe — ensures Turnkey has an EVM wallet available before the provider is handed out.
        withContext(Dispatchers.IO) {
            provider.getWalletAddress()
        }

        return provider
    }

    // ---------- Managed authentication (email or SMS one-time code) — internal API, see InternalRainTurnkeyApi ----------

    /**
     * Where managed authentication stands, over time: [TurnkeyAuthState.Loading] until the first
     * auth call has configured Turnkey and any previous session has been restored,
     * [TurnkeyAuthState.Authenticated] once a session is live,
     * [TurnkeyAuthState.Unauthenticated] otherwise. Always `Unauthenticated` in bring-your-own
     * mode, where the host owns authentication.
     */
    @InternalRainTurnkeyApi
    val authState: Flow<TurnkeyAuthState>
        get() = managedAuth?.authState ?: flowOf(TurnkeyAuthState.Unauthenticated)

    /** Snapshot of [authState] right now. */
    @InternalRainTurnkeyApi
    fun currentAuthState(): TurnkeyAuthState =
        managedAuth?.currentAuthState() ?: TurnkeyAuthState.Unauthenticated

    /**
     * True when an unexpired session is already live — restored, or just established — with more
     * than 30 seconds left, so the one-time-code step can be skipped. Reflects the local expiry
     * only: a session revoked server-side (a login on another device) reads as active until its
     * first call fails. False until the first auth call has configured Turnkey, and always false
     * in bring-your-own mode.
     */
    @InternalRainTurnkeyApi
    fun hasActiveSession(): Boolean = managedAuth?.hasActiveSession() ?: false

    /**
     * Waits for the asynchronous session restore that follows configuration, so a returning user's
     * session can be reused without a new code. A timeout returns normally and leaves [authState]
     * at [TurnkeyAuthState.Loading]. As the first auth call of a launch it also runs Turnkey's
     * one-shot initialization first, which [timeoutMs] does not bound. A no-op in bring-your-own
     * mode. Throws `RainError.InvalidConfig` when the ids are blank, conflict with the ones the
     * process was configured with, or Turnkey was configured outside the SDK, and
     * `RainError.InternalError` when Turnkey's initialization failed.
     */
    @InternalRainTurnkeyApi
    suspend fun awaitSessionRestore(
        timeoutMs: Long = TurnkeyManagedAuthController.DEFAULT_RESTORE_TIMEOUT_MS,
    ) {
        managedAuth?.awaitSessionRestore(timeoutMs)
    }

    /**
     * Sends a one-time login code to [contact], by email or by SMS; touches no existing session.
     * Applies the one-shot Turnkey configuration when it is the first auth call. Calling it again
     * for the same contact issues a new code and replaces the pending one, which is how a code is
     * resent: Turnkey codes expire after 5 minutes by default, lock after 3 wrong attempts, and at
     * most 3 can be active per user. Calling it for another contact or channel retires the pending
     * code first, so a failed switch leaves nothing confirmable. A phone number must be in E.164
     * form (`+`, country code and number, digits only); spaces, dots, hyphens and parentheses are
     * removed first, and a parenthesised trunk zero is refused. The contact is canonicalized once
     * and the same string is sent on confirm. Throws `RainError.InvalidConfig` for a blank email or
     * a phone number outside E.164. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun sendLoginCode(contact: LoginContact) = requireManagedAuth().sendLoginCode(contact)

    /** The email channel: `sendLoginCode(LoginContact.Email(email))`. */
    @InternalRainTurnkeyApi
    suspend fun sendLoginCode(email: String) = sendLoginCode(LoginContact.Email(email))

    /**
     * Confirms the code from [sendLoginCode]. A first login signs the user up and creates one
     * wallet holding an Ethereum and a Solana account inside the same request; a returning login
     * backfills a missing account onto the existing wallet. A rejected code throws
     * `RainError.InvalidLoginCode` and keeps the challenge, so the user can retype it; any other
     * failure inside the code check itself also keeps it, while a failure after the code was
     * accepted drops it. If that failure is the switch to the new session, the device is signed
     * out as well — the login already revoked the previous session server-side — without firing
     * [TurnkeyConfig.onSessionExpired]; a provisioning failure keeps the new session, which heals
     * at resolution. A successful login revokes the user's other Turnkey sessions on every
     * device (`invalidateExisting`); the signed-out device's `onSessionExpired` fires at its next
     * call. The channel the code went out on makes no difference here. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun confirmLoginCode(code: String) = requireManagedAuth().confirmLoginCode(code)

    /**
     * Clears the selected session (full logout) — after waiting for a restore in flight to
     * settle — without firing [TurnkeyConfig.onSessionExpired]; cached accounts are still evicted
     * and a pending login code is dropped. [hasActiveSession] and [currentAuthState] read
     * unauthenticated as soon as it returns. A no-op when no session is selected. Managed mode only —
     * throws `RainError.InvalidConfig` in bring-your-own mode, where the host owns the session.
     */
    @InternalRainTurnkeyApi
    suspend fun logout() = requireManagedAuth().logout()

    private fun requireManagedAuth(): TurnkeyManagedAuthController =
        managedAuth ?: throw RainError.InvalidConfig(
            "Authentication methods are only available in managed mode — construct the provider " +
                "with TurnkeyConfig(application, organizationId, authProxyConfigId); in " +
                "bring-your-own mode the host owns authentication"
        )
}
