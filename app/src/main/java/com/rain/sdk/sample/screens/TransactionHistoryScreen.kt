package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.sample.R
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainBadgeTone
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDivider
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainIconButton
import com.rain.sdk.sample.ui.RainLink
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainNote
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSpinner
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTextAction
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainType

@Composable
fun TransactionHistoryScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: TransactionHistoryViewModel = viewModel(factory = TransactionHistoryViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Re-fetch whenever the active chain changes.
    LaunchedEffect(selectedChain) {
        viewModel.fetchTransactions(selectedChain)
    }

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "History", subtitle = "Sent from this wallet on ${selectedChain.displayName}")

        state.errorText?.let { RainErrorPanel(it) }

        if (state.isLoading && state.transactions.isEmpty()) {
            RainPanel {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RainSpinner()
                    RainMuted("Loading transactions…")
                }
            }
        }

        // Empty state. The history is sourced from this wallet's send activities filtered to the
        // selected chain, so it shows only transactions sent through this wallet on this network
        // (no receives, nothing sent outside the provider). Empty is expected for a fresh wallet.
        if (!state.isLoading && state.transactions.isEmpty() && state.errorText == null) {
            RainNote(
                title = "No transactions on ${selectedChain.displayName}",
                body = "History lists transactions sent from this wallet on the selected network. Sends on " +
                    "other chains, or transfers received from someone else, won't appear here.",
            )
        }

        if (state.transactions.isNotEmpty()) {
            RainCard(gap = 0.dp, contentPadding = PaddingValues(horizontal = 24.dp)) {
                state.transactions.forEach { tx ->
                    TransactionRow(tx = tx, walletAddress = state.walletAddress, chain = selectedChain)
                    RainDivider()
                }
                val walletAddress = state.walletAddress
                if (walletAddress != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RainTextAction(
                            text = "View all on ${selectedChain.explorerName}",
                            onClick = { openUrl(context, selectedChain.explorerAddressUrl(walletAddress)) },
                            icon = R.drawable.ic_arrow_up_right,
                        )
                    }
                }
            }
        }

        RainButton(
            text = "Refresh",
            onClick = { viewModel.fetchTransactions(selectedChain) },
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = !state.isLoading,
            loading = state.isLoading,
        )
    }
}

@Composable
private fun TransactionRow(tx: RainTransaction, walletAddress: String?, chain: WalletChain) {
    val context = LocalContext.current

    val isSend = walletAddress?.let { tx.from.equals(it, ignoreCase = true) } ?: false
    val isReceive = walletAddress?.let { tx.to?.equals(it, ignoreCase = true) == true } ?: false
    val kind: Pair<String, RainBadgeTone>? = when {
        isSend && isReceive -> "Self" to RainBadgeTone.Outline
        isSend -> "Sent" to RainBadgeTone.Neutral
        isReceive -> "Received" to RainBadgeTone.Success
        else -> null
    }

    // Solana history rows carry the provider's status id, not an explorer-resolvable signature,
    // so the hash is shown plainly (no link) on Solana.
    val explorerLinkable = !chain.isSolana

    // Formatted like the Balances screen: a clean decimal, no trailing zeros or scientific
    // notation. Only a transfer with no token address is denominated in the native symbol; a
    // token transfer whose symbol is unknown shows the bare amount, identified by the mint below.
    val formattedValue = tx.value?.let { formatPlain(it) }?.takeIf { it != "0" }
    val unit = tx.asset ?: chain.nativeSymbol.takeIf { tx.tokenAddress == null }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RainRow {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (kind != null) RainBadge(kind.first, kind.second)
                if (explorerLinkable) {
                    RainLink(
                        text = shortHash(tx.hash),
                        onClick = { openUrl(context, chain.explorerTxUrl(tx.hash)) },
                        maxLines = 1,
                    )
                } else {
                    Text(shortHash(tx.hash), style = RainType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (formattedValue != null) {
                RainStrong(listOfNotNull(formattedValue, unit).joinToString(" "))
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RainMuted(shortAddress(tx.from))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = "to",
                tint = RainColors.TextMuted,
                modifier = Modifier.size(16.dp),
            )
            RainMuted(shortAddress(tx.to ?: "—"))
        }

        tx.tokenAddress?.let { tokenAddress ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RainMuted("Token ${shortAddress(tokenAddress)}")
                RainIconButton(
                    icon = R.drawable.ic_copy,
                    contentDescription = "Copy token address",
                    onClick = { copyToClipboard(context, "Token address", tokenAddress, "Token address copied") },
                    size = 24.dp,
                    iconSize = 16.dp,
                )
            }
        }
    }
}
