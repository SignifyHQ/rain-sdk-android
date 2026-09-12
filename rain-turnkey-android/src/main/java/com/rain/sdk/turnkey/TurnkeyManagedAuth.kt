package com.rain.sdk.turnkey

import android.app.Application
import com.rain.sdk.internal.error.RainError
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.AuthState
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.milliseconds
import com.turnkey.core.models.TurnkeyConfig as VendorTurnkeyConfig
import kotlin.concurrent.withLock as withJavaLock

/**
 * Guards the vendor's process-wide configuration.
 *
 * `TurnkeyContext` is a Kotlin `object` whose `initSuspend` silently returns when it has already
 * run, so without this guard a host that changed ids — or a context configured outside the SDK —
 * would keep transacting against the first organization with no signal. Configuring again with
 * the same ids is a no-op; with different ids, or over a foreign configuration, it is an error
 * the provider surfaces on every auth call (the app has to relaunch to change them).
 *
 * Known limit: the foreign-configuration probe reads a field the vendor assigns asynchronously,
 * so a host `TurnkeyContext.init` still in flight from `Application.onCreate` can slip past it.
 */
internal object TurnkeyManagedConfigurator {
    private val mutex = Mutex()

    @Volatile
    private var configuredWith: Pair<String, String>? = null

    /**
     * Whether this SDK ran the vendor's initialization itself. The vendor assigns its context and
     * marks itself initialized before the work that can fail, so after a failed attempt the
     * foreign-init probe reads true although nobody else configured it; this flag keeps that from
     * being reported as "configured outside the SDK".
     */
    @Volatile
    private var initAttempted = false

    private val defaultInit: suspend (Application, String, String) -> Unit = { app, organizationId, authProxyConfigId ->
        // The vendor's own `init` launches `initSuspend` on Dispatchers.Main.immediate. Running it
        // there ourselves keeps its lifecycle-observer registration on the main thread while
        // surfacing a failure to this coroutine instead of an uncaught crash on the vendor's scope.
        withContext(Dispatchers.Main.immediate) {
            TurnkeyContext.initSuspend(
                app,
                VendorTurnkeyConfig(organizationId = organizationId, authProxyConfigId = authProxyConfigId)
            )
        }
    }

    /** `appContext` is a public `lateinit` the vendor assigns only inside `initSuspend`. */
    private val defaultProbe: () -> Boolean = { runCatching { TurnkeyContext.appContext }.isSuccess }

    /** Test seam: replaces the vendor configure call. */
    @Volatile
    internal var initImpl: suspend (Application, String, String) -> Unit = defaultInit

    /** Test seam: whether the vendor singleton was already initialized, by anyone. */
    @Volatile
    internal var vendorInitializedProbe: () -> Boolean = defaultProbe

    /**
     * Configures the vendor once per process. Returns the error to surface on every auth call
     * when the ids are blank, differ from the ones the process was configured with, the vendor
     * was configured outside the SDK, or its initialization failed; null when this provider may
     * proceed.
     */
    suspend fun configure(application: Application, organizationId: String, authProxyConfigId: String): RainError? {
        // Steady state — every auth call re-checks — needs no lock: a stale read only falls through.
        if (configuredWith == (organizationId to authProxyConfigId)) return null
        return mutex.withLock { configureLocked(application, organizationId, authProxyConfigId) }
    }

    private suspend fun configureLocked(application: Application, organizationId: String, authProxyConfigId: String): RainError? {
        val requested = organizationId to authProxyConfigId
        val existing = configuredWith
        return when {
            organizationId.isBlank() || authProxyConfigId.isBlank() ->
                RainError.InvalidConfig("organizationId and authProxyConfigId must not be blank")
            existing != null -> if (existing == requested) {
                null
            } else {
                RainError.InvalidConfig(
                    "The wallet backend is already configured with different ids for this app launch; " +
                        "relaunch the app to change them"
                )
            }
            vendorInitializedProbe() && !initAttempted -> RainError.InvalidConfig(
                "The wallet backend was already configured outside the SDK for this app launch; " +
                    "managed mode has to own that configuration — hand the authenticated TurnkeyContext " +
                    "to the bring-your-own TurnkeyConfig instead"
            )
            else -> initializeVendor(application, requested)
        }
    }

    /** Runs the vendor's one-shot initialization and records the ids only once it succeeded. */
    @Suppress("TooGenericExceptionCaught") // the vendor's init failure is untyped; every one becomes InternalError
    private suspend fun initializeVendor(application: Application, requested: Pair<String, String>): RainError? {
        initAttempted = true
        try {
            initImpl(application, requested.first, requested.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing recorded, so the next call retries. The real vendor treats a second init as a
            // no-op once it has marked itself initialized: that retry then records the ids and the
            // readiness wait surfaces the original failure as the same InternalError.
            return RainError.InternalError(
                "The wallet backend failed to initialize for this app launch; relaunch the app to retry",
                e
            )
        }
        configuredWith = requested
        return null
    }

    internal fun resetForTest() {
        configuredWith = null
        initAttempted = false
        initImpl = defaultInit
        vendorInitializedProbe = defaultProbe
    }
}

/**
 * Owns the one-time-code flow, email or SMS, for a managed-mode [TurnkeyProvider]: send code,
 * confirm code (sign-up or login — the vendor decides), account provisioning, session restore,
 * logout. Every vendor failure is mapped to a [RainError] before it surfaces; cancellation is
 * never mapped.
 *
 * Sessions: each login stores its session under a fresh key and then selects it. The vendor's
 * `createSession` rejects only a duplicate *key*, so no stored session has to be cleared before a
 * login — a rejected code cannot cost the user the session they already have. Once the switch
 * succeeds the previous session, already revoked server-side by the login, is cleared locally.
 *
 * One auth operation runs at a time: send, confirm, logout and provisioning all read-then-write
 * the same process-wide vendor state.
 */
@Suppress("TooManyFunctions") // the whole auth flow lives here so one mutex can serialize every step
internal class TurnkeyManagedAuthController(
    private val context: TurnkeyContextProtocol,
    private val coordinator: TurnkeySessionCoordinator,
    private val configure: suspend () -> RainError?,
    private val nowEpochSeconds: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {
    private data class PendingOtp(val challenge: OtpChallenge, val contact: String)

    private val flowMutex = Mutex()
    private val pendingLock = ReentrantLock()
    private var pendingOtp: PendingOtp? = null
    private val closed = AtomicBoolean(false)

    /** [TurnkeySessionCoordinator.sessionStates] as a login screen sees them. */
    val authState: Flow<TurnkeyAuthState> =
        coordinator.sessionStates
            .map { if (closed.get()) TurnkeyAuthState.Unauthenticated else it.toAuthState() }
            .distinctUntilChanged()

    fun currentAuthState(): TurnkeyAuthState =
        if (closed.get()) TurnkeyAuthState.Unauthenticated else coordinator.currentState().toAuthState()

    /**
     * True when a session is live with more than [SESSION_MIN_REMAINING_SECONDS] left, so the
     * one-time-code step can be skipped. [authState] answers "is there a session"; this answers
     * "can the flow skip the code" — the floor keeps a session from dying during its first call.
     * Reflects the local expiry only: a session revoked server-side (for example by a login on
     * another device) reads as active until its first call fails.
     */
    fun hasActiveSession(): Boolean {
        if (closed.get()) return false
        val state = coordinator.currentState()
        return state is TurnkeySessionState.Active &&
            state.expiresAtEpochSeconds - nowEpochSeconds() > SESSION_MIN_REMAINING_SECONDS
    }

    /**
     * Waits for the vendor to initialize and restore any persisted session, or for [timeoutMs] to
     * elapse — a timeout returns normally and leaves [authState] at [TurnkeyAuthState.Loading].
     * Throws [RainError.InvalidConfig] on a configuration mismatch and [RainError.InternalError]
     * when the vendor's initialization itself failed.
     */
    suspend fun awaitSessionRestore(timeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS) {
        requireOpenAndConfigured()
        withTimeoutOrNull(timeoutMs.milliseconds) {
            ready()
            awaitRestoreSettled(timeoutMs)
        }
    }

    /** The email channel: `sendLoginCode(LoginContact.Email(email))`. */
    suspend fun sendLoginCode(email: String) = sendLoginCode(LoginContact.Email(email))

    /**
     * Sends a one-time code to [contact] on its channel. Touches no session. A second call for the
     * same contact replaces the pending challenge on success and keeps it on failure, so a failed
     * resend leaves a code the user can still type; a call for another contact or channel retires
     * the pending challenge before the vendor is asked, so a failed switch leaves nothing
     * confirmable. The contact becomes the account's identity on sign-up, so it is canonicalized
     * once here and the very same string is sent on confirm: an email is trimmed; a phone number is
     * trimmed, stripped of separators and checked against E.164. A blank or malformed contact
     * throws [RainError.InvalidConfig] before the vendor is called and changes nothing.
     */
    suspend fun sendLoginCode(contact: LoginContact) {
        flowMutex.withLock {
            prepare()
            val (canonical, channel) = canonicalize(contact)
            retirePendingOtpUnlessFor(canonical, channel)
            val challenge = guarded { context.sendOtp(canonical, channel) }
            pendingLock.withJavaLock {
                pendingOtp = PendingOtp(challenge, canonical)
            }
        }
    }

    /**
     * Confirms the code from [sendLoginCode]: signs the user up on first login, stores the session
     * under a fresh key, selects it, clears the previous session, then ensures the Ethereum and
     * Solana accounts exist. A sign-up creates [MANAGED_WALLET] inside the signup request itself, so
     * a new organization never exists without its wallet.
     *
     * A rejected code throws [RainError.InvalidLoginCode] and keeps the challenge, so the user can
     * simply retype it. So does any other failure inside the verify step — the auth proxy has been
     * seen wrapping a rejection in an HTTP 500 whose body the Kotlin SDK discards, which surfaces
     * as [RainError.ProviderError] — so the user can retry or request a new code. A failure once
     * the code was accepted (account lookup, login, session) drops the challenge. A failure while
     * selecting the new session signs the device out: the login already revoked the previous
     * session server-side, so keeping it selected would only fail at the next wallet call, and
     * the host's re-auth hook stays silent because this exception is the signal. A provisioning
     * failure once the new session is live throws with the session kept: [ensureAccounts] runs
     * again at provider resolution, so it heals without a new code.
     */
    suspend fun confirmLoginCode(code: String) {
        flowMutex.withLock {
            prepare()
            val pending = requirePendingOtp()
            val trimmed = requireCode(code)
            val previousKey = context.selectedSessionKey
            val sessionKey = SESSION_KEY_PREFIX + UUID.randomUUID()
            guarded(onVendorFailure = ::dropChallengeUnlessVerifyFailed) {
                context.completeOtp(
                    challenge = pending.challenge,
                    otpCode = trimmed,
                    contact = pending.contact,
                    sessionKey = sessionKey,
                    signupWallet = MANAGED_WALLET,
                )
            }
            clearPendingOtp()
            switchToSession(sessionKey, previousKey)
            ensureAccountsLocked()
        }
    }

    /**
     * Decides, on the raw vendor failure of a confirm, whether the one-time code is still worth
     * retrying. The code is spent only once the verify step returned a token, so a failure inside
     * that step (the proxy refusing the code in a shape the mapper cannot classify, or the call
     * never completing) keeps the challenge, while a failure in the account lookup, login or
     * session step that follows drops it. The one exception — a verify response without a token —
     * is harmless: the retry surfaces [RainError.InvalidLoginCode] and the user requests a new code.
     */
    private fun dropChallengeUnlessVerifyFailed(e: Exception) {
        if (!TurnkeyErrorMapping.isLoginCodeVerifyFailure(e)) clearPendingOtp()
    }

    /** Drops a pending challenge issued for another contact or channel; a same-contact resend keeps it. */
    private fun retirePendingOtpUnlessFor(contact: String, channel: OtpChannel) {
        pendingLock.withJavaLock {
            val pending = pendingOtp ?: return
            if (pending.contact != contact || pending.challenge.channel != channel) pendingOtp = null
        }
    }

    private fun requirePendingOtp(): PendingOtp =
        pendingLock.withJavaLock { pendingOtp }
            ?: throw RainError.InvalidConfig("No login code was requested; call sendLoginCode first")

    private fun requireCode(code: String): String =
        code.trim().ifEmpty { throw RainError.InvalidConfig("code must not be blank") }

    /** The identity string the vendor keys the account on, and the channel the code travels on. */
    private fun canonicalize(contact: LoginContact): Pair<String, OtpChannel> = when (contact) {
        is LoginContact.Email -> {
            val email = contact.value.trim().ifEmpty { throw RainError.InvalidConfig("email must not be blank") }
            email to OtpChannel.EMAIL
        }
        is LoginContact.Sms -> requirePhoneNumber(contact.value) to OtpChannel.SMS
    }

    /**
     * Removes the separators people type (spaces, dots, hyphens, parentheses) and requires the
     * E.164 shape. No country inference: a national number without `+` is refused, and so is a
     * parenthesised trunk zero (`+44 (0) 20 ...`), which stripping would fold into a different,
     * well-formed number. The fixed message never echoes the input.
     */
    private fun requirePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw RainError.InvalidConfig("phoneNumber must not be blank")
        val digits = trimmed.replace(PHONE_SEPARATORS, "")
        if (TRUNK_PREFIX.containsMatchIn(trimmed) || !E164.matches(digits)) {
            throw RainError.InvalidConfig(
                "phoneNumber must be in E.164 format: '+' then country code and number, digits only, " +
                    "for example +13214567890"
            )
        }
        return digits
    }

    /**
     * Ensures the authenticated organization holds an Ethereum (secp256k1) and a Solana (ed25519)
     * account. Idempotent. A sign-up already gets both inside the signup request ([MANAGED_WALLET]);
     * this backfills organizations that predate that or were created outside the flow. Missing
     * accounts are added to the wallet Rain resolves; only an organization with no wallet at all
     * gets a new one — so the user has a single mnemonic to back up either way. Also run at
     * provider resolution, so a session whose provisioning failed after login heals itself without
     * a new code.
     */
    suspend fun ensureAccounts() {
        flowMutex.withLock { ensureAccountsLocked() }
    }

    /**
     * Clears the selected session. Deliberate, so the host's `onSessionExpired` re-auth hook stays
     * silent for the death this causes; cached accounts are still evicted. A pending code is
     * dropped once the clear was attempted, whether or not it succeeded. Reads made right after
     * this returns already see no session: the vendor flips its auth state, selected key and
     * session inline inside `clearSession`, and [hasActiveSession] and [currentAuthState] derive
     * from those values on demand, so no wait for the flip is needed here.
     */
    suspend fun logout() {
        flowMutex.withLock {
            prepare()
            // Do not clear mid-restore: the vendor completes its readiness signal before the last
            // of its configuration is assigned, and a clear in that window half-applies.
            awaitRestoreSettled(RESTORE_SETTLE_TIMEOUT_MS)
            if (context.selectedSessionKey == null) {
                // Nothing to clear, so nothing to suppress — an armed suppression with no death to
                // consume it would silence the next genuine one.
                clearPendingOtp()
                return
            }
            coordinator.suppressNextHostHook()
            var cleared = false
            try {
                guarded { context.clearSelectedSession() }
                cleared = true
            } finally {
                // Not cleared: the death did not happen (or may not), so the host must still hear
                // about a later one.
                if (!cleared) coordinator.releaseHostHookSuppression()
                clearPendingOtp()
            }
        }
    }

    /** Makes this controller inert: every auth call throws, and the state reads unauthenticated. */
    fun close() {
        closed.set(true)
    }

    private suspend fun ensureAccountsLocked() {
        prepare()
        // The same contract as every wallet read: waits out a restore in flight, throws
        // TokenExpired when no session can be produced (an unsettled restore never provisions
        // against a stale list), and retries transient failures.
        guarded { coordinator.executeRead { _, _ -> context.refreshWallets() } }
        val wallets = context.wallets
        val formats = wallets.flatMap { it.accounts }.map { it.addressFormat }.toSet()
        val missing = buildList {
            if (V1AddressFormat.ADDRESS_FORMAT_ETHEREUM !in formats) add(ETHEREUM_ACCOUNT)
            if (V1AddressFormat.ADDRESS_FORMAT_SOLANA !in formats) add(SOLANA_ACCOUNT)
        }
        if (missing.isEmpty()) return
        guarded {
            val target = wallets.firstOrNull { wallet ->
                wallet.accounts.any { it.addressFormat == V1AddressFormat.ADDRESS_FORMAT_ETHEREUM }
            } ?: wallets.firstOrNull()
            if (target == null) {
                context.createWallet(MANAGED_WALLET_NAME, missing)
            } else {
                context.createWalletAccounts(target.id, missing)
            }
            // Deterministic for the caller's immediate read; the vendor's own auto-refresh is a
            // configuration default this module does not own.
            coordinator.executeRead { _, _ -> context.refreshWallets() }
        }
    }

    /**
     * On a first login the vendor selects the new session itself; over a live session it only
     * stores it, so the switch has to be explicit. Afterwards the wallet provider's cached
     * addresses are evicted — an Active→Active transition is not a death the watcher notices — and
     * the previous session, revoked server-side by the login, is cleared locally. A switch that
     * fails is abandoned the same way on both exits: see [abandonSwitch].
     */
    private suspend fun switchToSession(sessionKey: String, previousKey: String?) {
        if (context.selectedSessionKey != sessionKey) {
            try {
                guarded { context.selectSession(sessionKey) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { abandonSwitch(sessionKey, previousKey) }
                throw e
            } catch (e: RainError) {
                abandonSwitch(sessionKey, previousKey)
                throw e
            }
        }
        coordinator.notifySessionReplaced()
        if (previousKey != null) clearUnselected(previousKey)
    }

    /**
     * Cleans up after a switch that failed. The fresh key is cleared only when the selection
     * really did not take effect — the vendor persists it before its auto-refresh can fail. When
     * the previous session is still the selected one, the device is signed out: the login that
     * just succeeded revoked that session server-side (`invalidateExisting`), so leaving it
     * selected would only move the failure to the next wallet call, where it would read as an
     * unexplained expiry. The caller rethrows, and that exception is the host's signal, so the
     * re-auth hook stays silent as it does after [logout].
     */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // best-effort cleanup: a clear that fails is logged, the select failure is what surfaces
    private suspend fun abandonSwitch(sessionKey: String, previousKey: String?) {
        clearUnselected(sessionKey)
        if (previousKey == null || context.selectedSessionKey != previousKey) return
        coordinator.suppressNextHostHook()
        try {
            context.clearSelectedSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The death did not happen, so the host must still hear about a later one.
            coordinator.releaseHostHookSuppression()
            Timber.w(e, "Rain SDK: could not clear the superseded Turnkey session")
        }
    }

    /** Best-effort removal of a stored session that is not the selected one. */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // best-effort cleanup: a stale key that will not clear is logged, not surfaced
    private suspend fun clearUnselected(sessionKey: String) {
        if (context.selectedSessionKey == sessionKey) return
        try {
            context.clearSession(sessionKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Rain SDK: could not clear a stale Turnkey session")
        }
    }

    /** Waits, bounded, for the vendor's asynchronous session restore to leave `loading`. */
    private suspend fun awaitRestoreSettled(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs.milliseconds) {
            guarded { context.authState.first { it != AuthState.loading } }
        }
    }

    private fun clearPendingOtp() {
        pendingLock.withJavaLock { pendingOtp = null }
    }

    /** The entry guard of every mutating call: open, configured for this process, vendor ready. */
    private suspend fun prepare() {
        requireOpenAndConfigured()
        ready()
    }

    private suspend fun requireOpenAndConfigured() {
        if (closed.get()) throw RainError.InvalidConfig("This Turnkey provider was closed; build a new one")
        configure()?.let { throw it }
    }

    /**
     * Waits, bounded, for the vendor's one-shot initialization. A failed or never-finishing init is
     * permanent for the process, so both surface as [RainError.InternalError] naming a relaunch.
     */
    @Suppress(
        "TooGenericExceptionCaught",
        "ThrowsCount"
    ) // untyped vendor failure; cancellation, failure and timeout are distinct exits
    private suspend fun ready() {
        val ready = try {
            withTimeoutOrNull(READY_TIMEOUT_MS.milliseconds) {
                context.awaitReady()
                true
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw RainError.InternalError(
                "The wallet backend failed to initialize for this app launch; relaunch the app to retry",
                e
            )
        }
        if (!ready) {
            throw RainError.InternalError(
                "The wallet backend did not finish initializing for this app launch; relaunch the app to retry"
            )
        }
    }

    /**
     * Runs a vendor call under the error contract: cancellation propagates untouched, a
     * [RainError] passes through, anything else is mapped. `ensureActive()` — not a cause-chain
     * walk — decides whether a caught exception is really this coroutine's cancellation: the
     * vendor wraps cancellation in its own error types, but it also re-emits a sibling's failure as
     * a wrapped `JobCancellationException` while this caller is still active. [onVendorFailure]
     * sees the raw vendor exception before it is mapped, for decisions the mapped error cannot carry.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // the mapping boundary: two pass-throughs and one mapped exit
    private suspend fun <T> guarded(onVendorFailure: ((Exception) -> Unit)? = null, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            onVendorFailure?.invoke(e)
            throw TurnkeyErrorMapping.mapAuthError(e)
        }
    }

    internal companion object {
        private const val MILLIS_PER_SECOND = 1000.0

        /** Host-facing default for [awaitSessionRestore]. */
        const val DEFAULT_RESTORE_TIMEOUT_MS = 5_000L

        /** Longest a call waits for the vendor's readiness signal before giving up on this launch. */
        const val READY_TIMEOUT_MS = 15_000L

        /** Longest [logout] waits for a restore in flight before clearing; the coordinator's own bound. */
        const val RESTORE_SETTLE_TIMEOUT_MS = 10_000L

        const val SESSION_MIN_REMAINING_SECONDS = 30.0
        const val SESSION_KEY_PREFIX = "rain-turnkey-"
        const val MANAGED_WALLET_NAME = "Wallet"

        /** Separators people type into a phone number; removed before the E.164 check. */
        private val PHONE_SEPARATORS = Regex("""[\s().-]""")

        /** E.164: `+`, a country code that never starts with 0, at most 15 digits in total. */
        private val E164 = Regex("""^\+[1-9]\d{1,14}$""")

        /** A national trunk zero in parentheses; stripping it would yield a wrong but valid-looking number. */
        private val TRUNK_PREFIX = Regex("""\(\s*0\s*\)""")

        val ETHEREUM_ACCOUNT = TurnkeyAccountSpec(
            addressFormat = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
            curve = V1Curve.CURVE_SECP256K1,
            path = "m/44'/60'/0'/0/0",
        )
        val SOLANA_ACCOUNT = TurnkeyAccountSpec(
            addressFormat = V1AddressFormat.ADDRESS_FORMAT_SOLANA,
            curve = V1Curve.CURVE_ED25519,
            path = "m/44'/501'/0'/0'",
        )

        /**
         * The wallet every managed sign-up creates, inside the signup request: one seed carrying both
         * accounts. Name, mnemonic length (Turnkey's default of 12) and derivation paths are a
         * cross-platform contract shared by Rain's SDKs — change it everywhere or nowhere.
         */
        val MANAGED_WALLET = TurnkeyWalletSpec(MANAGED_WALLET_NAME, listOf(ETHEREUM_ACCOUNT, SOLANA_ACCOUNT))
    }
}
