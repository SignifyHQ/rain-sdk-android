package com.rain.sdk.wallet

import android.app.Application
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Rain wallet: a registrable [ProviderDescriptor] under which the SDK owns authentication,
 * wallet provisioning, sessions and key export, so a host integrates one product under Rain's
 * names and configures nothing but behaviour.
 *
 * ```kotlin
 * val wallet = RainProvider(application)
 *
 * wallet.awaitSessionRestore()
 * if (!wallet.hasActiveSession()) {
 *     wallet.sendLoginCode(RainWalletContact.Email("user@example.com"))
 *     wallet.confirmLoginCode(code) // sign-up or login, plus wallet provisioning
 * }
 *
 * val rain = RainSdk.builder()
 *     .rpcEndpoints(mapOf(8453 to "https://…"))
 *     .register(wallet)
 *     .build()
 * val client = rain.provider(ProviderId.RAIN)
 * ```
 *
 * Send a one-time login code to the user's email or phone, confirm it (a first login signs the
 * user up and creates one wallet holding an Ethereum and a Solana account on a single seed; a
 * returning login finds the account), then register the provider and resolve
 * `rain.provider(ProviderId.RAIN)`. Resolving before a session is live fails with
 * `RainError.TokenExpired` (`RAIN_201`).
 *
 * The wallet backend's configuration is one-shot per app launch and the first authentication call
 * applies it. A backend the app configured itself, or another provider in this process configured
 * with a different backend identity, makes every authentication call throw `RainError.InvalidConfig`
 * (`RAIN_102`) until the app relaunches; a failed backend initialization makes them throw
 * `RainError.InternalError` (`RAIN_502`) until then.
 *
 * The Rain wallet shares its process-wide wallet backend with the SDK's bring-your-own adapter for
 * that backend, so `RainSdk.Builder.build()` refuses a registry that holds both; the module README
 * names the adapter. That check is best-effort: a second `RainSdk` instance, or a provider that is
 * never registered, can still collide, which the backend reports as `RainError.InvalidConfig` on
 * the first authentication call.
 */
@Suppress("TooManyFunctions") // the descriptor is also the authentication, session and export surface, by design
class RainProvider internal constructor(
    private val backing: TurnkeyProvider,
) : ProviderDescriptor {

    /**
     * Creates the Rain wallet provider for this app.
     *
     * @param application The host application; the wallet backend needs it for secure storage.
     * @param config Behaviour the host may tune. The defaults are the product.
     */
    constructor(application: Application, config: RainWalletConfig = RainWalletConfig()) : this(
        TurnkeyProvider(config.toBacking(application))
    )

    override val id: ProviderId get() = ProviderId.RAIN

    /**
     * [Capability.EXPORT], [Capability.MULTI_CHAIN] and [Capability.BIOMETRIC_GATE] always, plus
     * [Capability.GAS_SPONSORSHIP] while [RainWalletConfig.sponsorGas] is on. `RainSdk` copies this
     * set onto the resolved client.
     */
    override val capabilities: Set<Capability> get() = backing.capabilities

    /**
     * Materializes the wallet for the registry; core calls it on the first
     * `rain.provider(ProviderId.RAIN)`. It re-checks the account set, so a login whose provisioning
     * failed heals here, and probes the Ethereum account.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) before a session is live.
     */
    override suspend fun create(context: ProviderContext): WalletProvider = backing.create(context)

    /**
     * Stops the passive session watcher and makes this provider inert. Call it when discarding the
     * provider (for example when rebuilding the SDK for a new login), so a stale provider can never
     * fire its expiry hook again. Afterwards every authentication and export call throws
     * `RainError.InvalidConfig` (`RAIN_102`), [currentAuthState] reads
     * [RainWalletAuthState.Unauthenticated] and [hasActiveSession] is false. [sessionState] and
     * [currentSessionState] keep reporting the process-wide backend session, so collect
     * [sessionState] in a scope the host cancels when it discards the provider.
     */
    override fun close() {
        backing.close()
    }

    // ---------- Session ----------

    /**
     * The wallet session over time. Emits on every authentication or session change and when an
     * active session passes its expiry, so a host can react to a session dying silently without
     * waiting for a wallet call to fail. The same instance on every read, so a Compose collector
     * keyed on it stays subscribed across recompositions.
     */
    val sessionState: Flow<RainWalletSessionState> by lazy {
        backing.sessionState.map { it.toRainWallet() }
    }

    /** Snapshot of [sessionState] right now. */
    fun currentSessionState(): RainWalletSessionState = backing.currentSessionState().toRainWallet()

    /**
     * Forces a session refresh (extended expiry) regardless of remaining lifetime.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) when the session cannot be refreshed; re-authenticate.
     */
    suspend fun refreshSession() {
        backing.refreshSession()
    }

    // ---------- Authentication (one-time code by email or SMS) ----------

    /**
     * Where authentication stands, over time: [RainWalletAuthState.Loading] until the first
     * authentication call has configured the wallet backend and any previous session has been
     * restored, [RainWalletAuthState.Authenticated] once a session is live,
     * [RainWalletAuthState.Unauthenticated] otherwise. The same instance on every read.
     */
    val authState: Flow<RainWalletAuthState> by lazy {
        backing.authState.map { it.toRainWallet() }
    }

    /** Snapshot of [authState] right now. */
    fun currentAuthState(): RainWalletAuthState = backing.currentAuthState().toRainWallet()

    /**
     * True when an unexpired session is already live, restored or just established, with more than
     * 30 seconds left, so the one-time-code step can be skipped. Reflects the local expiry only: a
     * session revoked server-side (a login on another device) reads as active until its first call
     * fails. False until the first authentication call has configured the wallet backend.
     */
    fun hasActiveSession(): Boolean = backing.hasActiveSession()

    /**
     * Waits for the asynchronous restore of a persisted session that follows configuration, so a
     * returning user's session is reused without a new code. A timeout returns normally and leaves
     * [authState] at [RainWalletAuthState.Loading]. As the first authentication call of a launch
     * it also runs the wallet backend's one-shot initialization first, which [timeoutMs] does not
     * bound.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) when the backend was configured outside the SDK
     *   or with a different identity during this launch.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed.
     */
    suspend fun awaitSessionRestore(timeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS) {
        backing.awaitSessionRestore(timeoutMs)
    }

    /**
     * Sends a one-time login code to [contact], by email or by SMS; touches no existing session.
     * Calling it again for the same contact issues a new code and replaces the pending one, which
     * is how a code is resent: codes expire after 5 minutes, lock after 3 wrong attempts, and at
     * most 3 can be active per user. Calling it for another contact or channel retires the pending
     * code first, so a failed switch leaves nothing confirmable. The contact is canonicalized once
     * (see [RainWalletContact]) and the same string is sent on confirm.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) for a blank email or a phone number outside
     *   E.164, and on a wallet backend configuration conflict for this launch.
     */
    suspend fun sendLoginCode(contact: RainWalletContact) {
        backing.sendLoginCode(contact.toBacking())
    }

    /**
     * The email channel: `sendLoginCode(RainWalletContact.Email(email))`. Pass a phone number
     * through [RainWalletContact.Sms] instead; this overload sends any string as an email address.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) for a blank email, and on a wallet backend
     *   configuration conflict for this launch.
     */
    suspend fun sendLoginCode(email: String) {
        sendLoginCode(RainWalletContact.Email(email))
    }

    /**
     * Confirms the code from [sendLoginCode]. A first login signs the user up and creates one wallet
     * holding an Ethereum and a Solana account inside the same request; a returning login backfills
     * a missing account onto the existing wallet. A failure after the code was accepted drops the
     * challenge; if that failure is the switch to the new session, the device is signed out as well,
     * without firing [RainWalletConfig.onSessionExpired]; a provisioning failure keeps the new
     * session, which heals at resolution. A successful login revokes the user's other sessions on
     * every device, and the signed-out device's expiry hook fires at its next call.
     *
     * @throws RainError.InvalidLoginCode (`RAIN_203`) when the code is rejected; the challenge is
     *   kept, so the user can retype it.
     * @throws RainError.ProviderError (`RAIN_501`) when the backend wraps a rejection in an HTTP 500;
     *   the challenge is kept here too, so treat it like `RAIN_203`.
     * @throws RainError.InvalidConfig (`RAIN_102`) when no code was requested or the code is blank.
     */
    suspend fun confirmLoginCode(code: String) {
        backing.confirmLoginCode(code)
    }

    /**
     * Clears the stored session (full logout), after waiting for a restore in flight to settle,
     * without firing [RainWalletConfig.onSessionExpired]; a pending login code is dropped.
     * [hasActiveSession] and [currentAuthState] read unauthenticated as soon as it returns. A no-op
     * when no session is selected.
     */
    suspend fun logout() {
        backing.logout()
    }

    // ---------- Key export ----------

    /**
     * The wallet's BIP-39 recovery phrase, 12 words, decrypted on this device and returned once.
     * The SDK never logs, caches or persists it. The wallet keeps its Ethereum account at
     * `m/44'/60'/0'/0/0` and its Solana account at `m/44'/501'/0'/0'` on this one seed, a
     * cross-platform contract shared by Rain's SDKs, so this one phrase restores both in any wallet
     * that derives those paths. Export is the backup path for a user who signs in with a passkey
     * only.
     *
     * Everything after the return is the host's duty. Gate the call, for example behind biometrics.
     * Show the phrase where screenshots and screen recording are blocked. Keep it off the
     * clipboard, or clear it. A transient failure is retried with a new request.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) without a live session.
     * @throws RainError.Unauthorized (`RAIN_202`) when the wallet backend refuses the export.
     * @throws RainError.WalletUnavailable (`RAIN_404`) when the account has no wallet yet.
     * @throws RainError.InvalidConfig (`RAIN_102`) when [RainWalletConfig.walletAddress] names no
     *   Ethereum account of the wallet, or when this provider was closed.
     * @throws RainError.ProviderError (`RAIN_501`) when the export is still pending after about four
     *   seconds or its bundle is rejected on this device.
     */
    suspend fun exportRecoveryPhrase(): String = backing.exportRecoveryPhrase()

    /**
     * The private key of the wallet's [account], decrypted on this device and returned once. For
     * [RainWalletKeyAccount.ETHEREUM] it is the 32-byte secp256k1 key as `0x` plus 64 lowercase hex
     * characters, the form Ethereum wallets import. For [RainWalletKeyAccount.SOLANA] it is the
     * 64-byte keypair, the seed followed by the public key, in plain Base58, the string Solana
     * wallets import. Before returning anything the SDK checks that the key is 32 bytes and that
     * the address it derives is the account's. Both keys derive from the seed behind
     * [exportRecoveryPhrase]. The formats are a cross-platform contract shared by Rain's SDKs.
     *
     * Everything after the return is the host's duty, as for [exportRecoveryPhrase]. A transient
     * failure is retried with a new request.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) without a live session.
     * @throws RainError.Unauthorized (`RAIN_202`) when the wallet backend refuses the export.
     * @throws RainError.WalletUnavailable (`RAIN_404`) when the wallet has no account of that
     *   family, which a new login provisions, or when that account sits on another curve than the
     *   family's.
     * @throws RainError.InvalidConfig (`RAIN_102`) when [RainWalletConfig.walletAddress] names no
     *   Ethereum account of the wallet, for either account, or when this provider was closed.
     * @throws RainError.ProviderError (`RAIN_501`) when the export is still pending after about four
     *   seconds, its bundle is rejected on this device, or the bundle names another account than the
     *   one requested.
     * @throws RainError.InternalError (`RAIN_502`) when the exported material fails a check, in
     *   which case nothing is returned.
     */
    suspend fun exportPrivateKey(account: RainWalletKeyAccount): String =
        backing.exportPrivateKey(account.toBacking())

    companion object {
        /** How long [awaitSessionRestore] waits by default, in milliseconds; inlined into callers. */
        const val DEFAULT_RESTORE_TIMEOUT_MS: Long = 5_000L
    }
}
