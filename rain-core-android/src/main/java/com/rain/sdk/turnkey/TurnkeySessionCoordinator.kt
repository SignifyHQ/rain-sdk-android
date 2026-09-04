package com.rain.sdk.turnkey

import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.error.RainError
import com.turnkey.core.models.AuthState
import com.turnkey.core.models.Session
import com.turnkey.core.models.errors.TurnkeyKotlinError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards every Turnkey call behind session-expiry checks, proactive refresh, refresh-on-401
 * retry, and transient-failure backoff, per [TurnkeySessionPolicy]. Mirrors the CST handling
 * in `RainSessionStore`/`RainApiService.withCst`, adapted to Turnkey's externally-owned
 * session.
 *
 * Terminal auth failures always surface as [RainError.TokenExpired] and fire the death
 * callbacks plus the host's `onSessionExpired` hook once per session death; both re-arm when a
 * live session is seen again.
 */
internal class TurnkeySessionCoordinator(
    private val turnkey: TurnkeyContextProtocol,
    private val policy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
    private val onSessionExpired: (() -> Unit)? = null,
    private val nowEpochSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val refreshLock = Mutex()
    private val expiryNotified = AtomicBoolean(false)
    private val monitoringStarted = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Whether a session was ever observed — only an existing session can "die". */
    private val sawSession = AtomicBoolean(false)

    /** Runs when an active session dies (before the host hook) — e.g. cached-account eviction. */
    private val deathCallbacks = CopyOnWriteArrayList<() -> Unit>()

    /** Registers a callback invoked once per session death, before the host hook. */
    fun onSessionDeath(callback: () -> Unit) {
        deathCallbacks += callback
    }

    /** Snapshot of the session state as seen right now. */
    fun currentState(): TurnkeySessionState =
        deriveState(turnkey.authState.value, turnkey.session)

    /**
     * The session state over time. Re-derives on every Turnkey auth/session emission, and —
     * because a session passing its expiry emits nothing by itself — re-checks at the active
     * session's expiry instant, looping in case the timer wakes a hair early (rounding, or
     * monotonic-vs-wall-clock drift).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionStates: Flow<TurnkeySessionState> =
        combine(turnkey.authState, turnkey.sessionFlow) { auth, session -> auth to session }
            .transformLatest { (auth, session) ->
                var state = deriveState(auth, session)
                emit(state)
                while (state is TurnkeySessionState.Active && session != null) {
                    val waitMs = ((session.expiry - nowEpochSeconds()) * 1000).toLong() +
                        EXPIRY_RECHECK_SLACK_MS
                    delay(waitMs.coerceAtLeast(EXPIRY_RECHECK_SLACK_MS))
                    val next = deriveState(auth, session)
                    if (next != state) emit(next)
                    state = next
                }
            }
            .distinctUntilChanged()

    /** Idempotent call (balances, history, status reads): transient failures are retried. */
    suspend fun <T> executeRead(block: suspend (Session, TurnkeyClientProtocol) -> T): T =
        execute(idempotent = true, block)

    /** Non-idempotent call (sends, signing): retried only after a session refresh on 401. */
    suspend fun <T> executeWrite(block: suspend (Session, TurnkeyClientProtocol) -> T): T =
        execute(idempotent = false, block)

    /**
     * Forces a session refresh through Turnkey regardless of remaining lifetime. Throws
     * [RainError.TokenExpired] (after firing the expiry hook) when the refresh fails.
     */
    suspend fun refreshNow() {
        if (turnkey.session != null) sawSession.set(true)
        val outcome = refreshLock.withLock { refreshOutcome() }
        if (outcome is RefreshOutcome.Dead) expireAndThrow(outcome.cause)
        expiryNotified.set(false)
    }

    /** Permanently silences this coordinator: no watcher, and the hook can never fire again. */
    fun stop() {
        stopped.set(true)
    }

    /**
     * Starts the passive watcher that fires `onSessionExpired` when an active session dies
     * without any Rain call in flight (Turnkey's expiry timer clearing it, a logout, or the
     * JWT lapsing while the app is backgrounded). Safe to call more than once.
     *
     * Only Active→dead transitions notify: a session that is already dead when monitoring
     * starts (cold launch before login) is not a "death" — it surfaces through
     * [currentState] or the first wallet call instead.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (stopped.get()) {
            Timber.w("Rain SDK: Turnkey session watcher not started — this provider was closed; build a new one")
            return
        }
        if (!monitoringStarted.compareAndSet(false, true)) return
        scope.launch {
            var previous: TurnkeySessionState? = null
            sessionStates.collect { state ->
                when (state) {
                    is TurnkeySessionState.Active -> {
                        sawSession.set(true)
                        expiryNotified.set(false)
                    }
                    is TurnkeySessionState.Expired,
                    is TurnkeySessionState.Unauthenticated ->
                        if (previous is TurnkeySessionState.Active) notifyExpired()
                    // Transitional — keep `previous` so Active → Loading → Unauthenticated
                    // still reads as a death of the active session.
                    is TurnkeySessionState.Loading -> return@collect
                }
                previous = state
            }
        }
    }

    private suspend fun <T> execute(
        idempotent: Boolean,
        block: suspend (Session, TurnkeyClientProtocol) -> T
    ): T {
        var refreshedAfterAuthFailure = false
        var transientRetries = 0
        var backoffMs = policy.initialRetryDelayMs
        while (true) {
            val session = ensureValidSession()
            val client = turnkey.turnkeyClient ?: expireAndThrow()
            try {
                return block(session, client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    isAuthFailure(e) -> {
                        // A 401 means Turnkey rejected the request before executing it, so a
                        // single refresh-and-retry is safe even for sends.
                        if (refreshedAfterAuthFailure || !policy.autoRefresh) expireAndThrow(e)
                        refreshedAfterAuthFailure = true
                        val outcome = refreshLock.withLock {
                            // A concurrent caller may have already rotated the rejected
                            // session; refreshing again would waste a key rotation and could
                            // report a false session death.
                            val current = turnkey.session
                            if (current != null && current.publicKey != session.publicKey) {
                                RefreshOutcome.Fresh(current)
                            } else {
                                refreshOutcome()
                            }
                        }
                        if (outcome is RefreshOutcome.Dead) expireAndThrow(outcome.cause)
                    }
                    idempotent && transientRetries < policy.maxTransientRetries && isTransient(e) -> {
                        transientRetries++
                        Timber.w(
                            e,
                            "Rain SDK: transient Turnkey failure, retry %d/%d",
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

    /**
     * The current session, refreshed first when it is expired or within the refresh buffer.
     * Throws [RainError.TokenExpired] when no usable session can be produced.
     */
    private suspend fun ensureValidSession(): Session {
        // Turnkey restores persisted sessions asynchronously after launch; a call racing that
        // restore must wait it out rather than misreport a valid session as expired.
        if (turnkey.session == null && turnkey.authState.value == AuthState.loading) {
            withTimeoutOrNull(AUTH_RESTORE_TIMEOUT_MS) {
                turnkey.authState.first { it != AuthState.loading }
            }
        }
        val session = turnkey.session ?: expireAndThrow()
        sawSession.set(true)
        val now = nowEpochSeconds()
        if (session.expiry - now > policy.refreshBufferSeconds) {
            expiryNotified.set(false)
            return session
        }
        if (!policy.autoRefresh) {
            if (session.expiry <= now) expireAndThrow()
            // Inside the buffer but not yet expired: without autoRefresh the call proceeds.
            return session
        }
        val outcome = refreshLock.withLock {
            // A concurrent caller may have refreshed while this one waited on the lock.
            val current = turnkey.session ?: return@withLock RefreshOutcome.Dead(null)
            if (current.expiry - nowEpochSeconds() > policy.refreshBufferSeconds) {
                return@withLock RefreshOutcome.Fresh(current)
            }
            refreshOutcome()
        }
        return when (outcome) {
            is RefreshOutcome.Fresh -> outcome.session
            is RefreshOutcome.Dead -> expireAndThrow(outcome.cause)
        }
    }

    /** Outcome of a locked refresh attempt. Only ever built while holding [refreshLock]. */
    private sealed interface RefreshOutcome {
        data class Fresh(val session: Session) : RefreshOutcome
        data class Dead(val cause: Exception?) : RefreshOutcome
    }

    /**
     * Refreshes through Turnkey and reports the outcome instead of throwing, so callers can
     * release [refreshLock] before firing the expiry hook — a host reacting to the hook by
     * calling back into the coordinator must never find the lock still held.
     */
    private suspend fun refreshOutcome(): RefreshOutcome {
        return try {
            turnkey.refreshSession(policy.refreshExpirationSeconds)
            turnkey.session?.let { RefreshOutcome.Fresh(it) } ?: RefreshOutcome.Dead(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Rain SDK: Turnkey session refresh failed")
            RefreshOutcome.Dead(e)
        }
    }

    private fun expireAndThrow(cause: Throwable? = null): Nothing {
        if (cause != null) Timber.w(cause, "Rain SDK: Turnkey session is no longer usable")
        notifyExpired()
        throw RainError.TokenExpired()
    }

    private fun notifyExpired() {
        // A closed coordinator belongs to a discarded provider — it must never notify.
        if (stopped.get()) return
        // Never logged in is not a session death: only notify once a session has been seen.
        if (!sawSession.get()) return
        if (!expiryNotified.compareAndSet(false, true)) return
        // Internal listeners first: they evict state the host hook may immediately re-read.
        deathCallbacks.forEach { callback ->
            runCatching { callback() }
                .onFailure { Timber.w(it, "Rain SDK: session-death callback threw") }
        }
        onSessionExpired?.let { hook ->
            runCatching { hook() }
                .onFailure { Timber.w(it, "Rain SDK: onSessionExpired callback threw") }
        }
    }

    private fun deriveState(auth: AuthState, session: Session?): TurnkeySessionState = when {
        auth == AuthState.loading -> TurnkeySessionState.Loading
        session == null || auth == AuthState.unauthenticated -> TurnkeySessionState.Unauthenticated
        session.expiry <= nowEpochSeconds() -> TurnkeySessionState.Expired
        else -> TurnkeySessionState.Active(session.expiry)
    }

    private fun isAuthFailure(e: Throwable): Boolean = anyInChain(e) { t ->
        t is RainError.TokenExpired ||
            t is TurnkeyKotlinError.InvalidSession ||
            (t is TurnkeyHistoryError && t.statusCode == 401) ||
            ErrorMapper.turnkeyHttpStatus(t) == 401
    }

    private fun isTransient(e: Throwable): Boolean = anyInChain(e) { t ->
        t is IOException ||
            (t is TurnkeyHistoryError && isTransientStatus(t.statusCode)) ||
            ErrorMapper.turnkeyHttpStatus(t)?.let { isTransientStatus(it) } == true
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

        /** Extra wait past the expiry instant so an early timer wake cannot re-derive Active. */
        const val EXPIRY_RECHECK_SLACK_MS = 50L

        /** Longest a call waits for Turnkey's async session restore before judging the session. */
        const val AUTH_RESTORE_TIMEOUT_MS = 10_000L
    }
}
