package com.rain.sdk.sample.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.sample.PrivyAuthSample
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.TurnkeyAuthSample
import com.rain.sdk.sample.WalletChain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WalletMode { Portal, Turnkey, Privy }

class HomeViewModel(
    private val session: RainSession
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(isInitialized = session.isInitialized)
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // Apply the (dev-default) credentials so the SDK is configured even when the user
        // never edits the fields.
        session.configureRainApi(_state.value.rainApiKey, _state.value.userId)
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

    fun onPinChanged(value: String) {
        _state.update { it.copy(pin = value) }
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

        viewModelScope.launch {
            try {
                // Initialize with every EVM chain's RPC (Fuji + Base Sepolia) so the chain
                // dropdown and Rain collateral (which lives on Base Sepolia) both work; the
                // screens pick the active chain via `selectedChain`.
                val rpcConfig = WalletChain.entries
                    .filter { !it.isSolana }
                    .associate { it.chainId to it.rpcUrl }

                session.initializePortal(
                    sessionToken = _state.value.sessionToken,
                    rpcEndpoints = rpcConfig,
                    chainId = RainChain.AVALANCHE_TESTNET
                )

                SampleLog.i("Portal.init", "success — isInitialized=${session.isInitialized}")
                // Recovery (Portal backup share) is no longer available via the Rain API, so a
                // successful init goes straight to the feature grid instead of gating on recovery.
                _state.update {
                    it.copy(
                        isInitialized = session.isInitialized,
                        statusText = "SDK Initialized Successfully!",
                        needsRecovery = false,
                        isRecovered = true
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("Portal.init", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        statusText = "Error: ${e.message}",
                        isInitialized = false
                    )
                }
            }
        }
    }

    fun recoverWithPin() {
        // Portal wallet recovery previously pulled the encrypted backup share from the
        // Liquidity Financial proxy (`/v1/portal/backup`). The Rain dev API has no equivalent
        // yet — backup/recovery is slated to move behind the wallet-provider endpoint
        // (`POST /v1/issuing/users/{userId}/wallet`), which is not live. Surface that clearly
        // instead of calling a dead LF endpoint.
        SampleLog.w("Portal.recover", "recovery unavailable — Rain wallet endpoint not yet live")
        _state.update {
            it.copy(
                statusText = "Wallet recovery is not yet available via the Rain API " +
                    "(pending the wallet-provider endpoint).",
                isLoading = false
            )
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
        _state.update { it.copy(isLoading = true, statusText = "Initializing Turnkey...") }
        viewModelScope.launch {
            try {
                TurnkeyAuthSample.init(app, s.turnkeyOrgId, s.turnkeyAuthProxyConfigId)

                // Turnkey restores a valid session from secure storage during init. If one is
                // present, skip the OTP round-trip and go straight to initializing Rain.
                if (TurnkeyAuthSample.hasActiveSession()) {
                    SampleLog.i("Turnkey.otpInit", "existing session restored — skipping OTP")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            turnkeySessionActive = true,
                            statusText = "Existing Turnkey session restored — initialize Rain to continue"
                        )
                    }
                    return@launch
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
                    walletAddress = null
                )
                val evmAddress = runCatching { session.client?.getWalletAddress(WalletChain.EVM.chainId) }.getOrNull()
                val solAddress = runCatching { session.client?.getWalletAddress(WalletChain.SOLANA.chainId) }.getOrNull()
                SampleLog.i(
                    "Turnkey.rainInit",
                    "success — isInitialized=${session.isInitialized} evm=$evmAddress sol=$solAddress"
                )
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
                _state.update {
                    it.copy(
                        isLoading = false,
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
        _state.update { it.copy(isLoading = true, statusText = "Initializing Privy...") }
        viewModelScope.launch {
            try {
                PrivyAuthSample.init(app, s.privyAppId, s.privyAppClientId)

                // Privy restores a prior authenticated session during init; skip the OTP if so.
                if (PrivyAuthSample.hasActiveSession()) {
                    SampleLog.i("Privy.otpInit", "existing session restored — skipping OTP")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            privySessionActive = true,
                            statusText = "Existing Privy session restored — initialize Rain to continue"
                        )
                    }
                    return@launch
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
        SampleLog.i("Privy.rainInit", "initializing Rain w/ Privy (EVM)")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Rain with Privy...") }
        viewModelScope.launch {
            try {
                val created = PrivyAuthSample.ensureEthereumWallet()
                if (created) {
                    _state.update { it.copy(statusText = "Provisioned Privy wallet, initializing Rain...") }
                }

                // Privy is EVM-only here; initialize with the EVM chains' RPC endpoints.
                val rpcConfig = WalletChain.entries
                    .filter { !it.isSolana }
                    .associate { it.chainId to it.rpcUrl }

                session.initializePrivy(
                    privy = PrivyAuthSample.privy,
                    rpcEndpoints = rpcConfig,
                    walletAddress = null
                )
                val evmAddress = runCatching { session.client?.getWalletAddress(WalletChain.EVM.chainId) }.getOrNull()
                SampleLog.i("Privy.rainInit", "success — isInitialized=${session.isInitialized} evm=$evmAddress")
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
                _state.update {
                    it.copy(isLoading = false, statusText = "Rain Privy init failed: ${e.message}")
                }
            }
        }
    }

    fun clearSession() {
        SampleLog.i("Home", "clearing session (provider logout + UI reset)")
        viewModelScope.launch {
            // Real logout so the next run requires fresh auth (and resume detects no session).
            TurnkeyAuthSample.logout()
            PrivyAuthSample.logout()
            _state.update {
                HomeUiState(statusText = "Session Cleared", mode = it.mode)
            }
        }
    }
}

data class HomeUiState(
    val mode: WalletMode = WalletMode.Turnkey,
    val sessionToken: String = "",
    // Dev-only defaults; clear before release.
    val rainApiKey: String = "183e782b19016d4ee4c5a97e269c130d41fd3963",
    val userId: String = "0a0eb6d6-f109-46b0-a09a-adf10b564525",
    val pin: String = "",
    val turnkeyOrgId: String = "",
    val turnkeyAuthProxyConfigId: String = "",
    val turnkeyEmail: String = "",
    val turnkeyOtpId: String? = null,
    val turnkeyOtpEncryptionBundle: String? = null,
    val turnkeyOtpCode: String = "",
    val turnkeySessionActive: Boolean = false,
    val privyAppId: String = "cmqffvqfu00xh0dl70hgq9qyw",
    val privyAppClientId: String = "client-WY6aRWodKbyNL5djJVCTNCyKNbuNV4TXFmdy5Hnc3xHBQ",
    val privyEmail: String = "",
    val privyOtpSent: Boolean = false,
    val privyOtpCode: String = "",
    val privySessionActive: Boolean = false,
    val isInitialized: Boolean = false,
    val needsRecovery: Boolean = false,
    val isRecovered: Boolean = false,
    val isLoading: Boolean = false,
    val statusText: String = "Ready"
)

class HomeViewModelFactory(
    private val session: RainSession
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(session) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
