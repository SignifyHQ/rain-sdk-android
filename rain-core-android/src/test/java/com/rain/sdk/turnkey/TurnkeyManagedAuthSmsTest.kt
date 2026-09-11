package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.helpers.expectThrows
import com.turnkey.core.models.errors.TurnkeyKotlinError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The SMS channel of [TurnkeyManagedAuthController], against [MockTurnkey]. The email channel and
 * everything channel-neutral (sessions, provisioning, logout, cancellation) is in
 * [TurnkeyManagedAuthTest]; this class covers what differs for a phone number: the channel that
 * reaches the vendor, canonicalization, and the pre-vendor checks. Gated on JDK 24 like every
 * Turnkey suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyManagedAuthSmsTest {

    @Before
    fun setUp() {
        assumeJdk24()
        TurnkeyManagedConfigurator.resetForTest()
    }

    @After
    fun tearDown() = TurnkeyManagedConfigurator.resetForTest()

    private fun coordinator(turnkey: MockTurnkey) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        onSessionExpired = { },
        retryDelay = { },
    )

    private fun controller(
        turnkey: MockTurnkey,
        coordinator: TurnkeySessionCoordinator = coordinator(turnkey),
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        coordinator = coordinator,
        configure = { null },
    )

    private fun rejectedCode(): Exception = TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
        TurnkeyKotlinError.FailedToVerifyOtp(RuntimeException("HTTP error from /v1/otp_verify_v2: 401"))
    )

    /** Turnkey's documented sandbox number, never a real person's. */
    private val smsContact = LoginContact.Sms("+19999999999")
    private val smsCall = MockTurnkey.SendOtpCall("+19999999999", OtpChannel.SMS)

    @Test
    fun `sendLoginCode by phone starts an SMS OTP for the E164 number and touches no session`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)

        controller.sendLoginCode(smsContact)

        assertThat(turnkey.sendOtpCalls).containsExactly(smsCall)
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(1)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
    }

    @Test
    fun `an SMS code is confirmed with the same channel and number it was sent with`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode(smsContact)
        controller.confirmLoginCode("123456")

        val call = turnkey.completeOtpCalls.single()
        assertThat(call.channel).isEqualTo(OtpChannel.SMS)
        assertThat(call.contact).isEqualTo("+19999999999")
        // The sign-up wallet does not depend on the channel.
        assertThat(call.signupWallet).isEqualTo(TurnkeyManagedAuthController.MANAGED_WALLET)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a phone number is canonicalized once and the identical string is sent on confirm`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode(LoginContact.Sms("  +1 (999) 999-9999 "))
        controller.confirmLoginCode("123456")

        assertThat(turnkey.sendOtpCalls).containsExactly(smsCall)
        assertThat(turnkey.completeOtpCalls.single().contact).isEqualTo("+19999999999")
    }

    @Test
    fun `a malformed phone number is a caller error before the vendor is touched`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)
        // No plus sign, a country code starting with 0, 16 digits, letters, a 00 prefix.
        val malformed = listOf("999-999-9999", "+0999999999", "+1234567890123456", "+1999abc9999", "0019999999999")

        malformed.forEach { number ->
            val thrown = expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(LoginContact.Sms(number)) }
            assertThat(thrown).hasMessageThat().contains("E.164")
            assertThat(thrown).hasMessageThat().doesNotContain(number)
        }

        assertThat(turnkey.sendOtpCalls).isEmpty()
        // Validation runs after the readiness check, in the same order as the blank-email check.
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(malformed.size)
    }

    @Test
    fun `a blank phone number is a caller error, not a rejected code`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)

        val thrown = expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(LoginContact.Sms("   ")) }

        assertThat(thrown).hasMessageThat().contains("blank")
        assertThat(turnkey.sendOtpCalls).isEmpty()
    }

    @Test
    fun `a blank email through the typed contact is still a caller error`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)

        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(LoginContact.Email("   ")) }

        assertThat(turnkey.sendOtpCalls).isEmpty()
    }

    @Test
    fun `a second sendLoginCode on the other channel replaces the pending challenge`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode("user@example.com")
        turnkey.stubbedOtpChallenge = OtpChallenge(
            otpId = "otp-2",
            encryptionTargetBundle = "bundle-2",
            channel = OtpChannel.EMAIL,
        )
        controller.sendLoginCode(smsContact)
        controller.confirmLoginCode("123456")

        val call = turnkey.completeOtpCalls.single()
        assertThat(call.otpId).isEqualTo("otp-2")
        assertThat(call.channel).isEqualTo(OtpChannel.SMS)
        assertThat(call.contact).isEqualTo("+19999999999")
    }

    @Test
    fun `a rejected SMS code surfaces InvalidLoginCode and keeps the challenge for a retry`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.completeOtpError = rejectedCode()
        val controller = controller(turnkey)
        controller.sendLoginCode(smsContact)

        expectThrows<RainError.InvalidLoginCode> { controller.confirmLoginCode("000000") }

        turnkey.completeOtpError = null
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        controller.confirmLoginCode("123456")
        assertThat(turnkey.sendOtpCalls).hasSize(1)
        assertThat(turnkey.completeOtpCalls.map { it.otpId }.toSet()).hasSize(1)
        assertThat(turnkey.completeOtpCalls.map { it.channel }.toSet()).containsExactly(OtpChannel.SMS)
    }

    @Test
    fun `a verify failure the mapper cannot classify keeps an SMS challenge for a retry`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.completeOtpError = TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
            TurnkeyKotlinError.FailedToVerifyOtp(RuntimeException("HTTP error from /v1/otp_verify_v2: 500"))
        )
        val controller = controller(turnkey)
        controller.sendLoginCode(smsContact)

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
    fun `logout drops a pending SMS code`() = runTest {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode(smsContact)

        controller.logout()
        runCurrent()

        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(turnkey.completeOtpCalls).isEmpty()
    }
}
