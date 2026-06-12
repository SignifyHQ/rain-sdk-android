package com.rain.sdk.sample.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.NetworkClient
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.TurnkeyAuthSample
import com.rain.sdk.sample.WalletChain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WalletMode { Portal, Turnkey }

class HomeViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(isInitialized = rainClient.isInitialized)
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun onModeChanged(mode: WalletMode) {
        SampleLog.d("Home", "mode changed: $mode")
        _state.update { it.copy(mode = mode) }
    }

    fun onSessionTokenChanged(value: String) {
        _state.update { it.copy(sessionToken = value) }
    }

    fun onRainApiKeyChanged(value: String) {
        _state.update { it.copy(rainApiKey = value) }
        NetworkClient.configure(value, _state.value.userId)
    }

    fun onUserIdChanged(value: String) {
        _state.update { it.copy(userId = value) }
        NetworkClient.configure(_state.value.rainApiKey, value)
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

    fun initializeSdk() {
        if (_state.value.sessionToken.isBlank()) return

        val tokenMask = SampleLog.maskToken(_state.value.sessionToken)
        SampleLog.i("Portal.init", "calling initializePortal sessionToken=$tokenMask chainId=${RainChain.AVALANCHE_TESTNET}")

        try {
            // Initialize with every EVM chain's RPC (Fuji + Base Sepolia) so the chain
            // dropdown and Rain collateral (which lives on Base Sepolia) both work; the
            // screens pick the active chain via `selectedChain`.
            val rpcConfig = WalletChain.entries
                .filter { !it.isSolana }
                .associate { it.chainId to it.rpcUrl }

            rainClient.initializePortal(
                portalSessionToken = _state.value.sessionToken,
                rpcEndpoints = rpcConfig,
                chainId = RainChain.AVALANCHE_TESTNET
            )

            SampleLog.i("Portal.init", "success — isInitialized=${rainClient.isInitialized}")
            // Recovery (Portal backup share) is no longer available via the Rain API, so a
            // successful init goes straight to the feature grid instead of gating on recovery.
            _state.update {
                it.copy(
                    isInitialized = rainClient.isInitialized,
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
                rainClient.initializeTurnkey(
                    turnkey = TurnkeyAuthSample.context,
                    rpcEndpoints = WalletChain.rpcEndpoints,
                    chainId = RainChain.AVALANCHE_TESTNET,
                    walletAddress = null
                )
                val evmAddress = runCatching { rainClient.getWalletAddress(WalletChain.EVM.chainId) }.getOrNull()
                val solAddress = runCatching { rainClient.getWalletAddress(WalletChain.SOLANA.chainId) }.getOrNull()
                SampleLog.i(
                    "Turnkey.rainInit",
                    "success — isInitialized=${rainClient.isInitialized} evm=$evmAddress sol=$solAddress"
                )
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = rainClient.isInitialized,
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

    fun clearSession() {
        SampleLog.i("Home", "clearing session (Turnkey logout + UI reset)")
        viewModelScope.launch {
            // Real logout so the next run requires a fresh OTP (and resume detects no session).
            TurnkeyAuthSample.logout()
            _state.update {
                HomeUiState(statusText = "Session Cleared", mode = it.mode)
            }
        }
    }
}

data class HomeUiState(
    val mode: WalletMode = WalletMode.Turnkey,
    val sessionToken: String = "",
    val rainApiKey: String = "",
    val userId: String = "",
    val pin: String = "",
    val turnkeyOrgId: String = "",
    val turnkeyAuthProxyConfigId: String = "",
    val turnkeyEmail: String = "",
    val turnkeyOtpId: String? = null,
    val turnkeyOtpEncryptionBundle: String? = null,
    val turnkeyOtpCode: String = "",
    val turnkeySessionActive: Boolean = false,
    val isInitialized: Boolean = false,
    val needsRecovery: Boolean = false,
    val isRecovered: Boolean = false,
    val isLoading: Boolean = false,
    val statusText: String = "Ready"
)

class HomeViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
