package com.rain.sdk.privy

import com.rain.sdk.internal.error.RainError
import io.privy.auth.AuthState
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.sdk.Privy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards every Privy call behind auth-state checks and transient-failure backoff, and turns a
 * dying session into a host-visible signal (the `onSessionExpired` hook plus [sessionStates]).
 *
 * Unlike the Turnkey coordinator there is no refresh machinery: the Privy SDK single-flights
 * its own session refresh internally before every wallet/indexer call, so an auth failure that
 * reaches Rain means Privy already tried and the session is truly dead. Terminal auth failures
 * surface as [RainError.TokenExpired] and fire the hook once per session death; the hook
 * re-arms when a live session is seen again.
 */
internal class PrivySessionCoordinator(
    private val privy: Privy,
    private val policy: PrivySessionPolicy = PrivySessionPolicy(),
    private val onSessionExpired: (() -> Unit)? = null,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val expiryNotified = AtomicBoolean(false)
    private val monitoringStarted = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Whether an authenticated session was ever observed — only an existing session can "die". */
    private val sawSession = AtomicBoolean(false)

    /** Runs when an active session dies (before the host hook) — e.g. cached-account eviction. */
    private val deathCallbacks = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Snapshot of the session state as seen right now. Reads Privy's `authState.value`, which
     * can briefly lag the source of truth; the guarded calls re-check via the vendor anyway.
     */
    fun currentState(): PrivySessionState = deriveState(privy.authState.value)

    /** The session state over time, derived from Privy's own auth-state flow. */
    val sessionStates: Flow<PrivySessionState> =
        privy.authState.map { deriveState(it) }.distinctUntilChanged()

    /** Registers a callback invoked once per session death, before the host hook. */
    fun onSessionDeath(callback: () -> Unit) {
        deathCallbacks += callback
    }

    /** Permanently silences this coordinator: no watcher, and the hook can never fire again. */
    fun stop() {
        stopped.set(true)
    }

    /**
     * Starts the passive watcher that fires `onSessionExpired` (and the death callbacks) when
     * an active session dies without any Rain call in flight. Safe to call more than once.
     *
     * Only Active→Unauthenticated transitions notify: a session that is already dead when
     * monitoring starts surfaces through [currentState] or the first wallet call instead.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (stopped.get()) {
            Timber.w("Rain SDK: Privy session watcher not started — this provider was closed; build a new one")
            return
        }
        if (!monitoringStarted.compareAndSet(false, true)) return
        scope.launch {
            var previous: PrivySessionState? = null
            sessionStates.collect { state ->
                when (state) {
                    is PrivySessionState.Active -> {
                        sawSession.set(true)
                        expiryNotified.set(false)
                    }
                    is PrivySessionState.Unauthenticated ->
                        if (previous is PrivySessionState.Active) notifyDeath()
                    // Transitional (startup restore / offline-unverified): keep `previous` so
                    // Active → Loading/Unverified → Unauthenticated still reads as a death.
                    is PrivySessionState.Loading,
                    is PrivySessionState.Unverified -> return@collect
                }
                previous = state
            }
        }
    }

    /** Idempotent call (address reads, history): transient failures are retried with backoff. */
    suspend fun <T> executeRead(block: suspend () -> T): T = execute(idempotent = true, block)

    /** Non-idempotent call (sends, signing): never retried. */
    suspend fun <T> executeWrite(block: suspend () -> T): T = execute(idempotent = false, block)

    /**
     * Forces a session refresh through Privy (`PrivyUser.refresh`). Throws
     * [RainError.TokenExpired] (after firing the expiry hook) when no user exists or the
     * refresh fails.
     */
    suspend fun refreshNow() {
        val user = privy.getUser() ?: expireAndThrow()
        sawSession.set(true)
        user.refresh().exceptionOrNull()?.let { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "Rain SDK: Privy session refresh failed")
            expireAndThrow(e)
        }
        expiryNotified.set(false)
    }

    private suspend fun <T> execute(idempotent: Boolean, block: suspend () -> T): T {
        // Privy restores persisted credentials asynchronously after launch; a call racing that
        // restore must wait it out rather than misreport a valid session as dead.
        awaitAuthReady()
        when (privy.authState.value) {
            // Note: this does not re-arm the hook — Privy's local state can keep reading
            // Authenticated after a server-side death, and each failing call must not re-fire.
            // Re-arming happens on the watcher's transition back to Active (a real re-login).
            is AuthState.Authenticated -> sawSession.set(true)
            is AuthState.Unauthenticated -> expireAndThrow()
            // Unverified proceeds: Privy's own per-call refresh settles whether it is usable.
            else -> Unit
        }
        var transientRetries = 0
        var backoffMs = policy.initialRetryDelayMs
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    // Privy already retried its own internal refresh before this surfaced, so
                    // an auth failure here is terminal — never retried by Rain.
                    isAuthFailure(e) -> expireAndThrow(e)
                    idempotent && transientRetries < policy.maxTransientRetries && isTransient(e) -> {
                        transientRetries++
                        Timber.w(
                            e,
                            "Rain SDK: transient Privy failure, retry %d/%d",
                            transientRetries,
                            policy.maxTransientRetries
                        )
                        retryDelay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(policy.maxRetryDelayMs)
                    }
                    else -> throw e
                }
            }
        }
    }

    private suspend fun awaitAuthReady() {
        if (privy.authState.value !is AuthState.NotReady) return
        withTimeoutOrNull(AUTH_RESTORE_TIMEOUT_MS) {
            privy.authState.first { it !is AuthState.NotReady }
        }
    }

    private fun expireAndThrow(cause: Throwable? = null): Nothing {
        if (cause != null) Timber.w(cause, "Rain SDK: Privy session is no longer usable")
        notifyDeath()
        throw RainError.TokenExpired()
    }

    private fun notifyDeath() {
        // A closed coordinator belongs to a discarded provider — it must never notify.
        if (stopped.get()) return
        // Never logged in is not a session death: only notify once a session has been seen.
        if (!sawSession.get()) return
        if (!expiryNotified.compareAndSet(false, true)) return
        deathCallbacks.forEach { callback ->
            runCatching { callback() }
                .onFailure { Timber.w(it, "Rain SDK: session-death callback threw") }
        }
        onSessionExpired?.let { hook ->
            runCatching { hook() }
                .onFailure { Timber.w(it, "Rain SDK: onSessionExpired callback threw") }
        }
    }

    private fun deriveState(auth: AuthState): PrivySessionState = when (auth) {
        is AuthState.NotReady -> PrivySessionState.Loading
        is AuthState.Authenticated -> PrivySessionState.Active
        is AuthState.AuthenticatedUnverified -> PrivySessionState.Unverified
        is AuthState.Unauthenticated -> PrivySessionState.Unauthenticated
    }

    private fun isAuthFailure(e: Throwable): Boolean = anyInChain(e) {
        it is RainError.TokenExpired || PrivyErrorMapping.mapOrNull(it) is RainError.TokenExpired
    }

    private fun isTransient(e: Throwable): Boolean = anyInChain(e) {
        it is NoNetworkException ||
            it is IOException ||
            it is RainError.NetworkError ||
            (it is PrivyApiException && it.statusCode?.let(::isTransientStatus) == true)
    }

    private fun isTransientStatus(status: Int): Boolean =
        status == 408 || status == 429 || status in 500..599

    private inline fun anyInChain(e: Throwable, predicate: (Throwable) -> Boolean): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (predicate(current)) return true
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return false
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8

        /** Longest a call waits for Privy's async credential restore before judging the session. */
        const val AUTH_RESTORE_TIMEOUT_MS = 10_000L
    }
}
