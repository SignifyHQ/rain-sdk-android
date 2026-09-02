package com.rain.sdk.sample.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.sample.PrivyAuthSample
import com.rain.sdk.sample.RainSampleApp
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.SessionHealth
import com.rain.sdk.sample.SessionStore
import com.rain.sdk.sample.TurnkeyAuthSample
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.WalletSessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WalletMode { Portal, Turnkey, Privy }

class HomeViewModel(
    private val app: RainSampleApp
) : ViewModel() {

    private val session: RainSession get() = app.session
    private val store: SessionStore get() = app.store

    // Fields start from the last working values; empty on first run.
    private val _state = MutableStateFlow(seededState(store.provider?.toMode() ?: WalletMode.Turnkey))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private fun seededState(mode: WalletMode) = HomeUiState(
        mode = mode,
        sessionToken = store.portalSessionToken,
        rainApiKey = store.rainApiKey,
        userId = store.rainUserId,
        turnkeyOrgId = store.turnkeyOrgId,
        turnkeyAuthProxyConfigId = store.turnkeyAuthProxyConfigId,
        turnkeyEmail = store.turnkeyEmail,
        privyAppId = store.privyAppId,
        privyAppClientId = store.privyAppClientId,
        privyEmail = store.privyEmail,
    )

    init {
        session.configureRainApi(_state.value.rainApiKey, _state.value.userId)

        viewModelScope.launch {
            session.sessionStatus.collect { status ->
                _state.update { current ->
                    // Portal Refreshing→Active means onSessionTokenNeeded consumed the replacement.
                    val consumedReplacement =
                        current.sessionStatus?.health == SessionHealth.Transitional &&
                            status?.health == SessionHealth.Healthy &&
                            current.mode == WalletMode.Portal &&
                            current.replacementPortalToken.isNotBlank()
                    if (consumedReplacement) store.portalSessionToken = current.replacementPortalToken.trim()
                    current.copy(
                        sessionStatus = status,
                        sessionToken = if (consumedReplacement) current.replacementPortalToken.trim() else current.sessionToken,
                        replacementPortalToken = if (consumedReplacement) "" else current.replacementPortalToken,
                    )
                }
            }
        }

        if (session.isInitialized) markResumed() else resumeIfPossible()
    }

    /** The SDK outlived this ViewModel (Activity recreation): reflect its state without re-initializing. */
    private fun markResumed() {
        _state.update {
            it.copy(
                isInitialized = true,
                isRecovered = true,
                turnkeySessionActive = it.mode == WalletMode.Turnkey,
                privySessionActive = it.mode == WalletMode.Privy,
                statusText = "Session resumed"
            )
        }
    }

    /** Replays the saved provider through the same paths the buttons use; falls back to the manual screen. */
    private fun resumeIfPossible() {
        val provider = store.provider ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, statusText = "Resuming ${provider.name} session...") }
            when (provider) {
                SessionStore.Provider.Portal ->
                    if (_state.value.sessionToken.isNotBlank()) initializeSdk() else resumeFallback("Ready")
                SessionStore.Provider.Turnkey -> {
                    app.vendorInit?.join()
                    if (!TurnkeyAuthSample.hasActiveSession()) {
                        resumeFallback("Saved Turnkey session expired — log in again")
                    } else if (!TurnkeyAuthSample.activeSessionEmail().matches(_state.value.turnkeyEmail)) {
                        resumeFallback("Saved Turnkey session belongs to another email — log in again")
                    } else {
                        _state.update { it.copy(turnkeySessionActive = true) }
                        initializeRainWithTurnkey()
                    }
                }
                SessionStore.Provider.Privy -> {
                    app.vendorInit?.join()
                    if (!PrivyAuthSample.hasActiveSession()) {
                        resumeFallback("Saved Privy session expired — log in again")
                    } else if (!PrivyAuthSample.activeSessionEmail().matches(_state.value.privyEmail)) {
                        resumeFallback("Saved Privy session belongs to another email — log in again")
                    } else {
                        _state.update { it.copy(privySessionActive = true) }
                        initializeRainWithPrivy()
                    }
                }
            }
        }
    }

    private fun resumeFallback(message: String) {
        SampleLog.i("Resume", message)
        _state.update { it.copy(isLoading = false, statusText = message) }
    }

    private fun String?.matches(email: String): Boolean =
        this != null && trim().equals(email.trim(), ignoreCase = true)

    fun onReplacementPortalTokenChanged(value: String) {
        _state.update { it.copy(replacementPortalToken = value) }
    }

    fun refreshSession() {
        SampleLog.i("Session", "manual refreshSession() on ${_state.value.mode}")
        _state.update { it.copy(isLoading = true, statusText = "Refreshing session...") }
        viewModelScope.launch {
            try {
                session.refreshSession()
                _state.update { it.copy(isLoading = false, statusText = "Session refreshed") }
            } catch (e: Exception) {
                SampleLog.e("Session", "refresh failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "Session refresh failed: ${e.message}")
                }
            }
        }
    }

    /** Portal only: installs the replacement token in place — no SDK rebuild. */
    fun updatePortalSessionToken() {
        val token = _state.value.replacementPortalToken.trim()
        if (token.isBlank()) return
        SampleLog.i("Portal.session", "updateSessionToken(${SampleLog.maskToken(token)})")
        _state.update { it.copy(isLoading = true, statusText = "Installing new Portal session token...") }
        viewModelScope.launch {
            try {
                session.updatePortalSessionToken(token)
                store.portalSessionToken = token
                _state.update {
                    it.copy(
                        isLoading = false,
                        sessionToken = token,
                        replacementPortalToken = "",
                        statusText = "Portal session token updated"
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Portal.session", "updateSessionToken failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "Token update failed: ${e.message}")
                }
            }
        }
    }

    fun onModeChanged(mode: WalletMode) {
        SampleLog.d("Home", "mode changed: $mode")
        _state.update { it.copy(mode = mode) }
    }

    fun onSessionTokenChanged(value: String) {
        _state.update { it.copy(sessionToken = value) }
    }

    fun onRainApiKeyChanged(value: String) {
        _state.update { it.copy(rainApiKey = value) }
        session.configureRainApi(value, _state.value.userId)
    }

    fun onUserIdChanged(value: String) {
        _state.update { it.copy(userId = value) }
        session.configureRainApi(_state.value.rainApiKey, value)
    }

    fun onTurnkeyOrgIdChanged(value: String) {
        _state.update { it.copy(turnkeyOrgId = value) }
    }

    fun onTurnkeyAuthProxyConfigIdChanged(value: String) {
        _state.update { it.copy(turnkeyAuthProxyConfigId = value) }
    }

    fun onTurnkeyEmailChanged(value: String) {
        _state.update { it.copy(turnkeyEmail = value) }
    }

    fun onTurnkeyOtpCodeChanged(value: String) {
        _state.update { it.copy(turnkeyOtpCode = value) }
    }

    fun onPrivyAppIdChanged(value: String) {
        _state.update { it.copy(privyAppId = value) }
    }

    fun onPrivyAppClientIdChanged(value: String) {
        _state.update { it.copy(privyAppClientId = value) }
    }

    fun onPrivyEmailChanged(value: String) {
        _state.update { it.copy(privyEmail = value) }
    }

    fun onPrivyOtpCodeChanged(value: String) {
        _state.update { it.copy(privyOtpCode = value) }
    }

    fun initializeSdk() {
        if (_state.value.sessionToken.isBlank()) return

        val tokenMask = SampleLog.maskToken(_state.value.sessionToken)
        SampleLog.i("Portal.init", "calling initializePortal sessionToken=$tokenMask chainId=${RainChain.AVALANCHE_TESTNET}")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Portal...") }

        // Hooks outlive this call: capture the state holder, not the ViewModel.
        val uiState = _state
        viewModelScope.launch {
            try {
                // Initialize with every EVM chain's RPC (Fuji + Base Sepolia) so the chain
                // dropdown and Rain collateral (which lives on Base Sepolia) both work; the
                // screens pick the active chain via `selectedChain`.
                val rpcConfig = WalletChain.selectable
                    .filter { !it.isSolana }
                    .associate { it.chainId to it.rpcUrl }

                session.initializePortal(
                    sessionToken = _state.value.sessionToken,
                    rpcEndpoints = rpcConfig,
                    chainId = RainChain.AVALANCHE_TESTNET,
                    // A host mints a new token for the SAME Portal client here; this sample has no
                    // backend, so it returns the typed replacement or null (declines).
                    onSessionTokenNeeded = {
                        val replacement = uiState.value.replacementPortalToken.trim()
                        if (replacement.isBlank()) {
                            SampleLog.w("Portal.session", "onSessionTokenNeeded: no replacement token, declining")
                            null
                        } else {
                            SampleLog.i("Portal.session", "onSessionTokenNeeded: supplying replacement token")
                            replacement
                        }
                    },
                    // Recovery is "Update token" or Clear Session; the feature grid hides meanwhile.
                    onSessionExpired = {
                        SampleLog.w("Portal.session", "Portal session token rejected, new token required")
                        uiState.update {
                            it.copy(statusText = "Portal session expired — enter a replacement token")
                        }
                    }
                )

                // A freshly-created Portal client has no wallet; generate one before any screen
                // asks for an address. MPC keygen takes a few seconds on first run.
                _state.update { it.copy(statusText = "Setting up wallet…") }
                val createdWallet = session.ensurePortalWallet()

                SampleLog.i(
                    "Portal.init",
                    "success — isInitialized=${session.isInitialized} createdWallet=$createdWallet"
                )
                // Recovery (Portal backup share) is no longer available via the Rain API, so a
                // successful init goes straight to the feature grid instead of gating on recovery.
                persistRainCredentials(SessionStore.Provider.Portal)
                store.portalSessionToken = _state.value.sessionToken.trim()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = session.isInitialized,
                        statusText = "SDK Initialized Successfully!",
                        isRecovered = true
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Portal.init", "failed: ${e.message}", e)
                // Nothing usable survives a failed init: tear the half-built SDK down so its
                // session watcher stops reporting, and drop the stale status with it.
                session.reset()
                _state.update {
                    it.copy(
                        isLoading = false,
                        statusText = "Error: ${e.message}",
                        isInitialized = false,
                        sessionStatus = null
                    )
                }
            }
        }
    }

    fun sendTurnkeyOtp(app: Application) {
        val s = _state.value
        if (s.turnkeyOrgId.isBlank() || s.turnkeyAuthProxyConfigId.isBlank() || s.turnkeyEmail.isBlank()) {
            _state.update { it.copy(statusText = "Org ID, Auth Proxy Config ID, and Email are required") }
            return
        }

        SampleLog.i(
            "Turnkey.otpInit",
            "starting email-OTP flow email=${SampleLog.maskEmail(s.turnkeyEmail)}"
        )
        // Saved before init so a relaunch (the only way to change ids) picks up the new values.
        store.provider = SessionStore.Provider.Turnkey
        store.turnkeyOrgId = s.turnkeyOrgId.trim()
        store.turnkeyAuthProxyConfigId = s.turnkeyAuthProxyConfigId.trim()
        store.turnkeyEmail = s.turnkeyEmail.trim()
        _state.update { it.copy(isLoading = true, statusText = "Initializing Turnkey...") }
        viewModelScope.launch {
            try {
                TurnkeyAuthSample.init(app, s.turnkeyOrgId, s.turnkeyAuthProxyConfigId)

                // Turnkey restores a valid session from secure storage during init. Only reuse
                // it when it provably belongs to the email being logged in — otherwise entering
                // a different email would silently continue as the previous user. On mismatch
                // (or when the owner can't be determined) log out and run the full OTP flow.
                if (TurnkeyAuthSample.hasActiveSession()) {
                    val sessionEmail = TurnkeyAuthSample.activeSessionEmail()
                    if (sessionEmail != null && sessionEmail.trim().equals(s.turnkeyEmail.trim(), ignoreCase = true)) {
                        SampleLog.i("Turnkey.otpInit", "existing session restored for this email — skipping OTP")
                        _state.update {
                            it.copy(
                                isLoading = false,
                                turnkeySessionActive = true,
                                statusText = "Existing Turnkey session restored — initialize Rain to continue"
                            )
                        }
                        return@launch
                    }
                    SampleLog.w(
                        "Turnkey.otpInit",
                        "restored session belongs to ${SampleLog.maskEmail(sessionEmail)}, " +
                            "not ${SampleLog.maskEmail(s.turnkeyEmail)} — logging out"
                    )
                    TurnkeyAuthSample.logout()
                }

                _state.update { it.copy(statusText = "Sending OTP to ${s.turnkeyEmail}...") }
                val otpResult = TurnkeyAuthSample.sendEmailOtp(s.turnkeyEmail)

                SampleLog.i("Turnkey.otpInit", "OTP sent")
                _state.update {
                    it.copy(
                        isLoading = false,
                        turnkeyOtpId = otpResult.otpId,
                        turnkeyOtpEncryptionBundle = otpResult.otpEncryptionTargetBundle,
                        statusText = "OTP sent — check your email"
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Turnkey.otpInit", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        statusText = "Turnkey OTP init failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun verifyTurnkeyOtp() {
        val s = _state.value
        val otpId = s.turnkeyOtpId
        val otpEncryptionBundle = s.turnkeyOtpEncryptionBundle
        if (otpId.isNullOrBlank() || otpEncryptionBundle.isNullOrBlank()) {
            _state.update { it.copy(statusText = "Send OTP first") }
            return
        }
        if (s.turnkeyOtpCode.isBlank()) {
            _state.update { it.copy(statusText = "OTP code required") }
            return
        }

        SampleLog.i("Turnkey.otpVerify", "verifying OTP")
        _state.update { it.copy(isLoading = true, statusText = "Verifying OTP...") }
        viewModelScope.launch {
            try {
                TurnkeyAuthSample.verifyEmailOtp(otpId, s.turnkeyOtpCode, otpEncryptionBundle, s.turnkeyEmail)
                SampleLog.i(
                    "Turnkey.otpVerify",
                    "session active subOrgId=${SampleLog.maskToken(TurnkeyAuthSample.subOrganizationId)}"
                )
                _state.update {
                    it.copy(
                        isLoading = false,
                        turnkeySessionActive = true,
                        statusText = "Turnkey session active — initialize Rain to continue"
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Turnkey.otpVerify", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        statusText = "OTP verification failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun initializeRainWithTurnkey() {
        if (!_state.value.turnkeySessionActive) {
            _state.update { it.copy(statusText = "Verify OTP first") }
            return
        }
        SampleLog.i("Turnkey.rainInit", "initializing Rain w/ Turnkey (EVM + Solana)")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Rain with Turnkey...") }
        val uiState = _state
        viewModelScope.launch {
            try {
                val createdEvm = TurnkeyAuthSample.ensureEthereumWallet()
                val createdSolana = TurnkeyAuthSample.ensureSolanaWallet()
                if (createdEvm || createdSolana) {
                    _state.update { it.copy(statusText = "Provisioned Turnkey wallets, initializing Rain...") }
                }

                // Initialize with every supported chain's RPC so the dropdown can switch
                // between the EVM and Solana wallets without re-initializing.
                session.initializeTurnkey(
                    turnkey = TurnkeyAuthSample.context,
                    rpcEndpoints = WalletChain.rpcEndpoints,
                    chainId = RainChain.AVALANCHE_TESTNET,
                    walletAddress = null,
                    // Restart the OTP flow; a fresh login revives the provider (it watches the
                    // process-wide Turnkey singleton), so Rain is not re-initialized.
                    onSessionExpired = {
                        SampleLog.w("Turnkey.session", "Turnkey session expired, re-auth required")
                        uiState.update {
                            it.copy(
                                turnkeySessionActive = false,
                                turnkeyOtpId = null,
                                turnkeyOtpEncryptionBundle = null,
                                turnkeyOtpCode = "",
                                statusText = "Turnkey session expired — log in again"
                            )
                        }
                    }
                )
                val evmAddress = runCatching { session.client?.getWalletAddress(WalletChain.EVM.chainId) }.getOrNull()
                val solAddress = runCatching { session.client?.getWalletAddress(WalletChain.SOLANA.chainId) }.getOrNull()
                SampleLog.i(
                    "Turnkey.rainInit",
                    "success — isInitialized=${session.isInitialized} evm=$evmAddress sol=$solAddress"
                )
                persistRainCredentials(SessionStore.Provider.Turnkey)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = session.isInitialized,
                        isRecovered = true,
                        statusText = "Rain initialized with Turnkey — wallet ready"
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Turnkey.rainInit", "failed: ${e.message}", e)
                session.reset()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = false,
                        sessionStatus = null,
                        statusText = "Rain Turnkey init failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun sendPrivyOtp(app: Application) {
        val s = _state.value
        if (s.privyAppId.isBlank() || s.privyAppClientId.isBlank() || s.privyEmail.isBlank()) {
            _state.update { it.copy(statusText = "App ID, App Client ID, and Email are required") }
            return
        }

        SampleLog.i("Privy.otpInit", "starting email-OTP flow email=${SampleLog.maskEmail(s.privyEmail)}")
        store.provider = SessionStore.Provider.Privy
        store.privyAppId = s.privyAppId.trim()
        store.privyAppClientId = s.privyAppClientId.trim()
        store.privyEmail = s.privyEmail.trim()
        _state.update { it.copy(isLoading = true, statusText = "Initializing Privy...") }
        viewModelScope.launch {
            try {
                PrivyAuthSample.init(app, s.privyAppId, s.privyAppClientId)

                // Privy restores a prior authenticated session during init. Only reuse it when
                // it provably belongs to the email being logged in — otherwise entering a
                // different email would silently continue as the previous user. On mismatch
                // (or when the owner can't be determined) log out and run the full OTP flow.
                if (PrivyAuthSample.hasActiveSession()) {
                    val sessionEmail = PrivyAuthSample.activeSessionEmail()
                    if (sessionEmail != null && sessionEmail.trim().equals(s.privyEmail.trim(), ignoreCase = true)) {
                        SampleLog.i("Privy.otpInit", "existing session restored for this email — skipping OTP")
                        _state.update {
                            it.copy(
                                isLoading = false,
                                privySessionActive = true,
                                statusText = "Existing Privy session restored — initialize Rain to continue"
                            )
                        }
                        return@launch
                    }
                    SampleLog.w(
                        "Privy.otpInit",
                        "restored session belongs to ${SampleLog.maskEmail(sessionEmail)}, " +
                            "not ${SampleLog.maskEmail(s.privyEmail)} — logging out"
                    )
                    PrivyAuthSample.logout()
                }

                _state.update { it.copy(statusText = "Sending OTP to ${s.privyEmail}...") }
                PrivyAuthSample.sendEmailOtp(s.privyEmail)

                SampleLog.i("Privy.otpInit", "OTP sent")
                _state.update {
                    it.copy(
                        isLoading = false,
                        privyOtpSent = true,
                        statusText = "OTP sent — check your email"
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Privy.otpInit", "failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "Privy OTP init failed: ${e.message}")
                }
            }
        }
    }

    fun verifyPrivyOtp() {
        val s = _state.value
        if (!s.privyOtpSent) {
            _state.update { it.copy(statusText = "Send OTP first") }
            return
        }
        if (s.privyOtpCode.isBlank()) {
            _state.update { it.copy(statusText = "OTP code required") }
            return
        }

        SampleLog.i("Privy.otpVerify", "verifying OTP")
        _state.update { it.copy(isLoading = true, statusText = "Verifying OTP...") }
        viewModelScope.launch {
            try {
                PrivyAuthSample.verifyEmailOtp(s.privyOtpCode, s.privyEmail)
                SampleLog.i("Privy.otpVerify", "session active")
                _state.update {
                    it.copy(
                        isLoading = false,
                        privySessionActive = true,
                        statusText = "Privy session active — initialize Rain to continue"
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Privy.otpVerify", "failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "OTP verification failed: ${e.message}")
                }
            }
        }
    }

    fun initializeRainWithPrivy() {
        if (!_state.value.privySessionActive) {
            _state.update { it.copy(statusText = "Verify OTP first") }
            return
        }
        SampleLog.i("Privy.rainInit", "initializing Rain w/ Privy")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Rain with Privy...") }
        val uiState = _state
        viewModelScope.launch {
            try {
                val createdEvm = PrivyAuthSample.ensureEthereumWallet()
                val createdSolana = PrivyAuthSample.ensureSolanaWallet()
                if (createdEvm || createdSolana) {
                    _state.update { it.copy(statusText = "Provisioned Privy wallets, initializing Rain...") }
                }

                // Initialize with every supported chain's RPC (as on Turnkey) so the dropdown can
                // switch between the EVM and Solana wallets without re-initializing.
                session.initializePrivy(
                    privy = PrivyAuthSample.privy,
                    rpcEndpoints = WalletChain.rpcEndpoints,
                    walletAddress = null,
                    // Restart the OTP flow; a fresh login revives the provider (it watches the
                    // process-wide Privy singleton), so Rain is not re-initialized.
                    onSessionExpired = {
                        SampleLog.w("Privy.session", "Privy session expired, re-auth required")
                        uiState.update {
                            it.copy(
                                privySessionActive = false,
                                privyOtpSent = false,
                                privyOtpCode = "",
                                statusText = "Privy session expired — log in again"
                            )
                        }
                    }
                )
                val evmAddress = runCatching { session.client?.getWalletAddress(WalletChain.EVM.chainId) }.getOrNull()
                val solAddress = runCatching { session.client?.getWalletAddress(WalletChain.SOLANA.chainId) }.getOrNull()
                SampleLog.i(
                    "Privy.rainInit",
                    "success — isInitialized=${session.isInitialized} evm=$evmAddress sol=$solAddress"
                )
                persistRainCredentials(SessionStore.Provider.Privy)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = session.isInitialized,
                        isRecovered = true,
                        statusText = "Rain initialized with Privy — wallet ready"
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Privy.rainInit", "failed: ${e.message}", e)
                session.reset()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = false,
                        sessionStatus = null,
                        statusText = "Rain Privy init failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun persistRainCredentials(provider: SessionStore.Provider) {
        store.provider = provider
        store.rainApiKey = _state.value.rainApiKey.trim()
        store.rainUserId = _state.value.userId.trim()
    }

    fun clearSession() {
        SampleLog.i("Home", "clearing session (provider logout + SDK reset + UI reset)")
        viewModelScope.launch {
            // Close the provider first so its watcher does not report the logout as a death.
            session.reset()
            store.clear()
            // Real logout so the next run requires fresh auth (and resume detects no session).
            TurnkeyAuthSample.logout()
            PrivyAuthSample.logout()
            _state.update { seededState(it.mode).copy(statusText = "Session Cleared") }
        }
    }
}

data class HomeUiState(
    val mode: WalletMode = WalletMode.Turnkey,
    val sessionToken: String = "",
    val rainApiKey: String = "",
    val userId: String = "",
    val turnkeyOrgId: String = "",
    val turnkeyAuthProxyConfigId: String = "",
    val turnkeyEmail: String = "",
    val turnkeyOtpId: String? = null,
    val turnkeyOtpEncryptionBundle: String? = null,
    val turnkeyOtpCode: String = "",
    val turnkeySessionActive: Boolean = false,
    val privyAppId: String = "",
    val privyAppClientId: String = "",
    val privyEmail: String = "",
    val privyOtpSent: Boolean = false,
    val privyOtpCode: String = "",
    val privySessionActive: Boolean = false,
    val isInitialized: Boolean = false,
    val isRecovered: Boolean = false,
    val isLoading: Boolean = false,
    val statusText: String = "Ready",
    val sessionStatus: WalletSessionStatus? = null,
    /** Portal only: installed by "Update token" or handed to `onSessionTokenNeeded`. */
    val replacementPortalToken: String = "",
)

private fun SessionStore.Provider.toMode(): WalletMode = when (this) {
    SessionStore.Provider.Portal -> WalletMode.Portal
    SessionStore.Provider.Turnkey -> WalletMode.Turnkey
    SessionStore.Provider.Privy -> WalletMode.Privy
}

class HomeViewModelFactory(
    private val app: RainSampleApp
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
