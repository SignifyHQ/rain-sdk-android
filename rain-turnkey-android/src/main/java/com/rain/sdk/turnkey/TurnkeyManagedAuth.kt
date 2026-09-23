package com.rain.sdk.turnkey

import android.app.Activity
import android.app.Application
import com.rain.sdk.internal.error.RainError
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.AuthConfig
import com.turnkey.core.models.AuthState
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
 * the same ids and passkey domain is a no-op; with different ones, or over a foreign
 * configuration, it is an error the provider surfaces on every auth call (the app has to
 * relaunch to change them). The passkey domain is part of the configuration because the vendor
 * reads the relying party for its passkey login and sign-up from there, the cross-platform
 * contract shared by Rain's SDKs; the add-passkey ceremony takes it explicitly.
 *
 * Known limit: the foreign-configuration probe reads a field the vendor assigns asynchronously,
 * so a host `TurnkeyContext.init` still in flight from `Application.onCreate` can slip past it.
 */
internal object TurnkeyManagedConfigurator {
    private val mutex = Mutex()

    @Volatile
    private var configuredWith: Triple<String, String, String?>? = null

    /**
     * Whether this SDK ran the vendor's initialization itself. The vendor assigns its context and
     * marks itself initialized before the work that can fail, so after a failed attempt the
     * foreign-init probe reads true although nobody else configured it; this flag keeps that from
     * being reported as "configured outside the SDK".
     */
    @Volatile
    private var initAttempted = false

    private val defaultInit: suspend (Application, String, String, String?) -> Unit = { app, organizationId, authProxyConfigId, passkeyDomain ->
        // The vendor's own `init` launches `initSuspend` on Dispatchers.Main.immediate. Running it
        // there ourselves keeps its lifecycle-observer registration on the main thread while
        // surfacing a failure to this coroutine instead of an uncaught crash on the vendor's scope.
        // NonCancellable, because the vendor records whatever ends its first init in a process-wide
        // readiness signal and replays it to every later caller: a caller cancelled mid-init must
        // not be what ends it. The cost is that the configure mutex is held across a vendor init
        // that hangs.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            TurnkeyContext.initSuspend(
                app,
                VendorTurnkeyConfig(
                    organizationId = organizationId,
                    authProxyConfigId = authProxyConfigId,
                    // Null when passkeys are off: the code-only configuration the vendor got before.
                    authConfig = passkeyDomain?.let { AuthConfig(rpId = it) },
                ),
            )
        }
    }

    /** `appContext` is a public `lateinit` the vendor assigns only inside `initSuspend`. */
    private val defaultProbe: () -> Boolean = { runCatching { TurnkeyContext.appContext }.isSuccess }

    /** Test seam: replaces the vendor configure call. */
    @Volatile
    internal var initImpl: suspend (Application, String, String, String?) -> Unit = defaultInit

    /** Test seam: whether the vendor singleton was already initialized, by anyone. */
    @Volatile
    internal var vendorInitializedProbe: () -> Boolean = defaultProbe

    /**
     * Configures the vendor once per process. Returns the error to surface on every auth call
     * when the ids are blank, the ids or the passkey domain differ from the ones the process was
     * configured with, the vendor
     * was configured outside the SDK, or its initialization failed; null when this provider may
     * proceed.
     */
    suspend fun configure(
        application: Application,
        organizationId: String,
        authProxyConfigId: String,
        passkeyDomain: String?,
    ): RainError? {
        // Steady state — every auth call re-checks — needs no lock: a stale read only falls through.
        if (configuredWith == Triple(organizationId, authProxyConfigId, passkeyDomain)) return null
        return mutex.withLock { configureLocked(application, organizationId, authProxyConfigId, passkeyDomain) }
    }

    private suspend fun configureLocked(
        application: Application,
        organizationId: String,
        authProxyConfigId: String,
        passkeyDomain: String?,
    ): RainError? {
        val requested = Triple(organizationId, authProxyConfigId, passkeyDomain)
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
                    "managed mode has to own that configuration; remove the app's own wallet-backend " +
                    "initialization, or use the bring-your-own provider instead"
            )
            else -> initializeVendor(application, requested)
        }
    }

    /** Runs the vendor's one-shot initialization and records the ids only once it succeeded. */
    @Suppress("TooGenericExceptionCaught") // the vendor's init failure is untyped; every one becomes InternalError
    private suspend fun initializeVendor(application: Application, requested: Triple<String, String, String?>): RainError? {
        initAttempted = true
        try {
            initImpl(application, requested.first, requested.second, requested.third)
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
 * logout; the passkey flows, sign-in, sign-up and add-passkey, when a relying-party domain is
 * configured; and contact attach, a verified email or phone added to the signed-in account as a
 * login method. Every vendor failure is mapped to a [RainError] before it surfaces; cancellation
 * is never mapped.
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
    /** The passkey relying-party domain, already normalized; null means passkeys are off. */
    private val passkeyDomain: String? = null,
    private val nowEpochSeconds: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {
    private data class PendingOtp(val challenge: OtpChallenge, val contact: String)

    private val flowMutex = Mutex()
    private val pendingLock = ReentrantLock()
    private var pendingOtp: PendingOtp? = null

    /**
     * The verification code for a contact being attached, kept apart from [pendingOtp] so a login
     * cannot consume a verification code and a verification cannot consume a login code.
     */
    private var pendingContactOtp: PendingOtp? = null
    private val closed = AtomicBoolean(false)
    private val closedFlow = MutableStateFlow(false)

    /**
     * [TurnkeySessionCoordinator.sessionStates] as a login screen sees them. Combined with the
     * closed flag so [close] itself emits [TurnkeyAuthState.Unauthenticated]; the session flow alone
     * would stay silent until the session next changed.
     */
    val authState: Flow<TurnkeyAuthState> =
        combine(coordinator.sessionStates, closedFlow) { state, isClosed ->
            if (isClosed) TurnkeyAuthState.Unauthenticated else state.toAuthState()
        }.distinctUntilChanged()

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

    // ---------- passkeys ----------

    /**
     * Signs an existing user in with a passkey bound to the configured domain, through the system
     * passkey sheet presented from [activity]. The account is the one the passkey was created for.
     * A successful login stores the session under a fresh key, selects it, clears the previous
     * session, revokes the user's other sessions on every device and backfills a missing account,
     * the same steps as [confirmLoginCode]. A dismissed sheet, a refused passkey or a failed
     * ceremony leaves the current session untouched; a session the vendor stored under the fresh
     * key without selecting it is cleared. A pending login code survives, because it binds no
     * device key; a pending contact verification does not, because the account it named changed.
     * Throws [RainError.InvalidConfig] before touching the vendor when no domain is configured.
     */
    suspend fun loginWithPasskey(activity: Activity) {
        flowMutex.withLock {
            requirePasskeysConfigured()
            prepare()
            val previousKey = context.selectedSessionKey
            val sessionKey = SESSION_KEY_PREFIX + UUID.randomUUID()
            ceremony(sessionKey) { context.completePasskeyLogin(activity, sessionKey) }
            switchToSession(sessionKey, previousKey)
            ensureAccountsLocked()
        }
    }

    /**
     * Creates a new account whose only login method is a passkey bound to the configured domain,
     * with [MANAGED_WALLET] created inside the same request, then signs it in as [loginWithPasskey]
     * does. Every call mints a fresh account; returning users sign in or add a passkey instead.
     *
     * Refused with [RainError.InvalidConfig] while a live session is selected on this device: the
     * vendor swaps its process-wide client for a temporary-key client for the whole ceremony and
     * restores it only by selecting the new session, which it does only when none is selected, so
     * a wallet call made while the sheet is open would stamp with a key registered on nobody's
     * organization and read the live session as dead. A selected session that is already dead
     * carries no such risk and is cleared first, the way [logout] clears one, with the host's
     * re-auth hook silent; the ceremony then runs and the vendor selects what it creates, and a
     * ceremony that fails leaves the device signed out, as a logout would. Waiting out a restore in
     * flight first keeps a session that is still loading from being read as "nothing selected"; one
     * still loading after the bounded wait is refused like a live one. A failure after the
     * account exists (the login or the session store) leaves an account the passkey can still sign
     * into.
     */
    suspend fun signUpWithPasskey(activity: Activity) {
        flowMutex.withLock {
            requirePasskeysConfigured()
            prepare()
            awaitRestoreSettled(TurnkeySessionCoordinator.AUTH_RESTORE_TIMEOUT_MS)
            clearDeadSessionOrRefuse()
            val sessionKey = SESSION_KEY_PREFIX + UUID.randomUUID()
            val passkeyName = TurnkeyPasskeys.authenticatorName(nowEpochSeconds())
            ceremony(sessionKey) {
                context.completePasskeySignUp(activity, sessionKey, passkeyName, MANAGED_WALLET)
            }
            switchToSession(sessionKey, previousKey = null)
            ensureAccountsLocked()
        }
    }

    /**
     * Registers a passkey bound to the configured domain on the signed-in account, through the
     * system sheet presented from [activity], so the next sign-in can use it. No new account and no
     * session change. The session is checked before the sheet through the coordinator, which waits
     * out a restore in flight and refreshes a session near its expiry, so the user is never asked
     * for a biometric the backend cannot use; without a session this throws
     * [RainError.TokenExpired]. The ceremony runs outside the coordinator, because a retry there
     * would re-prompt the user; the registration runs inside it, retried once after a 401 refresh
     * (the backend answered before executing anything, so no duplicate). A dismissed sheet leaves
     * the account untouched; a registration the backend refused leaves a passkey on the device
     * that signs into nothing. Under [flowMutex], so a logout or login cannot swap the session
     * between the check and the registration; other auth calls wait while the sheet is open.
     */
    suspend fun addPasskey(activity: Activity) {
        flowMutex.withLock {
            val rpId = requirePasskeysConfigured()
            prepare()
            requireLiveSession()
            val name = TurnkeyPasskeys.authenticatorName(nowEpochSeconds())
            val registration = guarded { context.createPasskeyCredential(activity, rpId, name) }
            coordinator.executeWrite { session, _ ->
                context.registerAuthenticator(session.organizationId, session.userId, name, registration)
            }
        }
    }

    // ---------- contact attach ----------

    /**
     * Sends a verification code to [contact], a contact the signed-in user wants to attach to this
     * account as a login method, distinct from [sendLoginCode], which starts a login. Requires a
     * live session, checked before anything is sent. The contact is canonicalized like a login
     * contact and that string is what the account stores. The pending code follows the login send's
     * rule: a second call for the same contact replaces it on success and keeps it on failure, and
     * a call for another contact or channel retires it before the vendor is asked, so a failed
     * switch leaves nothing confirmable. Accounts are never merged: a contact another account
     * already owns goes to the backend, and its answer surfaces on confirm.
     */
    suspend fun sendContactVerificationCode(contact: LoginContact) {
        flowMutex.withLock {
            prepare()
            val (canonical, channel) = canonicalize(contact)
            retirePendingContactOtpUnlessFor(canonical, channel)
            requireLiveSession()
            val challenge = guarded { context.sendOtp(canonical, channel) }
            pendingLock.withJavaLock {
                pendingContactOtp = PendingOtp(challenge, canonical)
            }
        }
    }

    /**
     * Confirms the code from [sendContactVerificationCode] and attaches the contact, verified. The
     * session is checked before the code is spent, so a dead session costs no code. A rejected code
     * throws [RainError.InvalidLoginCode] and keeps the challenge, as [confirmLoginCode] does, and so
     * does any other failure inside the verify step. Once the verify returned a token the code is
     * spent, so the challenge is dropped whether or not the update that follows succeeds; a failed
     * update surfaces as its own error and the user requests a new code.
     */
    suspend fun confirmContactVerification(code: String) {
        flowMutex.withLock {
            prepare()
            val pending = requirePendingContactOtp()
            val trimmed = requireCode(code)
            requireLiveSession()
            val token = guarded(onVendorFailure = ::dropContactOtpUnlessVerifyFailed) {
                context.verifyOtpToken(pending.challenge, trimmed)
            }
            clearPendingContactOtp()
            // The coordinator is its own mapping boundary: it rethrows cancellation and maps the rest.
            coordinator.executeWrite { session, _ ->
                when (pending.challenge.channel) {
                    OtpChannel.EMAIL -> context.setUserEmail(session.organizationId, session.userId, pending.contact, token)
                    OtpChannel.SMS -> context.setUserPhoneNumber(session.organizationId, session.userId, pending.contact, token)
                }
            }
        }
    }

    /** Mirrors [dropChallengeUnlessVerifyFailed] for the verification slot. */
    private fun dropContactOtpUnlessVerifyFailed(e: Exception) {
        if (!TurnkeyErrorMapping.isLoginCodeVerifyFailure(e)) clearPendingContactOtp()
    }

    /** Mirrors [retirePendingOtpUnlessFor] for the verification slot. */
    private fun retirePendingContactOtpUnlessFor(contact: String, channel: OtpChannel) {
        pendingLock.withJavaLock {
            val pending = pendingContactOtp ?: return
            if (pending.contact != contact || pending.challenge.channel != channel) pendingContactOtp = null
        }
    }

    private fun requirePendingContactOtp(): PendingOtp =
        pendingLock.withJavaLock { pendingContactOtp }
            ?: throw RainError.InvalidConfig("No verification code was requested; call sendContactVerificationCode first")

    private fun clearPendingContactOtp() {
        pendingLock.withJavaLock { pendingContactOtp = null }
    }

    /**
     * A live or refreshable session, or [RainError.TokenExpired]: the coordinator's own check, run
     * as an empty read. It waits out a restore in flight and refreshes inside the expiry buffer;
     * [hasActiveSession] alone reads false while the vendor's asynchronous restore is still loading.
     */
    private suspend fun requireLiveSession() {
        coordinator.executeRead { _, _ -> Unit }
    }

    /**
     * The relying-party domain, or [RainError.InvalidConfig] before the vendor is touched. The
     * vendor holds the same value from the one-shot configuration for its login and sign-up; the
     * add-passkey ceremony is handed it explicitly.
     */
    private fun requirePasskeysConfigured(): String =
        passkeyDomain ?: throw RainError.InvalidConfig(TurnkeyPasskeys.NOT_CONFIGURED_MESSAGE)

    /**
     * Makes room for a passkey sign-up. A selected session that is live, or still restoring after
     * the bounded wait, refuses the call, because the vendor's client swap during the ceremony would
     * break wallet calls made against it. A selected session that is dead is cleared the way
     * [logout] clears one, host hook silent, so the ceremony can run and the vendor selects what it
     * creates; the sample offers the sign-up button in exactly that state, and refusing would tell
     * the user to log out of an account the screen says they are not in. The pending contact
     * verification goes with the dead session, since it named that account; a pending login code
     * binds no device key and survives, as it does across a login.
     */
    private suspend fun clearDeadSessionOrRefuse() {
        if (context.selectedSessionKey == null) return
        when (coordinator.currentState()) {
            is TurnkeySessionState.Expired, is TurnkeySessionState.Unauthenticated -> {
                clearSelectedSessionSilently()
                clearPendingContactOtp()
            }
            is TurnkeySessionState.Active, is TurnkeySessionState.Loading ->
                throw RainError.InvalidConfig(TurnkeyPasskeys.ALREADY_SIGNED_IN_MESSAGE)
        }
    }

    /**
     * Clears the selected session with the host's re-auth hook held silent for the death this
     * causes. The suppression is released when the clear did not happen, so the host still hears
     * about a later, genuine one.
     */
    private suspend fun clearSelectedSessionSilently() {
        coordinator.suppressNextHostHook()
        var cleared = false
        try {
            guarded { context.clearSelectedSession() }
            cleared = true
        } finally {
            if (!cleared) coordinator.releaseHostHookSuppression()
        }
    }

    /**
     * Runs a passkey ceremony that stores its session under [sessionKey] on success. On a mapped
     * failure or the caller's cancellation, the fresh key is cleared unless the vendor already
     * selected it: the vendor's key cleanup can throw after its session store, and a cancellation
     * can land after it, and either would otherwise leave a never-selected session in the registry
     * for good. A session the vendor selected before the failure is left signed in, as the code
     * login leaves it.
     */
    private suspend fun ceremony(sessionKey: String, block: suspend () -> Unit) {
        try {
            guarded(block = block)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { clearUnselected(sessionKey) }
            throw e
        } catch (e: RainError) {
            clearUnselected(sessionKey)
            throw e
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
     * silent for the death this causes; cached accounts still go stale. A pending login code and a
     * pending contact verification are dropped once the clear was attempted, whether or not it
     * succeeded. Reads made right after
     * this returns already see no session: the vendor flips its auth state, selected key and
     * session inline inside `clearSession`, and [hasActiveSession] and [currentAuthState] derive
     * from those values on demand, so no wait for the flip is needed here.
     */
    suspend fun logout() {
        flowMutex.withLock {
            prepare()
            // Do not clear mid-restore: the vendor completes its readiness signal before the last
            // of its configuration is assigned, and a clear in that window half-applies. The wait is
            // bounded by the coordinator's own restore timeout, so logout never outlasts a wallet call.
            awaitRestoreSettled(TurnkeySessionCoordinator.AUTH_RESTORE_TIMEOUT_MS)
            if (context.selectedSessionKey == null) {
                // Nothing to clear, so nothing to suppress — an armed suppression with no death to
                // consume it would silence the next genuine one.
                clearPendingOtp()
                clearPendingContactOtp()
                return
            }
            try {
                clearSelectedSessionSilently()
            } finally {
                clearPendingOtp()
                clearPendingContactOtp()
            }
        }
    }

    /** Makes this controller inert: every auth call throws, and the state reads unauthenticated. */
    fun close() {
        closed.set(true)
        closedFlow.value = true
    }

    private suspend fun ensureAccountsLocked() {
        prepare()
        // The same contract as every wallet read: waits out a restore in flight, throws
        // TokenExpired when no session can be produced (an unsettled restore never provisions
        // against a stale list), and retries transient failures.
        coordinator.executeRead { _, _ -> context.refreshWallets() }
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
     * stores it, so the switch has to be explicit. Afterwards the coordinator's death count
     * advances and stales the manager's cached addresses, since an Active→Active transition is not
     * a death the watcher notices, and the previous session, revoked server-side by the login, is
     * cleared locally. A switch that
     * fails is abandoned the same way on both exits: see [abandonSwitch].
     */
    private suspend fun switchToSession(sessionKey: String, previousKey: String?) {
        // The account is about to change, and the verification slot names the one being left.
        clearPendingContactOtp()
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
            Timber.w(e, "Rain SDK: could not clear the superseded wallet session")
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
            Timber.w(e, "Rain SDK: could not clear a stale wallet session")
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

    /**
     * The same guard for a call outside authentication that needs the vendor configured and its
     * session restore settled, such as key export. Without it, the first such call of a launch
     * would wait out the coordinator's restore timeout and report `TokenExpired`.
     */
    internal suspend fun ensureConfigured() = prepare()

    private suspend fun requireOpenAndConfigured() {
        if (closed.get()) throw RainError.InvalidConfig(TURNKEY_PROVIDER_CLOSED_MESSAGE)
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
            // The vendor stores a cancelled first initialization in its process-wide readiness signal
            // and replays it to every later caller, so only this coroutine's own cancellation passes.
            currentCoroutineContext().ensureActive()
            throw RainError.InternalError(
                "The wallet backend failed to initialize for this app launch; relaunch the app to retry",
                e
            )
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
