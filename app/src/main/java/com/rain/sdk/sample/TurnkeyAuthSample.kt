package com.rain.sdk.sample

import android.app.Application
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.InitOtpResult
import com.turnkey.core.models.OtpType
import com.turnkey.core.models.TurnkeyConfig
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1WalletAccountParams

/**
 * Sample-app glue that drives Turnkey's Kotlin SDK end-to-end (init, email OTP,
 * Ethereum wallet provisioning) so the host app can hand a ready `TurnkeyContext`
 * to `RainClient.initializeTurnkey(...)`.
 *
 * This file is NOT part of Rain SDK. It is reference code a host app would write
 * itself — Rain SDK intentionally does not own Turnkey auth (see docs/TURNKEY_SUPPORT.md).
 * Copy/adapt this for your own integration.
 */
object TurnkeyAuthSample {

    /** Hand this to `RainClient.initializeTurnkey(turnkey = …)` once auth is complete. */
    val context: TurnkeyContext get() = TurnkeyContext

    /** Sub-organization ID minted (or reused) for the authenticated user. Null before login. */
    val subOrganizationId: String?
        get() = TurnkeyContext.session.value?.organizationId

    /** Minimum session lifetime (seconds) we still consider worth resuming. */
    private const val SESSION_MIN_REMAINING_SECONDS = 30.0

    /**
     * True when an authenticated, unexpired Turnkey session is already loaded. The Turnkey SDK
     * restores a previously-selected session from secure storage during [init], so after init
     * this reports whether we can skip the OTP step entirely and go straight to initializing Rain.
     */
    fun hasActiveSession(): Boolean {
        val session = TurnkeyContext.session.value ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000.0
        return session.expiry > nowSeconds + SESSION_MIN_REMAINING_SECONDS
    }

    /** Clears all stored Turnkey sessions (full logout). Safe no-op if none exist. */
    suspend fun logout() {
        runCatching { TurnkeyContext.clearAllSessions() }
            .onFailure { SampleLog.w("TurnkeyAuth", "logout (clearAllSessions) failed: ${it.message}") }
    }

    /**
     * Initializes the Turnkey singleton. Idempotent — Turnkey's `initSuspend` no-ops on
     * subsequent calls within the same process, so changing org/proxy IDs after the first
     * call requires restarting the app.
     */
    suspend fun init(
        app: Application,
        organizationId: String,
        authProxyConfigId: String
    ) {
        SampleLog.d(
            "TurnkeyAuth",
            "init org=${SampleLog.maskToken(organizationId)} proxy=${SampleLog.maskToken(authProxyConfigId)}"
        )
        TurnkeyContext.initSuspend(
            app = app,
            cfg = TurnkeyConfig(
                organizationId = organizationId,
                authProxyConfigId = authProxyConfigId
            )
        )
        TurnkeyContext.awaitReady()
        SampleLog.d("TurnkeyAuth", "TurnkeyContext ready")
    }

    /**
     * Starts the email-OTP flow. Returns the [InitOtpResult] — both the `otpId` and the
     * `otpEncryptionTargetBundle` — which [verifyEmailOtp] needs (Turnkey SDK 2.0 encrypts the
     * OTP verification to a target key).
     */
    suspend fun sendEmailOtp(email: String): InitOtpResult {
        SampleLog.d("TurnkeyAuth", "sendEmailOtp to=${SampleLog.maskEmail(email)}")
        val result = TurnkeyContext.initOtp(
            otpType = OtpType.OTP_TYPE_EMAIL,
            contact = email
        )
        SampleLog.d("TurnkeyAuth", "OTP sent otpId=${SampleLog.maskToken(result.otpId)}")
        return result
    }

    /**
     * Verifies the OTP code and creates a Turnkey session. Handles both first-time signup
     * and returning login transparently via `loginOrSignUpWithOtp`. [otpEncryptionTargetBundle]
     * comes from the [sendEmailOtp] result.
     */
    suspend fun verifyEmailOtp(
        otpId: String,
        otpCode: String,
        otpEncryptionTargetBundle: String,
        email: String
    ) {
        SampleLog.d("TurnkeyAuth", "verifyEmailOtp otpId=${SampleLog.maskToken(otpId)}")
        // A prior successful login leaves a persisted session under `com.turnkey.sdk.session`;
        // Turnkey's createSession throws KeyAlreadyExists rather than overwriting it. Clear any
        // stored sessions first so re-verifying OTP (e.g. across demo runs) always succeeds.
        runCatching { TurnkeyContext.clearAllSessions() }
            .onFailure { SampleLog.w("TurnkeyAuth", "clearAllSessions failed (continuing): ${it.message}") }
        TurnkeyContext.loginOrSignUpWithOtp(
            otpId = otpId,
            otpCode = otpCode,
            otpEncryptionTargetBundle = otpEncryptionTargetBundle,
            contact = email,
            otpType = OtpType.OTP_TYPE_EMAIL
        )
        SampleLog.d(
            "TurnkeyAuth",
            "session active subOrgId=${SampleLog.maskToken(subOrganizationId)}"
        )
    }

    /**
     * Ensures the authenticated sub-org has at least one Ethereum-format wallet account.
     * Useful right after first-time signup when the auth proxy config didn't auto-provision
     * one. Returns true if a new wallet was created, false if a usable account already existed.
     */
    suspend fun ensureEthereumWallet(): Boolean {
        TurnkeyContext.refreshWallets()
        val wallets = TurnkeyContext.wallets.value.orEmpty()
        val hasEthAccount = wallets
            .flatMap { it.accounts }
            .any { it.addressFormat == V1AddressFormat.ADDRESS_FORMAT_ETHEREUM }

        SampleLog.d(
            "TurnkeyAuth",
            "ensureEthereumWallet wallets=${wallets.size} hasEthAccount=$hasEthAccount"
        )
        if (hasEthAccount) return false

        val created = TurnkeyContext.createWallet(
            walletName = "Rain SDK Sample Wallet",
            accounts = listOf(
                V1WalletAccountParams(
                    addressFormat = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
                    curve = V1Curve.CURVE_SECP256K1,
                    path = "m/44'/60'/0'/0/0",
                    pathFormat = V1PathFormat.PATH_FORMAT_BIP32
                )
            ),
            mnemonicLength = 12L
        )
        SampleLog.i(
            "TurnkeyAuth",
            "created wallet id=${created.walletId} addresses=${created.addresses}"
        )
        return true
    }

    /**
     * Ensures the authenticated sub-org has at least one Solana-format wallet account
     * (ed25519, BIP44 path `m/44'/501'/0'/0'`). Mirrors [ensureEthereumWallet] so the demo
     * can hand Rain both an EVM and a Solana account. Returns true if a new account was added.
     */
    suspend fun ensureSolanaWallet(): Boolean {
        TurnkeyContext.refreshWallets()
        val wallets = TurnkeyContext.wallets.value.orEmpty()
        val hasSolanaAccount = wallets
            .flatMap { it.accounts }
            .any { it.addressFormat == V1AddressFormat.ADDRESS_FORMAT_SOLANA }

        SampleLog.d(
            "TurnkeyAuth",
            "ensureSolanaWallet wallets=${wallets.size} hasSolanaAccount=$hasSolanaAccount"
        )
        if (hasSolanaAccount) return false

        val created = TurnkeyContext.createWallet(
            walletName = "Rain SDK Sample Solana Wallet",
            accounts = listOf(
                V1WalletAccountParams(
                    addressFormat = V1AddressFormat.ADDRESS_FORMAT_SOLANA,
                    curve = V1Curve.CURVE_ED25519,
                    path = "m/44'/501'/0'/0'",
                    pathFormat = V1PathFormat.PATH_FORMAT_BIP32
                )
            ),
            mnemonicLength = 12L
        )
        SampleLog.i(
            "TurnkeyAuth",
            "created solana wallet id=${created.walletId} addresses=${created.addresses}"
        )
        return true
    }
}
