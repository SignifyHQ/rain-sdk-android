package com.rain.sdk.sample.screens

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainSegmentedControl
import com.rain.sdk.sample.ui.RainStrong

/*
 * Home's provider configuration cards: Portal MPC (session token) and the two one-time-code
 * providers, Turnkey (email or SMS) and Privy (email). Shown while connecting and again, locked,
 * once connected.
 */

/** The configuration card for the selected provider. */
@Composable
internal fun ProviderCard(state: HomeUiState, viewModel: HomeViewModel, application: Application) {
    when (state.mode) {
        WalletMode.Portal -> PortalCard(state, viewModel)
        WalletMode.Turnkey -> TurnkeyCard(state, viewModel, application)
        WalletMode.Privy -> PrivyCard(state, viewModel, application)
    }
}

@Composable
private fun PortalCard(state: HomeUiState, viewModel: HomeViewModel) {
    RainCard {
        CardTitle("Portal MPC configuration", "Session token")
        RainField(
            label = "Portal session token",
            value = state.sessionToken,
            onValueChange = viewModel::onSessionTokenChanged,
            placeholder = "Paste a Portal session token",
            enabled = !state.isInitialized,
        )
        RainButton(
            text = if (state.isInitialized) "SDK initialized" else "Initialize SDK",
            onClick = viewModel::initializeSdk,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.sessionToken.isNotBlank() && !state.isInitialized && !state.isLoading,
            loading = state.isLoading && !state.isInitialized,
        )
    }
}

@Composable
private fun TurnkeyCard(state: HomeUiState, viewModel: HomeViewModel, application: Application) {
    // The ids, the channel and the contact are frozen once a code is out (a relaunch is the only
    // way to change the ids), but the button stays live as "Resend code": Turnkey codes expire
    // after 5 minutes and lock after 3 wrong attempts, and only a new code gets the user past either.
    // The channel switch also locks while a send is in flight and while a session is live, so the
    // channel the code went out on, and the one the header names, cannot change underneath.
    val codeSent = state.turnkeyOtpSent
    RainCard {
        CardTitle("Turnkey configuration", "One-time code by email or SMS")
        RainField(
            label = "Parent organization ID",
            value = state.turnkeyOrgId,
            onValueChange = viewModel::onTurnkeyOrgIdChanged,
            placeholder = "Organization ID",
            enabled = !codeSent,
        )
        RainField(
            label = "Auth proxy config ID",
            value = state.turnkeyAuthProxyConfigId,
            onValueChange = viewModel::onTurnkeyAuthProxyConfigIdChanged,
            placeholder = "Config ID",
            enabled = !codeSent,
        )
        TurnkeyContactFields(
            state,
            viewModel,
            switchEnabled = !codeSent && !state.isLoading && !state.turnkeySessionActive,
            fieldEnabled = !codeSent,
        )
        RainButton(
            text = if (codeSent) "Resend code" else "Send code",
            onClick = { viewModel.sendTurnkeyOtp(application) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.turnkeyOrgId.isNotBlank() &&
                state.turnkeyAuthProxyConfigId.isNotBlank() &&
                state.turnkeyContact.isNotBlank() &&
                !state.isLoading &&
                !state.turnkeySessionActive,
            loading = state.isLoading && !codeSent && !state.turnkeySessionActive,
        )
        if (codeSent) {
            OneTimeCodeStep(
                code = state.turnkeyOtpCode,
                onCodeChanged = viewModel::onTurnkeyOtpCodeChanged,
                sessionActive = state.turnkeySessionActive,
                isLoading = state.isLoading,
                onVerify = viewModel::verifyTurnkeyOtp,
                placeholder = state.turnkeyChannel.codePlaceholder,
            )
        }
        if (state.turnkeySessionActive) {
            InitializeRainButton(
                isInitialized = state.isInitialized,
                isLoading = state.isLoading,
                onClick = viewModel::initializeRainWithTurnkey,
            )
        }
    }
}

/** The "Send code by" switch and the selected channel's contact field. */
@Composable
private fun TurnkeyContactFields(
    state: HomeUiState,
    viewModel: HomeViewModel,
    switchEnabled: Boolean,
    fieldEnabled: Boolean,
) {
    Column {
        RainLabel("Send code by")
        RainSegmentedControl(
            options = TurnkeyContactChannel.entries.map { it.label },
            selectedIndex = state.turnkeyChannel.ordinal,
            onSelected = { viewModel.onTurnkeyChannelChanged(TurnkeyContactChannel.entries[it]) },
            enabled = switchEnabled,
        )
    }
    when (state.turnkeyChannel) {
        TurnkeyContactChannel.Email -> RainField(
            label = TurnkeyContactChannel.Email.fieldLabel,
            value = state.turnkeyEmail,
            onValueChange = viewModel::onTurnkeyEmailChanged,
            placeholder = "you@example.com",
            enabled = fieldEnabled,
            keyboardType = KeyboardType.Email,
        )
        // A number typed without a country code is converted with the device's region before it
        // reaches the SDK, which requires E.164 and removes spaces, dots, hyphens and parentheses.
        TurnkeyContactChannel.Phone -> RainField(
            label = TurnkeyContactChannel.Phone.fieldLabel,
            value = state.turnkeyPhone,
            onValueChange = viewModel::onTurnkeyPhoneChanged,
            placeholder = "+15551234567",
            enabled = fieldEnabled,
            helper = "With the country code, for example +15551234567",
            keyboardType = KeyboardType.Phone,
        )
    }
}

@Composable
private fun PrivyCard(state: HomeUiState, viewModel: HomeViewModel, application: Application) {
    val idsLocked = state.privyOtpSent || state.privySessionActive
    RainCard {
        CardTitle("Privy configuration", "Email one-time code")
        RainField(
            label = "App ID",
            value = state.privyAppId,
            onValueChange = viewModel::onPrivyAppIdChanged,
            placeholder = "Privy app ID",
            enabled = !idsLocked,
        )
        RainField(
            label = "App client ID",
            value = state.privyAppClientId,
            onValueChange = viewModel::onPrivyAppClientIdChanged,
            placeholder = "Privy app client ID",
            enabled = !idsLocked,
        )
        RainField(
            label = "Email",
            value = state.privyEmail,
            onValueChange = viewModel::onPrivyEmailChanged,
            placeholder = "you@example.com",
            enabled = !idsLocked,
            keyboardType = KeyboardType.Email,
        )
        RainButton(
            text = if (state.privyOtpSent) "Code sent" else "Send code",
            onClick = { viewModel.sendPrivyOtp(application) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.privyAppId.isNotBlank() &&
                state.privyAppClientId.isNotBlank() &&
                state.privyEmail.isNotBlank() &&
                !state.isLoading &&
                !idsLocked,
            loading = state.isLoading && !idsLocked,
        )
        if (state.privyOtpSent && !state.privySessionActive) {
            OneTimeCodeStep(
                code = state.privyOtpCode,
                onCodeChanged = viewModel::onPrivyOtpCodeChanged,
                sessionActive = false,
                isLoading = state.isLoading,
                onVerify = viewModel::verifyPrivyOtp,
            )
        }
        if (state.privySessionActive) {
            InitializeRainButton(
                isInitialized = state.isInitialized,
                isLoading = state.isLoading,
                onClick = viewModel::initializeRainWithPrivy,
            )
        }
    }
}

@Composable
private fun CardTitle(title: String, subtitle: String) {
    Column {
        RainStrong(title)
        RainLabel(subtitle)
    }
}

/** Code entry plus "Verify and log in"; both lock once the provider session is active. */
@Suppress("LongParameterList") // slot-style step shared by two providers: the placeholder is its only per-channel knob
@Composable
private fun OneTimeCodeStep(
    code: String,
    onCodeChanged: (String) -> Unit,
    sessionActive: Boolean,
    isLoading: Boolean,
    onVerify: () -> Unit,
    placeholder: String = "Code from your email",
) {
    RainField(
        label = "One-time code",
        value = code,
        onValueChange = onCodeChanged,
        placeholder = placeholder,
        enabled = !sessionActive,
        // Turnkey codes are numeric or alphanumeric depending on the auth-proxy setting in the
        // dashboard, one setting shared by email and SMS, so the keyboard must never be numeric-only.
        keyboardType = KeyboardType.Ascii,
    )
    RainButton(
        text = if (sessionActive) "Session active" else "Verify and log in",
        onClick = onVerify,
        modifier = Modifier.fillMaxWidth(),
        enabled = code.isNotBlank() && !isLoading && !sessionActive,
        loading = isLoading && !sessionActive,
    )
}

@Composable
private fun InitializeRainButton(isInitialized: Boolean, isLoading: Boolean, onClick: () -> Unit) {
    RainButton(
        text = if (isInitialized) "Rain initialized" else "Initialize Rain",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading && !isInitialized,
        loading = isLoading && !isInitialized,
    )
}
