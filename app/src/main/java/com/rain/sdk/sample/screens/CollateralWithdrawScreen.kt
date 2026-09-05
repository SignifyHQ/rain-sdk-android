package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainNote
import com.rain.sdk.sample.ui.RainOptionRow
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSpinner
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainType

@Composable
fun CollateralWithdrawScreen(
    innerPadding: PaddingValues,
    rainSdk: RainSdk,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: CollateralWithdrawViewModel = viewModel(factory = CollateralWithdrawViewModelFactory(rainSdk, rainClient))
) {
    val state by viewModel.state.collectAsState()

    // Re-load whenever the active chain changes so the screen shows that chain's contract.
    LaunchedEffect(selectedChain) {
        viewModel.loadContractInfo(selectedChain)
    }

    // The contract lives on its own chain (Base Sepolia for EVM), which may differ from the
    // selected one; explorer links and the descriptor follow the contract.
    val contractChain = WalletChain.entries.firstOrNull { it.chainId == state.chainId }
    val token = state.selectedToken

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(
            title = "Withdraw collateral",
            subtitle = contractChain?.let { "Contract on ${it.displayName}" } ?: selectedChain.displayName,
        )

        if (state.isLoadingContract) {
            RainPanel { RainMuted("Loading contract…") }
        }

        state.errorText?.let { RainErrorPanel(it) }

        if (state.availableTokens.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RainLabel("Token")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableTokens.forEachIndexed { index, option ->
                        RainOptionRow(
                            title = option.symbol.ifBlank { option.name },
                            subtitle = "Balance ${option.balanceDisplay}",
                            selected = index == state.selectedTokenIndex,
                            onClick = { viewModel.onTokenSelected(index) },
                            enabled = !state.isWithdrawing,
                        )
                    }
                }
            }

            RainField(
                label = "Recipient address",
                value = state.recipientAddress,
                onValueChange = { viewModel.onRecipientChanged(it) },
                enabled = !state.isWithdrawing,
            )

            RainField(
                label = "Amount to withdraw",
                value = state.amount,
                onValueChange = { viewModel.onAmountChanged(it) },
                placeholder = "0.00",
                enabled = !state.isWithdrawing,
                keyboardType = KeyboardType.Decimal,
                helper = token?.let {
                    if (state.isAmountOverBalance) {
                        "Amount exceeds the available balance (${it.balanceDisplay} ${it.symbol})"
                    } else {
                        "Available: ${it.balanceDisplay} ${it.symbol}"
                    }
                },
                helperIsError = state.isAmountOverBalance,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Dry-run actions: both build the withdrawal exactly as "Withdraw" would, signing
                // EIP-712 and reading the collateral's admin set, but broadcast nothing.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RainButton(
                        text = "Estimate fee",
                        onClick = { viewModel.estimateFee() },
                        modifier = Modifier.weight(1f),
                        style = RainButtonStyle.Secondary,
                        // EVM only: the SDK rejects a Solana chain id for fee estimation.
                        enabled = state.isAmountValid && !state.isWithdrawing && !state.isSolanaContract,
                    )
                    RainButton(
                        text = "Prepare only",
                        onClick = { viewModel.prepareWithdrawal() },
                        modifier = Modifier.weight(1f),
                        style = RainButtonStyle.Secondary,
                        enabled = state.isAmountValid && !state.isWithdrawing,
                    )
                }
                // Gas estimation also happens as part of the withdraw itself, so "Estimate fee"
                // above is optional.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RainButton(
                        text = "Withdraw maximum",
                        onClick = { viewModel.withdrawMaximum() },
                        modifier = Modifier.weight(1f),
                        style = RainButtonStyle.Secondary,
                        height = 48.dp,
                        enabled = !state.isWithdrawing && (token?.balance?.signum() ?: 0) > 0,
                    )
                    RainButton(
                        text = "Withdraw",
                        onClick = { viewModel.executeWithdraw() },
                        modifier = Modifier.weight(1f),
                        enabled = state.isAmountValid && !state.isWithdrawing,
                    )
                }
            }

            if (state.isWithdrawing) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RainSpinner()
                    RainMuted("Fetching the admin signature and building the withdrawal…")
                }
            }

            // Dry-run results: neither of these broadcast anything.
            state.estimatedFee?.let { fee ->
                RainCard(gap = 8.dp) {
                    RainRow {
                        RainStrong("Estimated fee", Modifier.weight(1f))
                        RainStrong(fee)
                    }
                    RainMuted("Dry run. Nothing was broadcast.")
                }
            }

            state.preparedWithdrawal?.let { summary ->
                RainCard(gap = 8.dp) {
                    RainRow {
                        RainStrong("Prepared withdrawal", Modifier.weight(1f))
                        RainBadge("Not broadcast")
                    }
                    Text(summary, style = RainType.Body)
                    RainMuted(
                        if (state.isSolanaContract) {
                            "A Solana blockhash is valid for about 60 to 90 seconds. Submit promptly or prepare again."
                        } else {
                            "Submit this transaction yourself, or tap Withdraw to let the SDK broadcast it."
                        },
                    )
                }
            }

            state.withdrawResult?.let { txHash ->
                val chain = contractChain ?: WalletChain.BASE_SEPOLIA
                TransactionResultCard(
                    title = "Withdrawal sent",
                    hash = txHash,
                    explorerUrl = chain.explorerTxUrl(txHash),
                    explorerName = chain.explorerName,
                )
            }
        } else if (!state.isLoadingContract && state.errorText == null && state.proxyAddress.isNotEmpty()) {
            RainNote(
                title = "No collateral tokens",
                body = "The collateral contract on ${contractChain?.displayName ?: selectedChain.displayName} " +
                    "holds no tokens to withdraw.",
            )
        }
    }
}
