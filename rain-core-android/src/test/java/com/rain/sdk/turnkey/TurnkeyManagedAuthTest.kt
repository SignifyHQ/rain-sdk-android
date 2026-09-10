package com.rain.sdk.turnkey

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.helpers.expectThrows
import com.turnkey.core.models.AuthState
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Managed-auth tests for [TurnkeyManagedConfigurator] and [TurnkeyManagedAuthController], run
 * against [MockTurnkey] — no vendor singleton is ever touched. Gated on JDK 24 like every
 * Turnkey suite. The configurator is process-global state, so it is reset around every test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyManagedAuthTest {

    private var hookCalls = 0

    @Before
    fun setUp() {
        assumeJdk24()
        TurnkeyManagedConfigurator.resetForTest()
    }

    @After
    fun tearDown() = TurnkeyManagedConfigurator.resetForTest()

    private fun coordinator(turnkey: MockTurnkey) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        onSessionExpired = { hookCalls++ },
        retryDelay = { },
    )

    private fun controller(
        turnkey: MockTurnkey,
        configurationError: RainError? = null,
        coordinator: TurnkeySessionCoordinator = coordinator(turnkey),
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        coordinator = coordinator,
        configure = { configurationError },
    )

    private fun rejectedCode(): Exception = TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
        TurnkeyKotlinError.FailedToVerifyOtp(RuntimeException("HTTP error from /v1/otp_verify_v2: 401"))
    )

    // ---------- configurator (process-wide, one-shot) ----------

    @Test
    fun `configure is idempotent for identical ids and initializes the vendor once`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        val app = mockk<Application>()

        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "proxy-a")).isNull()
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "proxy-a")).isNull()

        assertThat(initCalls).isEqualTo(1)
    }

    @Test
    fun `configure with different ids returns InvalidConfig and leaves the first configuration in place`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        val app = mockk<Application>()

        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "proxy-a")).isNull()
        val mismatch = TurnkeyManagedConfigurator.configure(app, "org-b", "proxy-a")

        assertThat(mismatch).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(initCalls).isEqualTo(1)
        // The original ids still work.
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "proxy-a")).isNull()
    }

    @Test
    fun `configure rejects blank ids without recording them`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        val app = mockk<Application>()

        assertThat(
            TurnkeyManagedConfigurator.configure(app, "", "proxy-a")
        ).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(
            TurnkeyManagedConfigurator.configure(app, "org-a", "  ")
        ).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(initCalls).isEqualTo(0)
        // A blank attempt must not burn the process slot.
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "proxy-a")).isNull()
        assertThat(initCalls).isEqualTo(1)
    }

    @Test
    fun `configure refuses a vendor context that was initialized outside the SDK`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { true }

        val result = TurnkeyManagedConfigurator.configure(mockk<Application>(), "org-a", "proxy-a")

        assertThat(result).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(initCalls).isEqualTo(0)
    }

    @Test
    fun `a failing vendor initialization returns InternalError, records nothing, and can be retried`() = runTest {
        var attempts = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ ->
            attempts++
            if (attempts == 1) error("keystore unavailable")
        }
        // Like the vendor: it reads as initialized from the first attempt on, even a failed one.
        TurnkeyManagedConfigurator.vendorInitializedProbe = { attempts > 0 }
        val app = mockk<Application>()

        val first = TurnkeyManagedConfigurator.configure(app, "org-a", "proxy-a")
        assertThat(first).isInstanceOf(RainError.InternalError::class.java)
        // Nothing was recorded, so the same ids try again — and the SDK's own attempt must not be
        // mistaken for a context configured outside the SDK.
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "proxy-a")).isNull()
        assertThat(attempts).isEqualTo(2)
    }

    // ---------- email OTP ----------

    @Test
    fun `sendLoginCode starts an email OTP and touches no session`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)

        controller.sendLoginCode("user@example.com")

        assertThat(turnkey.sendOtpCalls).containsExactly("user@example.com")
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(1)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
    }

    @Test
    fun `confirmLoginCode without a prior sendLoginCode throws InvalidConfig`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)

        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(turnkey.completeOtpCalls).isEmpty()
    }

    @Test
    fun `first login completes with the stashed challenge under a fresh key and creates nothing when both accounts exist`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-1", encryptionTargetBundle = "bundle-1")
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode("user@example.com")
        controller.confirmLoginCode("123456")

        val call = turnkey.completeOtpCalls.single()
        assertThat(call.otpId).isEqualTo("otp-1")
        assertThat(call.otpCode).isEqualTo("123456")
        assertThat(call.otpEncryptionTargetBundle).isEqualTo("bundle-1")
        assertThat(call.contact).isEqualTo("user@example.com")
        assertThat(call.sessionKey).startsWith("rain-turnkey-")
        // A sign-up creates the wallet inside the signup request: one seed holding both accounts.
        // Name, order, curves and paths are the cross-platform contract shared by Rain's SDKs.
        assertThat(call.signupWallet.name).isEqualTo("Wallet")
        assertThat(call.signupWallet.accounts.map { it.addressFormat }).containsExactly(
            V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
            V1AddressFormat.ADDRESS_FORMAT_SOLANA
        ).inOrder()
        assertThat(call.signupWallet.accounts.map { it.curve }).containsExactly(
            V1Curve.CURVE_SECP256K1,
            V1Curve.CURVE_ED25519
        ).inOrder()
        assertThat(call.signupWallet.accounts.map { it.path }).containsExactly(
            "m/44'/60'/0'/0/0",
            "m/44'/501'/0'/0'"
        ).inOrder()
        // The vendor auto-selected the first session, so no redundant re-select.
        assertThat(turnkey.selectSessionCalls).isEmpty()
        assertThat(turnkey.selectedSessionKey).isEqualTo(call.sessionKey)
        assertThat(turnkey.createWalletCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `logging in over a live session switches to a fresh key, evicts cached accounts, then clears the old session`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val coordinator = coordinator(turnkey)
        var evictions = 0
        coordinator.onSessionDeath { evictions++ }
        val controller = controller(turnkey, coordinator = coordinator)

        controller.sendLoginCode("other@example.com")
        controller.confirmLoginCode("123456")

        val fresh = turnkey.completeOtpCalls.single().sessionKey
        assertThat(fresh).isNotEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.selectSessionCalls).containsExactly(fresh)
        assertThat(turnkey.selectedSessionKey).isEqualTo(fresh)
        // Never a pre-login clear; the previous session goes only after the switch succeeded.
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        // Active→Active is not a death the watcher notices, so eviction is explicit — host hook silent.
        assertThat(evictions).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `an Ethereum-only account gets a Solana account added to its existing wallet`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()), session = null)
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode("user@example.com")
        controller.confirmLoginCode("123456")

        // Added to the wallet Rain resolves, not minted as a second wallet (a second mnemonic).
        assertThat(turnkey.createWalletCalls).isEmpty()
        val added = turnkey.createWalletAccountsCalls.single()
        assertThat(added.walletId).isEqualTo("wallet-id")
        assertThat(added.accounts.map { it.addressFormat }).containsExactly(V1AddressFormat.ADDRESS_FORMAT_SOLANA)
        assertThat(added.accounts.single().curve).isEqualTo(V1Curve.CURVE_ED25519)
        assertThat(added.accounts.single().path).isEqualTo("m/44'/501'/0'/0'")
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(2)
    }

    @Test
    fun `an organization with no wallet at all gets one wallet holding both accounts`() = runTest {
        // An account created outside the sign-up flow (or predating the wallet-at-signup request).
        val turnkey = MockTurnkey(wallets = emptyList(), session = null)
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode("user@example.com")
        controller.confirmLoginCode("123456")

        val created = turnkey.createWalletCalls.single()
        assertThat(created.walletName).isEqualTo("Wallet")
        assertThat(created.accounts.map { it.addressFormat }).containsExactly(
            V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
            V1AddressFormat.ADDRESS_FORMAT_SOLANA
        ).inOrder()
        assertThat(created.accounts.map { it.curve }).containsExactly(
            V1Curve.CURVE_SECP256K1,
            V1Curve.CURVE_ED25519
        ).inOrder()
        assertThat(turnkey.createWalletAccountsCalls).isEmpty()
    }

    @Test
    fun `a rejected code surfaces InvalidLoginCode and never touches the live session`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.completeOtpError = rejectedCode()
        val controller = controller(turnkey)
        controller.sendLoginCode("user@example.com")

        expectThrows<RainError.InvalidLoginCode> { controller.confirmLoginCode("000000") }

        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.selectSessionCalls).isEmpty()
        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNotNull()

        // The challenge survives a rejected code so the user can simply retype it.
        turnkey.completeOtpError = null
        controller.confirmLoginCode("123456")
        assertThat(turnkey.completeOtpCalls).hasSize(2)
        assertThat(turnkey.completeOtpCalls.map { it.otpId }.toSet()).hasSize(1)
    }

    @Test
    fun `a provisioning failure throws, keeps the new session, and drops the spent code`() = runTest {
        val turnkey = MockTurnkey(wallets = emptyList(), session = null)
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        turnkey.createWalletError = TurnkeyKotlinError.FailedToCreateWallet(RuntimeException("server 500"))
        val controller = controller(turnkey)
        controller.sendLoginCode("user@example.com")

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isNotInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(turnkey.session).isNotNull()
        assertThat(turnkey.selectedSessionKey).isEqualTo(turnkey.completeOtpCalls.single().sessionKey)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        // The code was consumed by the verify step; a retry cannot re-run it.
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(turnkey.completeOtpCalls).hasSize(1)
    }

    @Test
    fun `a failure after the code was consumed drops the challenge even though it is not a rejection`() = runTest {
        val turnkey = MockTurnkey(session = null)
        // The vendor verified the code, then failed creating the session: the code is spent.
        turnkey.completeOtpError = TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
            TurnkeyKotlinError.FailedToCreateSession(RuntimeException("io"))
        )
        val controller = controller(turnkey)
        controller.sendLoginCode("user@example.com")

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isNotInstanceOf(RainError.InvalidLoginCode::class.java)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(turnkey.completeOtpCalls).hasSize(1)
    }

    @Test
    fun `a verify failure the mapper cannot classify keeps the challenge for a retry`() = runTest {
        // The auth proxy has been seen wrapping a rejected code in an HTTP 500 whose body the
        // Kotlin SDK discards: the mapper sees a plain 500 (ProviderError), but the code itself
        // was still being checked, so the same challenge must stay usable.
        val turnkey = MockTurnkey(session = null)
        turnkey.completeOtpError = TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
            TurnkeyKotlinError.FailedToVerifyOtp(RuntimeException("HTTP error from /v1/otp_verify_v2: 500"))
        )
        val controller = controller(turnkey)
        controller.sendLoginCode("user@example.com")

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }
        assertThat(thrown).isInstanceOf(RainError.ProviderError::class.java)

        turnkey.completeOtpError = null
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        controller.confirmLoginCode("123456")

        assertThat(turnkey.sendOtpCalls).hasSize(1)
        assertThat(turnkey.completeOtpCalls.map { it.otpId }.toSet()).containsExactly("otp-id")
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a verify call that never completes keeps the challenge for a retry`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.completeOtpError = TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
            TurnkeyKotlinError.FailedToVerifyOtp(java.io.IOException("unreachable"))
        )
        val controller = controller(turnkey)
        controller.sendLoginCode("user@example.com")

        expectThrows<RainError> { controller.confirmLoginCode("123456") }

        turnkey.completeOtpError = null
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        controller.confirmLoginCode("123456")

        assertThat(turnkey.sendOtpCalls).hasSize(1)
        assertThat(turnkey.completeOtpCalls).hasSize(2)
    }

    @Test
    fun `a blank code is a caller error, not a rejected code`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)
        controller.sendLoginCode("  user@example.com ")

        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("   ") }

        // The challenge survives, and the contact was normalized once for both steps.
        assertThat(turnkey.sendOtpCalls).containsExactly("user@example.com")
        assertThat(turnkey.completeOtpCalls).isEmpty()
    }

    @Test
    fun `ensureAccounts is idempotent and can be re-run to heal a half-provisioned session`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()))
        turnkey.onCreateWalletAccounts = { turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana()) }
        val controller = controller(turnkey)

        controller.ensureAccounts()
        controller.ensureAccounts()

        assertThat(turnkey.createWalletAccountsCalls).hasSize(1)
        assertThat(turnkey.createWalletCalls).isEmpty()
    }

    @Test
    fun `a select failure clears the fresh key and signs the device out without firing the re-auth hook`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.selectSessionError = TurnkeyKotlinError.FailedToSetSelectedSession(RuntimeException("io"))
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode("other@example.com")

        expectThrows<RainError> { controller.confirmLoginCode("123456") }
        runCurrent()

        // The fresh key never took effect and is dropped; the previous session was revoked by the
        // login that just succeeded, so it is cleared too rather than left to fail at the next call.
        val fresh = turnkey.completeOtpCalls.single().sessionKey
        assertThat(turnkey.clearSessionCalls).containsExactly(fresh)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
        assertThat(turnkey.selectedSessionKey).isNull()
        assertThat(turnkey.session).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(turnkey.createWalletCalls).isEmpty()
        // The thrown error is the signal; the re-auth hook stays silent for this death.
        assertThat(hookCalls).isEqualTo(0)

        // A genuine death after a re-login still notifies the host.
        turnkey.authenticate()
        runCurrent()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `a select failure whose sign-out also fails surfaces the select error and re-arms the re-auth hook`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.selectSessionError = TurnkeyKotlinError.FailedToSetSelectedSession(RuntimeException("io"))
        turnkey.clearSelectedSessionError = TurnkeyKotlinError.FailedToClearSession(RuntimeException("boom"))
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode("other@example.com")

        expectThrows<RainError> { controller.confirmLoginCode("123456") }

        // The clear failure is logged; what surfaces is the select failure the caller rethrows.
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        // The suppression armed for a sign-out that never happened must not swallow a real death.
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `a select failure after the selection took effect keeps the new session and clears nothing`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.selectSessionAppliesBeforeThrowing = true
        turnkey.selectSessionError = TurnkeyKotlinError.FailedToSetSelectedSession(RuntimeException("refresh 503"))
        val controller = controller(turnkey)
        controller.sendLoginCode("other@example.com")

        expectThrows<RainError> { controller.confirmLoginCode("123456") }

        val fresh = turnkey.completeOtpCalls.single().sessionKey
        assertThat(turnkey.selectedSessionKey).isEqualTo(fresh)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `a configuration error makes every auth call throw InvalidConfig before touching the vendor`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey, configurationError = RainError.InvalidConfig("mismatch"))

        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode("user@example.com") }
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        expectThrows<RainError.InvalidConfig> { controller.awaitSessionRestore(timeoutMs = 100) }
        expectThrows<RainError.InvalidConfig> { controller.logout() }

        assertThat(turnkey.awaitReadyCallCount).isEqualTo(0)
        assertThat(turnkey.sendOtpCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }

    // ---------- state ----------

    @Test
    fun `authState maps every session state and collapses Expired into Unauthenticated`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)

        turnkey.authStateFlow.value = AuthState.loading
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Loading)

        turnkey.authenticate()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(controller.authState.first()).isEqualTo(TurnkeyAuthState.Authenticated)

        turnkey.session = MockTurnkey.expiredSession()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(controller.authState.first()).isEqualTo(TurnkeyAuthState.Unauthenticated)
    }

    @Test
    fun `hasActiveSession is true only for a session with comfortably more than the floor left`() {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        assertThat(controller.hasActiveSession()).isTrue()

        turnkey.session = MockTurnkey.nearExpirySession(remainingSeconds = 10)
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)

        turnkey.session = MockTurnkey.expiredSession()
        assertThat(controller.hasActiveSession()).isFalse()

        turnkey.session = null
        assertThat(controller.hasActiveSession()).isFalse()
    }

    @Test
    fun `awaitSessionRestore returns once the restore settles and reports the session`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.authStateFlow.value = AuthState.loading
        val controller = controller(turnkey)
        launch {
            delay(50)
            turnkey.authenticate()
        }

        controller.awaitSessionRestore(timeoutMs = 5_000)

        assertThat(turnkey.awaitReadyCallCount).isEqualTo(1)
        assertThat(controller.hasActiveSession()).isTrue()
    }

    @Test
    fun `awaitSessionRestore returns without throwing when the restore does not settle in time`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.authStateFlow.value = AuthState.loading
        val controller = controller(turnkey)

        controller.awaitSessionRestore(timeoutMs = 250)

        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Loading)
        assertThat(controller.hasActiveSession()).isFalse()
    }

    @Test
    fun `a vendor that failed to initialize surfaces as InternalError, not a raw exception`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.awaitReadyError = IllegalStateException("init failed")
        val controller = controller(turnkey)

        expectThrows<RainError.InternalError> { controller.awaitSessionRestore(timeoutMs = 100) }
        expectThrows<RainError.InternalError> { controller.sendLoginCode("user@example.com") }
    }

    @Test
    fun `a vendor that never becomes ready surfaces as InternalError after the bound instead of hanging`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.awaitReadyGate = CompletableDeferred()
        val controller = controller(turnkey)

        expectThrows<RainError.InternalError> { controller.sendLoginCode("user@example.com") }
        assertThat(turnkey.sendOtpCalls).isEmpty()
    }

    // ---------- logout ----------

    @Test
    fun `logout clears the selected session and drops the pending code without firing the re-auth hook`() = runTest {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        // backgroundScope: the watcher re-arms a real-clock expiry timer, so a foreground scope
        // would keep runTest advancing virtual time forever once the test body finishes.
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode("user@example.com")

        controller.logout()
        runCurrent()

        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
        assertThat(turnkey.session).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(hookCalls).isEqualTo(0)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }

        // A genuine death after a re-login still notifies the host.
        turnkey.authenticate()
        runCurrent()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `logout drops the pending code even when the clear fails, and a later genuine death still notifies`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.clearSelectedSessionError = TurnkeyKotlinError.FailedToClearSession(RuntimeException("boom"))
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode("user@example.com")

        expectThrows<RainError> { controller.logout() }

        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(turnkey.completeOtpCalls).isEmpty()
        // The suppression armed for a logout that never happened must not swallow a real death.
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `logout with no session clears nothing and leaves the re-auth hook armed for later`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()

        controller.logout()

        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        // A later login followed by a genuine death notifies the host.
        turnkey.authenticate()
        runCurrent()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    // ---------- errors and cancellation ----------

    @Test
    fun `an arbitrary vendor exception surfaces as RainError from every public method`() = runTest {
        val turnkey = MockTurnkey(wallets = emptyList(), session = null)
        val controller = controller(turnkey)

        turnkey.sendOtpError = RuntimeException("boom")
        expectThrows<RainError> { controller.sendLoginCode("user@example.com") }
        turnkey.sendOtpError = null
        controller.sendLoginCode("user@example.com")

        turnkey.completeOtpError = RuntimeException("boom")
        expectThrows<RainError> { controller.confirmLoginCode("123456") }
        turnkey.completeOtpError = null

        // That failure spent the code, so a new challenge is needed before provisioning can run.
        controller.sendLoginCode("user@example.com")
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        turnkey.createWalletError = RuntimeException("boom")
        expectThrows<RainError> { controller.confirmLoginCode("123456") }

        turnkey.clearSelectedSessionError = RuntimeException("boom")
        expectThrows<RainError> { controller.logout() }
    }

    @Test
    fun `a cancelled confirmLoginCode propagates cancellation rather than a mapped error`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val gate = CompletableDeferred<Unit>()
        turnkey.onCompleteOtp = { gate.await() }
        val controller = controller(turnkey)
        controller.sendLoginCode("user@example.com")

        var caught: Throwable? = null
        val job = launch {
            try {
                controller.confirmLoginCode("123456")
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(caught).isNotInstanceOf(RainError::class.java)
    }

    @Test
    fun `a vendor error that merely wraps a cancellation while the caller is active is a RainError`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.completeOtpError = TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(CancellationException("inner job"))
        val controller = controller(turnkey)
        controller.sendLoginCode("user@example.com")

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---------- lifecycle ----------

    @Test
    fun `every auth method on a closed controller throws InvalidConfig and its state reads inert`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)

        controller.close()

        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode("user@example.com") }
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        expectThrows<RainError.InvalidConfig> { controller.logout() }
        expectThrows<RainError.InvalidConfig> { controller.awaitSessionRestore(timeoutMs = 100) }
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }
}
