package com.rain.sdk.sample.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.sample.WalletChain

@Composable
fun TransactionHistoryScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: TransactionHistoryViewModel = viewModel(factory = TransactionHistoryViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()

    // Re-fetch whenever the active chain changes.
    LaunchedEffect(selectedChain) {
        viewModel.fetchTransactions(selectedChain)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back")
            }
            Text(
                text = "Transaction History",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Refresh button
        Button(
            onClick = { viewModel.fetchTransactions(selectedChain) },
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isLoading) "Loading..." else "🔄 Refresh (${selectedChain.nativeSymbol})")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error
        state.errorText?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Empty state
        if (!state.isLoading && state.transactions.isEmpty() && state.errorText == null) {
            Text(
                text = "No transactions found.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        // Transaction list
        state.transactions.forEach { tx ->
            TransactionCard(tx, state.walletAddress, selectedChain)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TransactionCard(tx: RainTransaction, walletAddress: String?, selectedChain: WalletChain) {
    val context = LocalContext.current

    val isSend = walletAddress?.let { tx.from.equals(it, ignoreCase = true) } ?: false
    val isReceive = walletAddress?.let { tx.to?.equals(it, ignoreCase = true) == true } ?: false
    val badgeStr = when {
        isSend && isReceive -> "SELF"
        isSend -> "SEND"
        isReceive -> "RECEIVE"
        else -> ""
    }
    val badgeColor = when {
        isSend && isReceive -> Color.Gray
        isSend -> Color(0xFFE57373)
        isReceive -> Color(0xFF81C784)
        else -> Color.Transparent
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Hash and Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Tx Hash",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (badgeStr.isNotEmpty()) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(badgeColor)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeStr,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
            }
            // Solana history rows carry the Turnkey status id, not an explorer-resolvable
            // signature, so the hash is shown plainly (no link) on Solana.
            val explorerLinkable = !selectedChain.isSolana
            Text(
                text = truncateHash(tx.hash),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (explorerLinkable) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (explorerLinkable) TextDecoration.Underline else null,
                maxLines = 1,
                modifier = if (explorerLinkable) {
                    Modifier.clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(selectedChain.explorerTxUrl(tx.hash)))
                        )
                    }
                } else Modifier
            )

            Spacer(modifier = Modifier.height(4.dp))

            // From → To
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "From",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = truncateAddress(tx.from),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("→", style = MaterialTheme.typography.bodyMedium)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "To",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = truncateAddress(tx.to ?: "—"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Value — formatted like the Balances screen (clean decimal, no trailing zeros /
            // scientific notation) with the native symbol falling back to the active chain's.
            val formattedValue = tx.value?.let { formatAmount(it) }
            if (formattedValue != null && formattedValue != "0") {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val unit = tx.symbol ?: selectedChain.nativeSymbol
                    Text(
                        text = "Value: $formattedValue $unit",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    tx.tokenAddress?.let { tokenAddr ->
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "(${truncateAddress(tokenAddr)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "⧉",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Contract Address", tokenAddr)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied Contract Address", Toast.LENGTH_SHORT).show()
                                }
                        )
                    }
                }
            }

            // Explorer link only where the hash is a real on-chain signature — hidden on Solana,
            // whose history row carries the Turnkey status id rather than a tx signature.
            if (explorerLinkable) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(selectedChain.explorerTxUrl(tx.hash)))
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔎 View on ${selectedChain.explorerName}")
                }
            }
        }
    }
}

/**
 * Formats a transaction's native value the same way the Balances screen formats balances:
 * a clean decimal with trailing zeros stripped and no scientific notation. Falls back to the
 * raw string if it isn't parseable.
 */
private fun formatAmount(value: String): String {
    val parsed = value.toBigDecimalOrNull() ?: return value
    return if (parsed.signum() == 0) "0" else parsed.stripTrailingZeros().toPlainString()
}

private fun truncateHash(hash: String): String {
    return if (hash.length > 20) "${hash.take(12)}...${hash.takeLast(6)}" else hash
}

private fun truncateAddress(address: String): String {
    return if (address.length > 14) "${address.take(6)}...${address.takeLast(6)}" else address
}


