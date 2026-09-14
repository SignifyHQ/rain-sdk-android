package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainSegmentedControl
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.theme.RainTheme

/*
 * Home's provider configuration cards: Portal MPC (session token) and the two one-time-code
 * providers, Turnkey (email or SMS) and Privy (email). Shown while connecting and again, locked,
 * once connected.
 */

/** The configuration card for the selected provider. */
@Composable
internal fun ProviderCard(state: HomeUiState, actions: ProviderCardActions) {
    when (state.mode) {
        WalletMode.Portal -> PortalCard(state, actions)
        WalletMode.Turnkey -> TurnkeyCard(state, actions)
        WalletMode.Privy -> PrivyCard(state, actions)
    }
}

/**
 * Callbacks the provider cards raise, so the cards can be previewed without a view model.
 *
 * The send callbacks take no `Application`: the caller captures it, because a preview's
 * `LocalContext` is not an `Application` and casting one inside a card would throw at composition.
 */
@Suppress("LongParameterList") // One callback per user action, across three providers.
internal class ProviderCardActions(
    val onSessionTokenChanged: (String) -> Unit,
    val onInitializeSdk: () -> Unit,
    val onTurnkeyOrgIdChanged: (String) -> Unit,
    val onTurnkeyAuthProxyConfigIdChanged: (String) -> Unit,
    val onTurnkeyChannelChanged: (TurnkeyContactChannel) -> Unit,
    val onTurnkeyEmailChanged: (String) -> Unit,
    val onTurnkeyPhoneChanged: (String) -> Unit,
    val onTurnkeyOtpCodeChanged: (String) -> Unit,
    val onSendTurnkeyCode: () -> Unit,
    val onVerifyTurnkeyOtp: () -> Unit,
    val onInitializeRainWithTurnkey: () -> Unit,
    val onPrivyAppIdChanged: (String) -> Unit,
    val onPrivyAppClientIdChanged: (String) -> Unit,
    val onPrivyEmailChanged: (String) -> Unit,
    val onPrivyOtpCodeChanged: (String) -> Unit,
    val onSendPrivyCode: () -> Unit,
    val onVerifyPrivyOtp: () -> Unit,
    val onInitializeRainWithPrivy: () -> Unit,
) {
    companion object {
        /** Inert callbacks for previews. */
        val None = ProviderCardActions(
            onSessionTokenChanged = {},
            onInitializeSdk = {},
            onTurnkeyOrgIdChanged = {},
            onTurnkeyAuthProxyConfigIdChanged = {},
            onTurnkeyChannelChanged = {},
            onTurnkeyEmailChanged = {},
            onTurnkeyPhoneChanged = {},
            onTurnkeyOtpCodeChanged = {},
            onSendTurnkeyCode = {},
            onVerifyTurnkeyOtp = {},
            onInitializeRainWithTurnkey = {},
            onPrivyAppIdChanged = {},
            onPrivyAppClientIdChanged = {},
            onPrivyEmailChanged = {},
            onPrivyOtpCodeChanged = {},
            onSendPrivyCode = {},
            onVerifyPrivyOtp = {},
            onInitializeRainWithPrivy = {},
        )
    }
}

@Composable
private fun PortalCard(state: HomeUiState, actions: ProviderCardActions) {
    RainCard {
        CardTitle("Portal MPC configuration", "Session token")
        RainField(
            label = "Portal session token",
            value = state.sessionToken,
            onValueChange = actions.onSessionTokenChanged,
            placeholder = "Paste a Portal session token",
            enabled = !state.isInitialized,
        )
        RainButton(
            text = if (state.isInitialized) "SDK initialized" else "Initialize SDK",
            onClick = actions.onInitializeSdk,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.sessionToken.isNotBlank() && !state.isInitialized && !state.isLoading,
            loading = state.isLoading && !state.isInitialized,
        )
    }
}

@Composable
private fun TurnkeyCard(state: HomeUiState, actions: ProviderCardActions) {
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
            onValueChange = actions.onTurnkeyOrgIdChanged,
            placeholder = "Organization ID",
            enabled = !codeSent,
        )
        RainField(
            label = "Auth proxy config ID",
            value = state.turnkeyAuthProxyConfigId,
            onValueChange = actions.onTurnkeyAuthProxyConfigIdChanged,
            placeholder = "Config ID",
            enabled = !codeSent,
        )
        TurnkeyContactFields(
            state,
            actions,
            switchEnabled = !codeSent && !state.isLoading && !state.turnkeySessionActive,
            fieldEnabled = !codeSent,
        )
        RainButton(
            text = if (codeSent) "Resend code" else "Send code",
            onClick = actions.onSendTurnkeyCode,
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
                onCodeChanged = actions.onTurnkeyOtpCodeChanged,
                sessionActive = state.turnkeySessionActive,
                isLoading = state.isLoading,
                onVerify = actions.onVerifyTurnkeyOtp,
                placeholder = state.turnkeyChannel.codePlaceholder,
            )
        }
        if (state.turnkeySessionActive) {
            InitializeRainButton(
                isInitialized = state.isInitialized,
                isLoading = state.isLoading,
                onClick = actions.onInitializeRainWithTurnkey,
            )
        }
    }
}

/** The "Send code by" switch and the selected channel's contact field. */
@Composable
private fun TurnkeyContactFields(
    state: HomeUiState,
    actions: ProviderCardActions,
    switchEnabled: Boolean,
    fieldEnabled: Boolean,
) {
    Column {
        RainLabel("Send code by")
        RainSegmentedControl(
            options = TurnkeyContactChannel.entries.map { it.label },
            selectedIndex = state.turnkeyChannel.ordinal,
            onSelected = { actions.onTurnkeyChannelChanged(TurnkeyContactChannel.entries[it]) },
            enabled = switchEnabled,
        )
    }
    when (state.turnkeyChannel) {
        TurnkeyContactChannel.Email -> RainField(
            label = TurnkeyContactChannel.Email.fieldLabel,
            value = state.turnkeyEmail,
            onValueChange = actions.onTurnkeyEmailChanged,
            placeholder = "you@example.com",
            enabled = fieldEnabled,
            keyboardType = KeyboardType.Email,
        )
        // A number typed without a country code is converted with the device's region before it
        // reaches the SDK, which requires E.164 and removes spaces, dots, hyphens and parentheses.
        TurnkeyContactChannel.Phone -> RainField(
            label = TurnkeyContactChannel.Phone.fieldLabel,
            value = state.turnkeyPhone,
            onValueChange = actions.onTurnkeyPhoneChanged,
            placeholder = "+15551234567",
            enabled = fieldEnabled,
            helper = "With the country code, for example +15551234567",
            keyboardType = KeyboardType.Phone,
        )
    }
}

@Composable
private fun PrivyCard(state: HomeUiState, actions: ProviderCardActions) {
    val idsLocked = state.privyOtpSent || state.privySessionActive
    RainCard {
        CardTitle("Privy configuration", "Email one-time code")
        RainField(
            label = "App ID",
            value = state.privyAppId,
            onValueChange = actions.onPrivyAppIdChanged,
            placeholder = "Privy app ID",
            enabled = !idsLocked,
        )
        RainField(
            label = "App client ID",
            value = state.privyAppClientId,
            onValueChange = actions.onPrivyAppClientIdChanged,
            placeholder = "Privy app client ID",
            enabled = !idsLocked,
        )
        RainField(
            label = "Email",
            value = state.privyEmail,
            onValueChange = actions.onPrivyEmailChanged,
            placeholder = "you@example.com",
            enabled = !idsLocked,
            keyboardType = KeyboardType.Email,
        )
        RainButton(
            text = if (state.privyOtpSent) "Code sent" else "Send code",
            onClick = actions.onSendPrivyCode,
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
                onCodeChanged = actions.onPrivyOtpCodeChanged,
                sessionActive = false,
                isLoading = state.isLoading,
                onVerify = actions.onVerifyPrivyOtp,
            )
        }
        if (state.privySessionActive) {
            InitializeRainButton(
                isInitialized = state.isInitialized,
                isLoading = state.isLoading,
                onClick = actions.onInitializeRainWithPrivy,
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

// region Previews

private const val PREVIEW_TURNKEY_ORG_ID = "a1b2c3d4-5e6f-7890-abcd-ef1234567890"
private const val PREVIEW_TURNKEY_PROXY_ID = "9f8e7d6c-5b4a-3210-fedc-ba0987654321"
private const val PREVIEW_PRIVY_APP_ID = "clz1a2b3c4d5e6f7g8h9i0jk"
private const val PREVIEW_PRIVY_CLIENT_ID = "client-WY1a2B3c4D5e6F7g8H9i0J"
private const val PREVIEW_EMAIL = "dev@rain.xyz"
private const val PREVIEW_PHONE = "+15551234567"
private const val PREVIEW_PORTAL_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.portal.session.token"

/** Turnkey with the ids and both contacts filled in; each preview copies the flags it needs. */
private val previewTurnkeyState = HomeUiState(
    mode = WalletMode.Turnkey,
    turnkeyOrgId = PREVIEW_TURNKEY_ORG_ID,
    turnkeyAuthProxyConfigId = PREVIEW_TURNKEY_PROXY_ID,
    turnkeyEmail = PREVIEW_EMAIL,
    turnkeyPhone = PREVIEW_PHONE,
)

/** Privy with its ids and email filled in; each preview copies the flags it needs. */
private val previewPrivyState = HomeUiState(
    mode = WalletMode.Privy,
    privyAppId = PREVIEW_PRIVY_APP_ID,
    privyAppClientId = PREVIEW_PRIVY_CLIENT_ID,
    privyEmail = PREVIEW_EMAIL,
)

@Composable
private fun ProviderCardPreview(state: HomeUiState) {
    RainTheme {
        ProviderCard(state = state, actions = ProviderCardActions.None)
    }
}

@Preview(name = "Portal · no token yet", showBackground = true)
@Composable
private fun PortalCardEmptyPreview() {
    ProviderCardPreview(HomeUiState(mode = WalletMode.Portal))
}

@Preview(name = "Portal · token pasted", showBackground = true)
@Composable
private fun PortalCardReadyPreview() {
    ProviderCardPreview(HomeUiState(mode = WalletMode.Portal, sessionToken = PREVIEW_PORTAL_TOKEN))
}

@Preview(name = "Portal · initialized", showBackground = true)
@Composable
private fun PortalCardInitializedPreview() {
    ProviderCardPreview(
        HomeUiState(mode = WalletMode.Portal, sessionToken = PREVIEW_PORTAL_TOKEN, isInitialized = true),
    )
}

@Preview(name = "Turnkey · email, ready to send", showBackground = true)
@Composable
private fun TurnkeyCardEmailPreview() {
    ProviderCardPreview(previewTurnkeyState)
}

@Preview(name = "Turnkey · phone, ready to send", showBackground = true)
@Composable
private fun TurnkeyCardPhonePreview() {
    ProviderCardPreview(previewTurnkeyState.copy(turnkeyChannel = TurnkeyContactChannel.Phone))
}

@Preview(name = "Turnkey · sending", showBackground = true)
@Composable
private fun TurnkeyCardSendingPreview() {
    ProviderCardPreview(previewTurnkeyState.copy(isLoading = true))
}

@Preview(name = "Turnkey · code sent by email", showBackground = true, heightDp = 700)
@Composable
private fun TurnkeyCardEmailCodeSentPreview() {
    ProviderCardPreview(previewTurnkeyState.copy(turnkeyOtpSent = true, turnkeyOtpCode = "481902"))
}

@Preview(name = "Turnkey · code sent by SMS", showBackground = true, heightDp = 700)
@Composable
private fun TurnkeyCardSmsCodeSentPreview() {
    ProviderCardPreview(
        previewTurnkeyState.copy(turnkeyChannel = TurnkeyContactChannel.Phone, turnkeyOtpSent = true),
    )
}

@Preview(name = "Turnkey · session active", showBackground = true, heightDp = 780)
@Composable
private fun TurnkeyCardSessionActivePreview() {
    ProviderCardPreview(
        previewTurnkeyState.copy(turnkeyOtpSent = true, turnkeyOtpCode = "481902", turnkeySessionActive = true),
    )
}

@Preview(name = "Turnkey · Rain initialized", showBackground = true, heightDp = 780)
@Composable
private fun TurnkeyCardInitializedPreview() {
    ProviderCardPreview(
        previewTurnkeyState.copy(
            turnkeyOtpSent = true,
            turnkeyOtpCode = "481902",
            turnkeySessionActive = true,
            isInitialized = true,
        ),
    )
}

@Preview(name = "Portal · initializing", showBackground = true)
@Composable
private fun PortalCardLoadingPreview() {
    ProviderCardPreview(
        HomeUiState(mode = WalletMode.Portal, sessionToken = PREVIEW_PORTAL_TOKEN, isLoading = true),
    )
}

@Preview(name = "Turnkey · verifying the code", showBackground = true, heightDp = 700)
@Composable
private fun TurnkeyCardVerifyingPreview() {
    ProviderCardPreview(
        previewTurnkeyState.copy(turnkeyOtpSent = true, turnkeyOtpCode = "481902", isLoading = true),
    )
}

@Preview(name = "Privy · ready to send", showBackground = true)
@Composable
private fun PrivyCardReadyPreview() {
    ProviderCardPreview(previewPrivyState)
}

@Preview(name = "Privy · code sent", showBackground = true, heightDp = 700)
@Composable
private fun PrivyCardCodeSentPreview() {
    ProviderCardPreview(previewPrivyState.copy(privyOtpSent = true, privyOtpCode = "620145"))
}

@Preview(name = "Privy · sending", showBackground = true)
@Composable
private fun PrivyCardSendingPreview() {
    ProviderCardPreview(previewPrivyState.copy(isLoading = true))
}

// Turnkey keeps the code step on screen once the session is live; Privy replaces it with the
// Initialize button. The two previews below sit side by side so that divergence is visible.
@Preview(name = "Privy · session active", showBackground = true, heightDp = 620)
@Composable
private fun PrivyCardSessionActivePreview() {
    ProviderCardPreview(previewPrivyState.copy(privyOtpSent = true, privySessionActive = true))
}

@Preview(name = "Privy · Rain initialized", showBackground = true, heightDp = 620)
@Composable
private fun PrivyCardInitializedPreview() {
    ProviderCardPreview(
        previewPrivyState.copy(privyOtpSent = true, privySessionActive = true, isInitialized = true),
    )
}

// endregion
