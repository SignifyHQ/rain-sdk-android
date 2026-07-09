package com.rain.sdk.sample

import android.app.Application
import io.privy.auth.AuthState
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig
import io.privy.logging.PrivyLogLevel

/**
 * Sample-app glue that drives Privy's Android SDK end-to-end (init, email OTP, embedded-wallet
 * provisioning) so the host app can hand a ready `Privy` singleton to
 * `RainSession.initializePrivy(privy = …)`.
 *
 * This file is NOT part of Rain SDK. It is reference code a host app would write itself — Rain SDK
 * intentionally does not own Privy auth (mirrors TurnkeyAuthSample). Copy/adapt for your own app.
 */
object PrivyAuthSample {

    // Privy must be a single instance for the app's lifetime; hold it here after init.
    @Volatile
    private var instance: Privy? = null

    /** Hand this to `RainSession.initializePrivy(privy = …)` once auth is complete. */
    val privy: Privy
        get() = instance ?: error("PrivyAuthSample.init(...) not called yet")

    /**
     * Initializes the Privy singleton (idempotent — reuses the existing instance). Must run on the
     * main thread with the Application context.
     */
    fun init(app: Application, appId: String, appClientId: String) {
        if (instance != null) return
        SampleLog.d(
            "PrivyAuth",
            "init appId=${SampleLog.maskToken(appId)} clientId=${SampleLog.maskToken(appClientId)}"
        )
        instance = Privy.init(
            context = app,
            config = PrivyConfig(
                appId = appId,
                appClientId = appClientId,
                logLevel = PrivyLogLevel.VERBOSE
            )
        )
    }

    /** True when Privy restored an authenticated session from a prior run (skips the OTP round-trip). */
    suspend fun hasActiveSession(): Boolean =
        runCatching { privy.getAuthState() is AuthState.Authenticated }.getOrDefault(false)

    /** Logs the Privy user out. Safe no-op if not authenticated. */
    suspend fun logout() {
        runCatching { instance?.logout() }
            .onFailure { SampleLog.w("PrivyAuth", "logout failed: ${it.message}") }
    }

    /** Sends an email OTP. Throws on failure. */
    suspend fun sendEmailOtp(email: String) {
        SampleLog.d("PrivyAuth", "sendEmailOtp to=${SampleLog.maskEmail(email)}")
        privy.email.sendCode(email).getOrThrow()
    }

    /** Verifies the OTP and creates a Privy session. Throws on failure. */
    suspend fun verifyEmailOtp(code: String, email: String) {
        SampleLog.d("PrivyAuth", "verifyEmailOtp email=${SampleLog.maskEmail(email)}")
        privy.email.loginWithCode(code = code, email = email).getOrThrow()
        SampleLog.d("PrivyAuth", "session active")
    }

    /**
     * Ensures the authenticated user has an embedded Ethereum wallet. Returns true if a new wallet
     * was created, false if one already existed.
     */
    suspend fun ensureEthereumWallet(): Boolean {
        val user = privy.getUser() ?: error("Privy user not authenticated")
        if (user.embeddedEthereumWallets.isNotEmpty()) {
            SampleLog.d("PrivyAuth", "ensureEthereumWallet — wallet already present")
            return false
        }
        val wallet = user.createEthereumWallet(allowAdditional = false).getOrThrow()
        SampleLog.i("PrivyAuth", "created embedded wallet address=${wallet.address}")
        return true
    }
}
