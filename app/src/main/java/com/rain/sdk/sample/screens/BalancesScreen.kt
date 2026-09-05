package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainAmount
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDivider
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTitleBlock

@Suppress("LongParameterList") // Navigation entry point: the NavHost injects the session, chain, and callbacks.
@Composable
fun BalancesScreen(
    innerPadding: PaddingValues,
    rainSdk: RainSdk,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: BalancesViewModel = viewModel(factory = BalancesViewModelFactory(rainSdk, rainClient)),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(selectedChain) {
        viewModel.loadWalletAddresses(selectedChain)
    }

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "Balances", subtitle = selectedChain.displayName)
        CollateralCard(state = state, onFetch = { viewModel.fetchCollateralBalances(selectedChain) })
        WalletCard(state = state, chain = selectedChain, onFetch = { viewModel.fetchBalances(selectedChain) })
    }
}

/**
 * Collateral balances come from the Rain API, not on-chain: tokens are deposited into the user's
 * collateral contract, so the wallet itself won't hold them.
 */
@Composable
private fun CollateralCard(state: BalancesUiState, onFetch: () -> Unit) {
    val collateral = state.collateralBalances
    RainCard {
        RainRow {
            Column(modifier = Modifier.weight(1f)) {
                RainStrong("Collateral")
                RainMuted(
                    if (state.collateralWalletAddress.isNotEmpty()) {
                        shortAddress(state.collateralWalletAddress)
                    } else {
                        "Rain collateral contract"
                    },
                )
            }
            RainBadge("Rain API")
        }

        if (collateral.isNotEmpty()) {
            val primary = collateral.first()
            RainAmount(value = formatMoney(primary.balance), unit = primary.symbol)
            RainDivider()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                collateral.forEach { token ->
                    val name = token.name.takeIf { it.isNotBlank() && it != token.symbol }
                    BalanceRow(
                        title = token.symbol,
                        subtitle = listOfNotNull(name, shortAddress(token.address)).joinToString(" · "),
                        value = formatMoney(token.balance),
                    )
                }
            }
        }
        RainMuted("Every token held in the collateral contract, read from the Rain API.")

        state.collateralError?.let { RainErrorPanel(it) }

        RainButton(
            text = if (collateral.isEmpty()) "Fetch" else "Refresh",
            onClick = onFetch,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = !state.isCollateralLoading,
            loading = state.isCollateralLoading,
        )
    }
}

/** The wallet's own holdings, read on-chain: the native token plus every discovered token. */
@Composable
private fun WalletCard(state: BalancesUiState, chain: WalletChain, onFetch: () -> Unit) {
    val native = state.nativeBalance
    RainCard {
        RainRow {
            Column(modifier = Modifier.weight(1f)) {
                RainStrong("Wallet")
                RainMuted(
                    if (state.internalWalletAddress.isNotEmpty()) {
                        shortAddress(state.internalWalletAddress)
                    } else {
                        "Connected wallet"
                    },
                )
            }
            RainBadge("Onchain")
        }

        if (native != null) {
            // Stored as "0.4821 ETH": split the figure from its unit for the headline.
            val nativeValue = native.substringBeforeLast(' ')
            val nativeUnit = native.substringAfterLast(' ', missingDelimiterValue = chain.nativeSymbol)
            RainAmount(value = nativeValue, unit = nativeUnit)
            RainDivider()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BalanceRow(title = nativeUnit, subtitle = "Native", value = nativeValue)
                state.walletTokenBalances.forEach { token ->
                    // The unit is always stated: an SPL mint has no on-chain symbol, so an
                    // unregistered token is named by its mint rather than a bare number.
                    val name = token.name?.takeIf { it.isNotBlank() && it != token.symbol }
                    BalanceRow(
                        title = token.displayUnit,
                        subtitle = listOfNotNull(name, shortAddress(token.address)).joinToString(" · "),
                        value = token.formattedBalance,
                    )
                }
            }
            if (state.walletTokenBalances.isEmpty()) {
                RainMuted("No ${chain.tokenStandard} tokens with a balance above zero.")
            }
        }

        RainMuted(
            "Native ${chain.nativeSymbol} plus every ${chain.tokenStandard} token with a balance above zero, " +
                "discovered automatically.",
        )

        state.errorMessage?.let { RainErrorPanel(it) }

        RainButton(
            text = if (native == null) "Fetch" else "Refresh",
            onClick = onFetch,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = !state.isLoading,
            loading = state.isLoading,
        )
    }
}

@Composable
private fun BalanceRow(title: String, subtitle: String?, value: String) {
    RainRow {
        Column(modifier = Modifier.weight(1f)) {
            RainStrong(title)
            if (subtitle != null) RainMuted(subtitle)
        }
        RainStrong(value)
    }
}
