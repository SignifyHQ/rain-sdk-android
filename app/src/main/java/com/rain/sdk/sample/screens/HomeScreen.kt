package com.rain.sdk.sample.screens

import android.app.Application
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.sample.R
import com.rain.sdk.sample.RainSampleApp
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.SampleEnvironment
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.Screen
import com.rain.sdk.sample.SessionHealth
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.WalletSessionStatus
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDropdownField
import com.rain.sdk.sample.ui.RainFeatureTile
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainHomeHeader
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSegmentedControl
import com.rain.sdk.sample.ui.RainStatusDot
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainRadius
import com.rain.sdk.sample.ui.theme.RainType

data class FeatureAction(
    @DrawableRes val icon: Int,
    val label: String,
    val screen: Screen,
)

private val featureActions = listOf(
    FeatureAction(R.drawable.ic_tile_wallet, "Wallet & QR", Screen.WalletInfo),
    FeatureAction(R.drawable.ic_tile_coin, "Balances", Screen.Balances),
    FeatureAction(R.drawable.ic_tile_transaction, "Send tokens", Screen.SendTokens),
    FeatureAction(R.drawable.ic_tile_bank, "Withdraw", Screen.CollateralWithdraw),
    FeatureAction(R.drawable.ic_tile_secure, "Auth pull", Screen.AuthPull),
    FeatureAction(R.drawable.ic_tile_time, "History", Screen.TransactionHistory),
)

/** Session details up to this length sit beside the headline; longer ones wrap below it. */
private const val INLINE_DETAIL_MAX_CHARS = 24

internal val WalletMode.displayName: String
    get() = when (this) {
        WalletMode.Portal -> "Portal MPC"
        WalletMode.Turnkey -> "Turnkey"
        WalletMode.Privy -> "Privy"
    }

/** "Turnkey · dev@rain.xyz" once connected; the bare provider name where there is no account. */
private fun HomeUiState.connectedSubtitle(): String {
    val account = when (mode) {
        WalletMode.Turnkey -> when (turnkeyChannel) {
            TurnkeyContactChannel.Email -> turnkeyEmail
            // The header shows up in screenshots, so a phone number is masked.
            TurnkeyContactChannel.Phone -> if (turnkeyPhone.isBlank()) "" else SampleLog.maskPhone(turnkeyPhone)
        }
        WalletMode.Privy -> privyEmail
        WalletMode.Portal -> ""
    }.trim()
    return if (account.isBlank()) mode.displayName else "${mode.displayName} · $account"
}

@Suppress("LongParameterList") // Navigation entry point: the NavHost injects the session, chain, and callbacks.
@Composable
fun HomeScreen(
    innerPadding: PaddingValues,
    session: RainSession,
    selectedChain: WalletChain,
    onChainSelected: (WalletChain) -> Unit,
    onNavigate: (Screen) -> Unit,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(LocalContext.current.applicationContext as RainSampleApp),
    ),
) {
    val state by viewModel.state.collectAsState()
    val application = LocalContext.current.applicationContext as Application
    val connected = state.isRecovered
    val sessionUsable = state.sessionStatus?.health != SessionHealth.Dead

    RainScreen(innerPadding) {
        RainHomeHeader(environmentLabel = SampleEnvironment.displayName)

        RainTitleBlock(
            title = "SDK sample",
            subtitle = if (connected) state.connectedSubtitle() else "Connect a wallet provider to start.",
        )

        if (!connected) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RainLabel("Wallet provider")
                // Locked while a provider is resolving or resolved, so the session card always
                // describes `mode`. Not gated on sessionStatus: a failed Initialize leaves that set
                // and would lock the other providers until process death.
                RainSegmentedControl(
                    options = WalletMode.entries.map { it.displayName },
                    selectedIndex = state.mode.ordinal,
                    onSelected = { viewModel.onModeChanged(WalletMode.entries[it]) },
                    enabled = !state.isInitialized && !state.isLoading,
                )
            }

            // Rain API credentials are independent of the wallet provider: they authenticate
            // contract/signature calls to the Rain dev API, so they live in their own card shown
            // for every provider.
            RainApiCard(state, viewModel)
            ProviderCard(state = state, viewModel = viewModel, application = application)
        }

        // Stays visible when the session is dead so the hidden feature grid is explained.
        state.sessionStatus?.let { status ->
            SessionCard(status = status, state = state, viewModel = viewModel)
        }

        if (connected && sessionUsable) {
            // Turnkey and Privy hold a Solana account; Portal is EVM-only. Force the selection
            // back to an EVM chain so Portal never reads/signs on Solana.
            LaunchedEffect(state.mode, selectedChain) {
                if (state.mode == WalletMode.Portal && selectedChain.isSolana) {
                    onChainSelected(WalletChain.EVM)
                }
            }
            ChainSection(
                mode = state.mode,
                selectedChain = selectedChain,
                onChainSelected = onChainSelected,
            )
            FeatureGrid(actions = featureActions, onActionClick = onNavigate)
        }

        if (connected) {
            // Connection details stay on the screen after connecting, as before the redesign. The
            // Rain API card is still live (configureRainApi), so a wallet can be connected first
            // and the program keys pasted afterwards; the provider card shows what was used and,
            // after a resume, still offers a fresh sign-in.
            RainLabel("Connection")
            RainApiCard(state, viewModel)
            ProviderCard(state = state, viewModel = viewModel, application = application)
            RainButton(
                text = "Clear session",
                onClick = viewModel::clearSession,
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Ghost,
            )
        }

        RainPanel { RainMuted("Status: ${state.statusText}") }
    }
}

@Composable
private fun RainApiCard(state: HomeUiState, viewModel: HomeViewModel) {
    RainCard {
        RainStrong("Rain API credentials")
        RainField(
            label = "Api-Key",
            value = state.rainApiKey,
            onValueChange = viewModel::onRainApiKeyChanged,
            placeholder = "Paste your program Api-Key",
        )
        RainField(
            label = "User ID",
            value = state.userId,
            onValueChange = viewModel::onUserIdChanged,
            placeholder = "Rain user ID",
        )
    }
}

/** `sessionState`, `refreshSession()` and, for Portal, `updateSessionToken()`. */
@Composable
private fun SessionCard(status: WalletSessionStatus, state: HomeUiState, viewModel: HomeViewModel) {
    // Short details sit beside the headline, as in the design; longer ones drop below it.
    val detail = status.detail
    val detailInline = detail != null && detail.length <= INLINE_DETAIL_MAX_CHARS
    RainCard {
        RainRow {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RainStatusDot(status.health.indicatorColor())
                RainStrong(status.label)
            }
            if (detail != null && detailInline) RainMuted(detail)
        }
        if (detail != null && !detailInline) RainMuted(detail)

        // Portal's refresh goes through onSessionTokenNeeded, which needs a replacement token.
        val canRefresh = !state.isLoading &&
            (state.mode != WalletMode.Portal || state.replacementPortalToken.isNotBlank())
        RainButton(
            text = "Refresh session",
            onClick = viewModel::refreshSession,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = canRefresh,
        )

        if (state.mode == WalletMode.Portal) {
            RainMuted(
                "Update token installs the replacement now (updateSessionToken). Refresh and any " +
                    "rejected call take it through onSessionTokenNeeded.",
            )
            RainField(
                label = "Replacement session token",
                value = state.replacementPortalToken,
                onValueChange = viewModel::onReplacementPortalTokenChanged,
                placeholder = "Paste a new Portal session token",
            )
            RainButton(
                text = "Update token",
                onClick = viewModel::updatePortalSessionToken,
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Secondary,
                enabled = state.replacementPortalToken.isNotBlank() && !state.isLoading,
            )
        }
    }
}

private fun SessionHealth.indicatorColor(): Color = when (this) {
    SessionHealth.Healthy -> RainColors.Success
    SessionHealth.Transitional -> RainColors.Warn
    SessionHealth.Dead -> RainColors.Danger
    SessionHealth.Unknown -> RainColors.TextSubtle
}

@Composable
private fun ChainSection(
    mode: WalletMode,
    selectedChain: WalletChain,
    onChainSelected: (WalletChain) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Solana for Turnkey and Privy; Portal is EVM-only.
    val chains = WalletChain.selectable.filter { mode != WalletMode.Portal || !it.isSolana }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RainLabel("Active wallet")
        Box(modifier = Modifier.fillMaxWidth()) {
            RainDropdownField(text = selectedChain.displayName, onClick = { expanded = true })
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(RainRadius.Small),
                containerColor = RainColors.Surface,
                border = BorderStroke(1.dp, RainColors.Border),
            ) {
                chains.forEach { chain ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                chain.displayName,
                                style = if (chain == selectedChain) RainType.Strong else RainType.Body,
                            )
                        },
                        onClick = {
                            onChainSelected(chain)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureGrid(
    actions: List<FeatureAction>,
    onActionClick: (Screen) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RainLabel("Features")
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { action ->
                    RainFeatureTile(
                        icon = action.icon,
                        label = action.label,
                        onClick = { onActionClick(action.screen) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
