package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
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
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType
import java.math.BigDecimal

@Composable
fun CollateralWithdrawScreen(
    innerPadding: PaddingValues,
    rainSdk: RainSdk,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: CollateralWithdrawViewModel = viewModel(
        factory = CollateralWithdrawViewModelFactory(rainSdk, rainClient)
    )
) {
    val state by viewModel.state.collectAsState()

    // Re-load whenever the active chain changes so the screen shows that chain's contract.
    LaunchedEffect(selectedChain) {
        viewModel.loadContractInfo(selectedChain)
    }

    CollateralWithdrawContent(
        innerPadding = innerPadding,
        state = state,
        selectedChain = selectedChain,
        onBack = onBack,
        actions = CollateralWithdrawActions(
            onTokenSelected = viewModel::onTokenSelected,
            onRecipientChanged = viewModel::onRecipientChanged,
            onAmountChanged = viewModel::onAmountChanged,
            onEstimateFee = viewModel::estimateFee,
            onPrepareWithdrawal = viewModel::prepareWithdrawal,
            onWithdrawMaximum = viewModel::withdrawMaximum,
            onExecuteWithdraw = viewModel::executeWithdraw,
        ),
    )
}

/** Callbacks the withdraw form raises, so the stateless body can be previewed without a view model. */
@Suppress("LongParameterList") // A bag of callbacks, one per user action; splitting it would only add indirection.
private class CollateralWithdrawActions(
    val onTokenSelected: (Int) -> Unit,
    val onRecipientChanged: (String) -> Unit,
    val onAmountChanged: (String) -> Unit,
    val onEstimateFee: () -> Unit,
    val onPrepareWithdrawal: () -> Unit,
    val onWithdrawMaximum: () -> Unit,
    val onExecuteWithdraw: () -> Unit,
) {
    companion object {
        /** Inert callbacks for previews. */
        val None = CollateralWithdrawActions(
            onTokenSelected = {},
            onRecipientChanged = {},
            onAmountChanged = {},
            onEstimateFee = {},
            onPrepareWithdrawal = {},
            onWithdrawMaximum = {},
            onExecuteWithdraw = {},
        )
    }
}

/** Stateless body of [CollateralWithdrawScreen]. */
@Composable
private fun CollateralWithdrawContent(
    innerPadding: PaddingValues,
    state: CollateralWithdrawUiState,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    actions: CollateralWithdrawActions,
) {
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
                        // Symbol leads; the token's name stays in the descriptor when it adds
                        // information ("USD Coin"), as the previous layout showed both.
                        val name = option.name.takeIf { it.isNotBlank() && it != option.symbol && option.symbol.isNotBlank() }
                        RainOptionRow(
                            title = option.symbol.ifBlank { option.name },
                            subtitle = listOfNotNull(name, "Balance ${option.balanceDisplay}").joinToString(" · "),
                            selected = index == state.selectedTokenIndex,
                            onClick = { actions.onTokenSelected(index) },
                            enabled = !state.isWithdrawing,
                        )
                    }
                }
            }

            RainField(
                label = "Recipient address",
                value = state.recipientAddress,
                onValueChange = actions.onRecipientChanged,
                enabled = !state.isWithdrawing,
            )

            RainField(
                label = "Amount to withdraw",
                value = state.amount,
                onValueChange = actions.onAmountChanged,
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
                        onClick = actions.onEstimateFee,
                        modifier = Modifier.weight(1f),
                        style = RainButtonStyle.Secondary,
                        // EVM only: the SDK rejects a Solana chain id for fee estimation.
                        enabled = state.isAmountValid && !state.isWithdrawing && !state.isSolanaContract,
                    )
                    RainButton(
                        text = "Prepare only",
                        onClick = actions.onPrepareWithdrawal,
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
                        onClick = actions.onWithdrawMaximum,
                        modifier = Modifier.weight(1f),
                        style = RainButtonStyle.Secondary,
                        height = 48.dp,
                        enabled = !state.isWithdrawing && (token?.balance?.signum() ?: 0) > 0,
                    )
                    RainButton(
                        text = "Withdraw",
                        onClick = actions.onExecuteWithdraw,
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

// region Previews

private const val PREVIEW_WALLET = "0x1234567890abcdef1234567890abcdef12345678"
private const val PREVIEW_PROXY = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
private const val PREVIEW_TX_HASH = "0x7d2f9b1c4e8a6d3f5b9c2e1a7f4d8b6c3e9a1f5d7b2c4e6a8f1d3b5c7e9a2f4c"

private val previewTokens = listOf(
    WithdrawTokenOption(
        name = "USD Coin",
        symbol = "USDC",
        address = WalletChain.BASE_SEPOLIA.defaultTokenAddress,
        decimals = 6,
        balance = BigDecimal("1250.50"),
    ),
    WithdrawTokenOption(
        name = "Wrapped Ether",
        symbol = "WETH",
        address = "0x4200000000000000000000000000000000000006",
        decimals = 18,
        balance = BigDecimal("0.25"),
    ),
)

/** A loaded EVM contract with USDC selected, the base every non-empty preview builds on. */
private val previewLoadedState = CollateralWithdrawUiState(
    walletAddress = PREVIEW_WALLET,
    recipientAddress = PREVIEW_WALLET,
    proxyAddress = PREVIEW_PROXY,
    chainId = WalletChain.BASE_SEPOLIA.chainId,
    availableTokens = previewTokens,
    selectedTokenIndex = 0,
    amount = "100",
)

@Composable
private fun CollateralWithdrawPreview(
    state: CollateralWithdrawUiState,
    selectedChain: WalletChain = WalletChain.BASE_SEPOLIA,
) {
    RainTheme {
        CollateralWithdrawContent(
            innerPadding = PaddingValues(),
            state = state,
            selectedChain = selectedChain,
            onBack = {},
            actions = CollateralWithdrawActions.None,
        )
    }
}

@Preview(name = "Loading contract", showBackground = true)
@Composable
private fun CollateralWithdrawLoadingPreview() {
    CollateralWithdrawPreview(CollateralWithdrawUiState(isLoadingContract = true))
}

@Preview(name = "Loaded · EVM", showBackground = true, heightDp = 900)
@Composable
private fun CollateralWithdrawLoadedPreview() {
    CollateralWithdrawPreview(previewLoadedState)
}

@Preview(name = "Amount over balance", showBackground = true, heightDp = 900)
@Composable
private fun CollateralWithdrawOverBalancePreview() {
    CollateralWithdrawPreview(previewLoadedState.copy(selectedTokenIndex = 1, amount = "5"))
}

@Preview(name = "Withdrawing", showBackground = true, heightDp = 900)
@Composable
private fun CollateralWithdrawWithdrawingPreview() {
    CollateralWithdrawPreview(previewLoadedState.copy(isWithdrawing = true))
}

@Preview(name = "Dry run · fee and prepared tx", showBackground = true, heightDp = 1200)
@Composable
private fun CollateralWithdrawDryRunPreview() {
    CollateralWithdrawPreview(
        previewLoadedState.copy(
            estimatedFee = "0.000042 ETH",
            preparedWithdrawal = "withdrawAsset(USDC, 100.00) to ${shortAddress(PREVIEW_WALLET)} · nonce 7 · " +
                "admin signature cached",
        ),
    )
}

@Preview(name = "Withdrawal sent", showBackground = true, heightDp = 1100)
@Composable
private fun CollateralWithdrawSentPreview() {
    CollateralWithdrawPreview(previewLoadedState.copy(amount = "", withdrawResult = PREVIEW_TX_HASH))
}

@Preview(name = "Solana contract · prepared", showBackground = true, heightDp = 1100)
@Composable
private fun CollateralWithdrawSolanaPreview() {
    CollateralWithdrawPreview(
        previewLoadedState.copy(
            walletAddress = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV",
            recipientAddress = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV",
            proxyAddress = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin",
            chainId = WalletChain.SOLANA.chainId,
            isSolanaContract = true,
            availableTokens = previewTokens.take(1).map { it.copy(address = WalletChain.SOLANA.defaultTokenAddress) },
            preparedWithdrawal = "Transfer 100.00 USDC · blockhash 4vJ9…Kp2Q · 1 signer",
        ),
        selectedChain = WalletChain.SOLANA,
    )
}

@Preview(name = "No collateral tokens", showBackground = true)
@Composable
private fun CollateralWithdrawEmptyPreview() {
    CollateralWithdrawPreview(
        CollateralWithdrawUiState(
            walletAddress = PREVIEW_WALLET,
            proxyAddress = PREVIEW_PROXY,
            chainId = WalletChain.BASE_SEPOLIA.chainId,
        ),
    )
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun CollateralWithdrawErrorPreview() {
    CollateralWithdrawPreview(
        CollateralWithdrawUiState(errorText = "No collateral contract on ${WalletChain.SOLANA.displayName}"),
        selectedChain = WalletChain.SOLANA,
    )
}

// endregion
