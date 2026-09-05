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

@Composable
fun BalancesScreen(
    innerPadding: PaddingValues,
    rainSdk: RainSdk,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: BalancesViewModel = viewModel(factory = BalancesViewModelFactory(rainSdk, rainClient))
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(selectedChain) {
        viewModel.loadWalletAddresses(selectedChain)
    }

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "Balances", subtitle = selectedChain.displayName)

        // Collateral balances come from the Rain API, not on-chain: tokens are deposited into the
        // user's collateral contract, so the wallet itself won't hold them.
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

            val collateral = state.collateralBalances
            if (collateral.isNotEmpty()) {
                val primary = collateral.first()
                RainAmount(value = formatMoney(primary.balance), unit = primary.symbol)
                RainDivider()
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    collateral.forEach { token ->
                        BalanceRow(
                            title = token.symbol,
                            subtitle = shortAddress(token.address),
                            value = formatMoney(token.balance),
                        )
                    }
                }
            } else if (!state.isCollateralLoading) {
                RainMuted("Every token held in the collateral contract, read from the Rain API.")
            }

            state.collateralError?.let { RainErrorPanel(it) }

            if (collateral.isEmpty()) {
                RainButton(
                    text = "Fetch collateral",
                    onClick = { viewModel.fetchCollateralBalances(selectedChain) },
                    modifier = Modifier.fillMaxWidth(),
                    style = RainButtonStyle.Secondary,
                    enabled = !state.isCollateralLoading,
                    loading = state.isCollateralLoading,
                )
            }
        }

        // The wallet's own holdings, read on-chain: the native token plus every discovered token.
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

            val native = state.nativeBalance
            if (native != null) {
                // Stored as "0.4821 ETH": split the figure from its unit for the headline.
                val nativeValue = native.substringBeforeLast(' ')
                val nativeUnit = native.substringAfterLast(' ', missingDelimiterValue = selectedChain.nativeSymbol)
                RainAmount(value = nativeValue, unit = nativeUnit)
                RainDivider()
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BalanceRow(title = nativeUnit, subtitle = "Native", value = nativeValue)
                    state.walletTokenBalances.forEach { token ->
                        // The unit is always stated: an SPL mint has no on-chain symbol, so an
                        // unregistered token is named by its mint rather than a bare number.
                        BalanceRow(
                            title = token.displayUnit,
                            subtitle = shortAddress(token.address),
                            value = token.formattedBalance,
                        )
                    }
                }
                if (state.walletTokenBalances.isEmpty()) {
                    RainMuted("No ${selectedChain.tokenStandard} tokens with a balance above zero.")
                }
            }

            RainMuted("Every ${selectedChain.tokenStandard} token with a balance above zero is discovered automatically.")

            state.errorMessage?.let { RainErrorPanel(it) }

            if (native == null) {
                RainButton(
                    text = "Fetch balances",
                    onClick = { viewModel.fetchBalances(selectedChain) },
                    modifier = Modifier.fillMaxWidth(),
                    style = RainButtonStyle.Secondary,
                    enabled = !state.isLoading,
                    loading = state.isLoading,
                )
            }
        }

        val busy = state.isLoading || state.isCollateralLoading
        RainButton(
            text = "Refresh",
            onClick = {
                viewModel.fetchCollateralBalances(selectedChain)
                viewModel.fetchBalances(selectedChain)
            },
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = !busy,
            loading = busy,
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
