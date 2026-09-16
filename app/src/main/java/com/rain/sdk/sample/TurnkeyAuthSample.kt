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
 * Sample-app glue that drives Turnkey's Kotlin SDK end-to-end (init, email one-time code, wallet
 * provisioning) so the host app can hand a ready `TurnkeyContext` to the SDK's bring-your-own
 * `TurnkeyConfig(turnkey = …)`.
 *
 * This file is NOT part of the Rain SDK. It is reference code a host app writes itself when it
 * runs Turnkey on its own terms; the SDK owns none of this in bring-your-own mode (see
 * docs/TURNKEY_SUPPORT.md). An app that wants the SDK to own authentication uses the Rain wallet
 * instead. Both drive one process-wide Turnkey singleton, so a launch that configured one cannot
 * switch to the other without a relaunch.
 */
object TurnkeyAuthSample {

    /** Hand this to `TurnkeyConfig(turnkey = …)` once auth is complete. */
    val context: TurnkeyContext get() = TurnkeyContext

    /** Sub-organization ID minted (or reused) for the authenticated user. Null before login. */
    val subOrganizationId: String?
        get() = TurnkeyContext.session.value?.organizationId

    /** Minimum session lifetime (seconds) still worth resuming. */
    private const val SESSION_MIN_REMAINING_SECONDS = 30.0

    private val ETHEREUM_ACCOUNT = V1WalletAccountParams(
        addressFormat = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
        curve = V1Curve.CURVE_SECP256K1,
        path = "m/44'/60'/0'/0/0",
        pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
    )
    private val SOLANA_ACCOUNT = V1WalletAccountParams(
        addressFormat = V1AddressFormat.ADDRESS_FORMAT_SOLANA,
        curve = V1Curve.CURVE_ED25519,
        path = "m/44'/501'/0'/0'",
        pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
    )

    /**
     * True when an authenticated, unexpired Turnkey session is already loaded. The Turnkey SDK
     * restores a previously selected session from secure storage during [init], so after init this
     * reports whether the one-time-code step can be skipped.
     */
    fun hasActiveSession(): Boolean {
        val session = TurnkeyContext.session.value ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000.0
        return session.expiry > nowSeconds + SESSION_MIN_REMAINING_SECONDS
    }

    /**
     * Email address of the user the restored session belongs to, or null if it cannot be
     * determined. Callers deciding whether to reuse a restored session MUST compare this against
     * the email being logged in: a valid session for a different email must not be reused.
     */
    suspend fun activeSessionEmail(): String? {
        if (!hasActiveSession()) return null
        return TurnkeyContext.user.value?.userEmail
            ?: runCatching {
                TurnkeyContext.refreshUser()
                TurnkeyContext.user.value?.userEmail
            }.onFailure {
                SampleLog.w("TurnkeyAuth", "refreshUser failed while resolving session owner: ${it.message}")
            }.getOrNull()
    }

    /** Clears all stored Turnkey sessions (full logout). Safe no-op if none exist. */
    suspend fun logout() {
        runCatching { TurnkeyContext.clearAllSessions() }
            .onFailure { SampleLog.w("TurnkeyAuth", "logout (clearAllSessions) failed: ${it.message}") }
    }

    /** Snapshot of the ids Turnkey was initialized with, to detect edits made afterwards. */
    private var configuredWith: Pair<String, String>? = null

    /**
     * Initializes the Turnkey singleton. Idempotent; editing the ids afterwards throws, because
     * Turnkey's `initSuspend` silently ignores a second configuration within the same process.
     */
    suspend fun init(app: Application, organizationId: String, authProxyConfigId: String) {
        val snapshot = organizationId to authProxyConfigId
        configuredWith?.let { existing ->
            check(existing == snapshot) {
                "Turnkey is already configured with different values this session. Fully kill the " +
                    "app and relaunch to change the Organization ID or Auth Proxy Config ID."
            }
            TurnkeyContext.awaitReady()
            return
        }
        configuredWith = snapshot
        SampleLog.d(
            "TurnkeyAuth",
            "init org=${SampleLog.maskToken(organizationId)} proxy=${SampleLog.maskToken(authProxyConfigId)}"
        )
        TurnkeyContext.initSuspend(
            app = app,
            cfg = TurnkeyConfig(organizationId = organizationId, authProxyConfigId = authProxyConfigId),
        )
        TurnkeyContext.awaitReady()
        SampleLog.d("TurnkeyAuth", "TurnkeyContext ready")
    }

    /**
     * Starts the email one-time-code flow. Returns the [InitOtpResult], whose `otpId` and
     * `otpEncryptionTargetBundle` [verifyEmailOtp] both needs.
     */
    suspend fun sendEmailOtp(email: String): InitOtpResult {
        SampleLog.d("TurnkeyAuth", "sendEmailOtp to=${SampleLog.maskEmail(email)}")
        val result = TurnkeyContext.initOtp(otpType = OtpType.OTP_TYPE_EMAIL, contact = email)
        SampleLog.d("TurnkeyAuth", "OTP sent otpId=${SampleLog.maskToken(result.otpId)}")
        return result
    }

    /**
     * Verifies the code and creates a Turnkey session. `loginOrSignUpWithOtp` handles first-time
     * sign-up and returning login transparently; [otpEncryptionTargetBundle] comes from the
     * [sendEmailOtp] result.
     */
    suspend fun verifyEmailOtp(otpId: String, otpCode: String, otpEncryptionTargetBundle: String, email: String) {
        SampleLog.d("TurnkeyAuth", "verifyEmailOtp otpId=${SampleLog.maskToken(otpId)}")
        // A prior login leaves a persisted session under Turnkey's default key, and createSession
        // throws KeyAlreadyExists rather than overwriting it; clear stored sessions first.
        runCatching { TurnkeyContext.clearAllSessions() }
            .onFailure { SampleLog.w("TurnkeyAuth", "clearAllSessions failed (continuing): ${it.message}") }
        TurnkeyContext.loginOrSignUpWithOtp(
            otpId = otpId,
            otpCode = otpCode,
            otpEncryptionTargetBundle = otpEncryptionTargetBundle,
            contact = email,
            otpType = OtpType.OTP_TYPE_EMAIL,
        )
        SampleLog.d("TurnkeyAuth", "session active subOrgId=${SampleLog.maskToken(subOrganizationId)}")
    }

    /**
     * Ensures the authenticated sub-organization has an Ethereum and a Solana account. A fresh
     * account gets ONE wallet carrying both, a single seed to back up, which is what the Rain wallet
     * provisions too. An existing wallet that lacks a family is only warned about: deriving extra
     * accounts onto an existing seed needs the raw `create_wallet_accounts` API, which this sample
     * keeps out of scope. Returns true when a wallet was created.
     */
    suspend fun ensureWallets(): Boolean {
        TurnkeyContext.refreshWallets()
        val wallets = TurnkeyContext.wallets.value.orEmpty()
        val formats = wallets.flatMap { it.accounts }.map { it.addressFormat }.toSet()
        SampleLog.d("TurnkeyAuth", "ensureWallets wallets=${wallets.size} formats=${formats.size}")
        val hasEthereum = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM in formats
        val hasSolana = V1AddressFormat.ADDRESS_FORMAT_SOLANA in formats
        return when {
            hasEthereum && hasSolana -> false
            wallets.isNotEmpty() -> {
                SampleLog.w(
                    "TurnkeyAuth",
                    "existing wallet lacks an Ethereum or Solana account; add it in Turnkey (create_wallet_accounts)"
                )
                false
            }
            else -> {
                val created = TurnkeyContext.createWallet(
                    walletName = "Rain SDK Sample Wallet",
                    accounts = listOf(ETHEREUM_ACCOUNT, SOLANA_ACCOUNT),
                    mnemonicLength = 12L,
                )
                SampleLog.i("TurnkeyAuth", "created wallet id=${created.walletId} accounts=${created.addresses.size}")
                true
            }
        }
    }
}
