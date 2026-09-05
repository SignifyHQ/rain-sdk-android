package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSegmentedControl
import com.rain.sdk.sample.ui.RainTitleBlock

@Composable
fun SendTokensScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: SendTokensViewModel = viewModel(factory = SendTokensViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()
    val isTokenSend = state.isTokenMode

    // Address defaults differ per chain (contract vs mint), so re-seed the form on a switch;
    // the ViewModel no-ops when the chain is unchanged.
    LaunchedEffect(selectedChain) { viewModel.onChainChanged(selectedChain) }

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "Send tokens", subtitle = selectedChain.displayName)

        // Every chain supports both a native and a token transfer.
        RainSegmentedControl(
            options = listOf("Native (${selectedChain.nativeSymbol})", "${selectedChain.tokenStandard} token"),
            selectedIndex = if (isTokenSend) 1 else 0,
            onSelected = { viewModel.onSendModeChanged(it == 1) },
            enabled = !state.isSending,
        )

        RainCard {
            if (isTokenSend) {
                RainField(
                    label = selectedChain.tokenAddressLabel,
                    value = state.contractAddress,
                    onValueChange = { viewModel.onContractAddressChanged(it) },
                    enabled = !state.isSending,
                    helper = if (selectedChain.isSolana) {
                        "Decimals come from the mint. If the recipient has no account for this token, " +
                            "one is created and you pay about 0.002 SOL in rent."
                    } else {
                        "Decimals are resolved automatically by the SDK."
                    },
                )
            }
            RainField(
                label = "Recipient address",
                value = state.recipientAddress,
                onValueChange = { viewModel.onRecipientChanged(it) },
                enabled = !state.isSending,
            )
            RainField(
                label = if (isTokenSend) "Amount (token units)" else "Amount (${selectedChain.nativeSymbol})",
                value = state.amount,
                onValueChange = { viewModel.onAmountChanged(it) },
                enabled = !state.isSending,
                keyboardType = KeyboardType.Decimal,
            )
        }

        state.errorText?.let { RainErrorPanel(it) }

        RainButton(
            text = when {
                state.isSending -> "Sending"
                isTokenSend -> "Send ${selectedChain.tokenStandard}"
                else -> "Send ${selectedChain.nativeSymbol}"
            },
            onClick = {
                if (isTokenSend) viewModel.sendTokenTransfer(selectedChain)
                else viewModel.sendNative(selectedChain)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSending,
            loading = state.isSending,
        )

        state.txHash?.let { txHash ->
            TransactionResultCard(
                title = "Transaction sent",
                hash = txHash,
                explorerUrl = selectedChain.explorerTxUrl(txHash),
                explorerName = selectedChain.explorerName,
            )
        }
    }
}
