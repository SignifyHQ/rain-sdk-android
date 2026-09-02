package com.rain.sdk.sample.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.sample.RainSampleApp
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.Screen
import com.rain.sdk.sample.SessionHealth
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.WalletSessionStatus

data class FeatureAction(
    val emoji: String,
    val label: String,
    val screen: Screen
)

private val featureActions = listOf(
    FeatureAction("💳", "Wallet & QR", Screen.WalletInfo),
    FeatureAction("💰", "Balances", Screen.Balances),
    FeatureAction("📤", "Send Tokens", Screen.SendTokens),
    FeatureAction("🏦", "Withdraw", Screen.CollateralWithdraw),
    FeatureAction("🔐", "Auth Pull", Screen.AuthPull),
    FeatureAction("📜", "History", Screen.TransactionHistory),
)

@Composable
fun HomeScreen(
    innerPadding: PaddingValues,
    session: RainSession,
    selectedChain: WalletChain,
    onChainSelected: (WalletChain) -> Unit,
    onNavigate: (Screen) -> Unit,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(LocalContext.current.applicationContext as RainSampleApp)
    )
) {
    val state by viewModel.state.collectAsState()
    val application = LocalContext.current.applicationContext as Application

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Rain SDK Showcase",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Locked while a provider is resolving or resolved, so the session card always describes
        // `mode`. Not gated on sessionStatus: a failed Initialize leaves that set and would lock
        // the other providers until process death.
        ModeSelector(
            mode = state.mode,
            enabled = !state.isInitialized && !state.isLoading,
            onModeChanged = viewModel::onModeChanged
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Rain API credentials are independent of the wallet provider (Portal/Turnkey):
        // they authenticate contract/signature calls to the Rain dev API, so they live in
        // their own card shown in both modes.
        RainApiSection(
            state = state,
            onRainApiKeyChanged = viewModel::onRainApiKeyChanged,
            onUserIdChanged = viewModel::onUserIdChanged
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (state.mode) {
            WalletMode.Portal -> ConfigurationSection(
                state = state,
                onSessionTokenChanged = viewModel::onSessionTokenChanged,
                onInitializeSdk = viewModel::initializeSdk
            )
            WalletMode.Turnkey -> TurnkeySection(
                state = state,
                onOrgIdChanged = viewModel::onTurnkeyOrgIdChanged,
                onAuthProxyConfigIdChanged = viewModel::onTurnkeyAuthProxyConfigIdChanged,
                onEmailChanged = viewModel::onTurnkeyEmailChanged,
                onOtpCodeChanged = viewModel::onTurnkeyOtpCodeChanged,
                onSendOtp = { viewModel.sendTurnkeyOtp(application) },
                onVerifyOtp = viewModel::verifyTurnkeyOtp,
                onInitializeRain = viewModel::initializeRainWithTurnkey
            )
            WalletMode.Privy -> PrivySection(
                state = state,
                onAppIdChanged = viewModel::onPrivyAppIdChanged,
                onAppClientIdChanged = viewModel::onPrivyAppClientIdChanged,
                onEmailChanged = viewModel::onPrivyEmailChanged,
                onOtpCodeChanged = viewModel::onPrivyOtpCodeChanged,
                onSendOtp = { viewModel.sendPrivyOtp(application) },
                onVerifyOtp = viewModel::verifyPrivyOtp,
                onInitializeRain = viewModel::initializeRainWithPrivy
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stays visible when the session is dead so the hidden feature grid is explained.
        state.sessionStatus?.let { status ->
            SessionSection(
                status = status,
                mode = state.mode,
                isLoading = state.isLoading,
                replacementPortalToken = state.replacementPortalToken,
                onReplacementPortalTokenChanged = viewModel::onReplacementPortalTokenChanged,
                onRefreshSession = viewModel::refreshSession,
                onUpdatePortalToken = viewModel::updatePortalSessionToken
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        val sessionUsable = state.sessionStatus?.health != SessionHealth.Dead
        if (state.isRecovered && sessionUsable) {
            // Turnkey and Privy hold a Solana account; Portal is EVM-only. Force the selection
            // back to an EVM chain so Portal never reads/signs on Solana.
            LaunchedEffect(state.mode, selectedChain) {
                if (state.mode == WalletMode.Portal && selectedChain.isSolana) {
                    onChainSelected(WalletChain.EVM)
                }
            }
            ChainSelector(
                mode = state.mode,
                selectedChain = selectedChain,
                onChainSelected = onChainSelected
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "SDK Features",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            FeatureGrid(
                actions = featureActions,
                onActionClick = onNavigate
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (state.isRecovered) {
            Button(
                onClick = { viewModel.clearSession() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Clear Session")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = "Status: ${state.statusText}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/** `sessionState`, `refreshSession()` and, for Portal, `updateSessionToken()`. */
@Composable
private fun SessionSection(
    status: WalletSessionStatus,
    mode: WalletMode,
    isLoading: Boolean,
    replacementPortalToken: String,
    onReplacementPortalTokenChanged: (String) -> Unit,
    onRefreshSession: () -> Unit,
    onUpdatePortalToken: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Wallet Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(status.health.indicatorColor(), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            status.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Portal's refresh goes through onSessionTokenNeeded, which needs a replacement token.
            val canRefresh = !isLoading &&
                (mode != WalletMode.Portal || replacementPortalToken.isNotBlank())
            OutlinedButton(
                onClick = onRefreshSession,
                enabled = canRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh session")
            }

            if (mode == WalletMode.Portal) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Replacement token: \"Update token\" installs it now (updateSessionToken); " +
                        "Refresh and any rejected call take it via onSessionTokenNeeded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = replacementPortalToken,
                    onValueChange = onReplacementPortalTokenChanged,
                    label = { Text("Replacement session token") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true
                )
                OutlinedButton(
                    onClick = onUpdatePortalToken,
                    enabled = replacementPortalToken.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Update token")
                }
            }
        }
    }
}

private val HealthyGreen = Color(0xFF2E7D32)
private val TransitionalAmber = Color(0xFFF9A825)

@Composable
private fun SessionHealth.indicatorColor(): Color = when (this) {
    SessionHealth.Healthy -> HealthyGreen
    SessionHealth.Transitional -> TransitionalAmber
    SessionHealth.Dead -> MaterialTheme.colorScheme.error
    SessionHealth.Unknown -> MaterialTheme.colorScheme.outline
}

@Composable
private fun ChainSelector(
    mode: WalletMode,
    selectedChain: WalletChain,
    onChainSelected: (WalletChain) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Solana for Turnkey and Privy; Portal is EVM-only.
    val chains = WalletChain.selectable.filter { mode != WalletMode.Portal || !it.isSolana }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Active wallet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedChain.displayName,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
                Text(text = "▾")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                chains.forEach { chain ->
                    DropdownMenuItem(
                        text = { Text(chain.displayName) },
                        onClick = {
                            onChainSelected(chain)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: WalletMode,
    enabled: Boolean,
    onModeChanged: (WalletMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = mode == WalletMode.Portal,
            onClick = { if (enabled) onModeChanged(WalletMode.Portal) },
            label = { Text("Portal MPC") },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = mode == WalletMode.Turnkey,
            onClick = { if (enabled) onModeChanged(WalletMode.Turnkey) },
            label = { Text("Turnkey") },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = mode == WalletMode.Privy,
            onClick = { if (enabled) onModeChanged(WalletMode.Privy) },
            label = { Text("Privy") },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConfigurationSection(
    state: HomeUiState,
    onSessionTokenChanged: (String) -> Unit,
    onInitializeSdk: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Portal Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = state.sessionToken,
                onValueChange = onSessionTokenChanged,
                label = { Text("Portal Session Token") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true
            )

            Button(
                onClick = onInitializeSdk,
                enabled = state.sessionToken.isNotBlank() && !state.isInitialized,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (state.isInitialized) "✅ SDK Initialized" else "Initialize SDK"
                )
            }
        }
    }
}

@Composable
private fun RainApiSection(
    state: HomeUiState,
    onRainApiKeyChanged: (String) -> Unit,
    onUserIdChanged: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Rain API Credentials",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = state.rainApiKey,
                onValueChange = onRainApiKeyChanged,
                label = { Text("Rain Api-Key") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = state.userId,
                onValueChange = onUserIdChanged,
                label = { Text("Rain User ID") },
                modifier = Modifier
                    .fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

@Composable
private fun TurnkeySection(
    state: HomeUiState,
    onOrgIdChanged: (String) -> Unit,
    onAuthProxyConfigIdChanged: (String) -> Unit,
    onEmailChanged: (String) -> Unit,
    onOtpCodeChanged: (String) -> Unit,
    onSendOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onInitializeRain: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Turnkey Configuration (Email OTP)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = state.turnkeyOrgId,
                onValueChange = onOrgIdChanged,
                label = { Text("Parent Organization ID") },
                enabled = state.turnkeyOtpId == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = state.turnkeyAuthProxyConfigId,
                onValueChange = onAuthProxyConfigIdChanged,
                label = { Text("Auth Proxy Config ID") },
                enabled = state.turnkeyOtpId == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = state.turnkeyEmail,
                onValueChange = onEmailChanged,
                label = { Text("Email") },
                enabled = state.turnkeyOtpId == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true
            )

            Button(
                onClick = onSendOtp,
                enabled = state.turnkeyOrgId.isNotBlank() &&
                    state.turnkeyAuthProxyConfigId.isNotBlank() &&
                    state.turnkeyEmail.isNotBlank() &&
                    !state.isLoading &&
                    state.turnkeyOtpId == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.turnkeyOtpId != null) "OTP sent" else "Init Turnkey & Send OTP")
            }

            if (state.turnkeyOtpId != null) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.turnkeyOtpCode,
                    onValueChange = onOtpCodeChanged,
                    label = { Text("OTP Code") },
                    enabled = !state.turnkeySessionActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true
                )

                Button(
                    onClick = onVerifyOtp,
                    enabled = state.turnkeyOtpCode.isNotBlank() &&
                        !state.isLoading &&
                        !state.turnkeySessionActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.turnkeySessionActive) "✅ Session active" else "Verify & Log In"
                    )
                }
            }

            if (state.turnkeySessionActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onInitializeRain,
                    enabled = !state.isLoading && !state.isInitialized,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.isInitialized) "✅ Rain Initialized" else "Initialize Rain w/ Turnkey"
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivySection(
    state: HomeUiState,
    onAppIdChanged: (String) -> Unit,
    onAppClientIdChanged: (String) -> Unit,
    onEmailChanged: (String) -> Unit,
    onOtpCodeChanged: (String) -> Unit,
    onSendOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onInitializeRain: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Privy Configuration (Email OTP)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = state.privyAppId,
                onValueChange = onAppIdChanged,
                label = { Text("Privy App ID") },
                enabled = !state.privyOtpSent && !state.privySessionActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = state.privyAppClientId,
                onValueChange = onAppClientIdChanged,
                label = { Text("Privy App Client ID") },
                enabled = !state.privyOtpSent && !state.privySessionActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = state.privyEmail,
                onValueChange = onEmailChanged,
                label = { Text("Email") },
                enabled = !state.privyOtpSent && !state.privySessionActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true
            )

            Button(
                onClick = onSendOtp,
                enabled = state.privyAppId.isNotBlank() &&
                    state.privyAppClientId.isNotBlank() &&
                    state.privyEmail.isNotBlank() &&
                    !state.isLoading &&
                    !state.privyOtpSent &&
                    !state.privySessionActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.privyOtpSent) "OTP sent" else "Init Privy & Send OTP")
            }

            if (state.privyOtpSent && !state.privySessionActive) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.privyOtpCode,
                    onValueChange = onOtpCodeChanged,
                    label = { Text("OTP Code") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true
                )

                Button(
                    onClick = onVerifyOtp,
                    enabled = state.privyOtpCode.isNotBlank() && !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Verify & Log In")
                }
            }

            if (state.privySessionActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onInitializeRain,
                    enabled = !state.isLoading && !state.isInitialized,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.isInitialized) "✅ Rain Initialized" else "Initialize Rain w/ Privy")
                }
            }
        }
    }
}

@Composable
private fun FeatureGrid(
    actions: List<FeatureAction>,
    onActionClick: (Screen) -> Unit
) {
    val chunked = actions.chunked(2)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        chunked.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { action ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onActionClick(action.screen) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = action.emoji,
                                fontSize = 32.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                if (row.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
