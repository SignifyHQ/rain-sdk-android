package com.rain.sdk.internal.network.rainapi

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A minted client session token plus its parsed expiry (null when unparseable). */
internal data class RainSession(val token: String, val expiresAt: Instant?)

/**
 * Mutex-guarded cache of the Rain client session token (CST).
 *
 * A cached CST is reused while it was minted for the current credential pair and is more
 * than [REFRESH_BUFFER_SECONDS] from expiry; otherwise [mint] is invoked (serialized, so
 * concurrent callers share one mint). A session without a parseable expiry is kept for
 * [FALLBACK_TTL_SECONDS] from mint time.
 */
internal class RainSessionStore(
    private val now: () -> Instant = Instant::now,
    private val mint: suspend (RainApiCredentials) -> RainSession,
) {
    private val mutex = Mutex()
    private var cached: RainSession? = null
    private var cachedFor: RainApiCredentials? = null

    suspend fun validToken(credentials: RainApiCredentials): String = mutex.withLock {
        val session = cached
        if (session != null && cachedFor == credentials && !isNearExpiry(session)) {
            return@withLock session.token
        }

        val minted = mint(credentials)
        cached = minted.expiresAt?.let { minted }
            ?: minted.copy(expiresAt = now().plusSeconds(FALLBACK_TTL_SECONDS))
        cachedFor = credentials
        minted.token
    }

    /** Drops the cached CST so the next [validToken] call re-mints (e.g. after a 401). */
    suspend fun invalidate() = mutex.withLock {
        cached = null
        cachedFor = null
    }

    private fun isNearExpiry(session: RainSession): Boolean {
        val expiry = session.expiresAt ?: return true
        return !now().plusSeconds(REFRESH_BUFFER_SECONDS).isBefore(expiry)
    }

    internal companion object {
        /** Re-mint the CST when it is within this window of expiring. */
        const val REFRESH_BUFFER_SECONDS = 60L

        /** Assumed lifetime when the API returns no parseable `expiresAt`. */
        const val FALLBACK_TTL_SECONDS = 300L
    }
}
