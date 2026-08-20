package com.rain.sdk.internal.network.rainapi

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RainSessionStoreTest {

    private val credentials = RainApiCredentials(apiKey = "key", userId = "user")
    private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun store(
        mints: AtomicInteger,
        expiresAt: (mintNumber: Int) -> Instant?,
    ) = RainSessionStore(now = { now }) {
        val n = mints.incrementAndGet()
        RainSession(token = "cst_$n", expiresAt = expiresAt(n))
    }

    @Test
    fun `reuses cached token while valid`() = runBlocking {
        val mints = AtomicInteger()
        val store = store(mints) { now.plusSeconds(3600) }

        val first = store.validToken(credentials)
        val second = store.validToken(credentials)

        assertThat(first).isEqualTo("cst_1")
        assertThat(second).isEqualTo("cst_1")
        assertThat(mints.get()).isEqualTo(1)
    }

    @Test
    fun `re-mints inside the 60s expiry buffer`() = runBlocking {
        val mints = AtomicInteger()
        val store = store(mints) { now.plusSeconds(3600) }

        store.validToken(credentials)
        now = now.plusSeconds(3600 - 30) // 30s left < 60s buffer
        val refreshed = store.validToken(credentials)

        assertThat(refreshed).isEqualTo("cst_2")
        assertThat(mints.get()).isEqualTo(2)
    }

    @Test
    fun `re-mints when credentials change`() = runBlocking {
        val mints = AtomicInteger()
        val store = store(mints) { now.plusSeconds(3600) }

        store.validToken(credentials)
        val other = store.validToken(RainApiCredentials(apiKey = "key2", userId = "user"))

        assertThat(other).isEqualTo("cst_2")
        assertThat(mints.get()).isEqualTo(2)
    }

    @Test
    fun `unparseable expiry falls back to five minute TTL`() = runBlocking {
        val mints = AtomicInteger()
        val store = store(mints) { null }

        store.validToken(credentials)
        now = now.plusSeconds(120) // well inside fallback TTL minus buffer
        store.validToken(credentials)
        assertThat(mints.get()).isEqualTo(1)

        now = now.plusSeconds(150) // 270s elapsed; 300 - 270 = 30s left < 60s buffer
        store.validToken(credentials)
        assertThat(mints.get()).isEqualTo(2)
    }

    @Test
    fun `invalidate drops the cached token`() = runBlocking {
        val mints = AtomicInteger()
        val store = store(mints) { now.plusSeconds(3600) }

        store.validToken(credentials)
        store.invalidate()
        store.validToken(credentials)

        assertThat(mints.get()).isEqualTo(2)
    }

    @Test
    fun `concurrent callers share a single mint`() = runBlocking {
        val mints = AtomicInteger()
        val store = store(mints) { now.plusSeconds(3600) }

        val tokens = coroutineScope {
            (1..20).map { async { store.validToken(credentials) } }.awaitAll()
        }

        assertThat(tokens.toSet()).containsExactly("cst_1")
        assertThat(mints.get()).isEqualTo(1)
    }
}
