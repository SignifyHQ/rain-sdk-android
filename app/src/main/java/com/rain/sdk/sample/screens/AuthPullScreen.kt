package com.rain.sdk.sample.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.SampleEnvironment

/**
 * Approves Rain's operator to spend USDC from this wallet — the wallet-side prerequisite for
 * Auth Pull — and shows the resulting allowance.
 */
@Composable
fun AuthPullScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: AuthPullViewModel = viewModel(factory = AuthPullViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<AuthPullAction?>(null) }

    // Operator and token are per-environment, so re-seed on a chain switch; the ViewModel
    // no-ops when the chain is unchanged.
    LaunchedEffect(selectedChain) { viewModel.onChainChanged(selectedChain) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(
                text = "Auth Pull",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (SampleEnvironment.isProduction) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            )
        ) {
            Text(
                text = if (SampleEnvironment.isProduction) {
                    "Production: this approval uses real USDC and real gas."
                } else {
                    "Sandbox: approvals use testnet USDC and testnet gas."
                },
                modifier = Modifier.padding(16.dp),
                fontWeight = FontWeight.Bold,
                color = if (SampleEnvironment.isProduction) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!viewModel.supportsAuthPull(selectedChain)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Auth Pull is not available on ${selectedChain.displayName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (SampleEnvironment.isProduction) {
                            "Production Auth Pull runs on Base and Arbitrum. Switch chains on " +
                                "the home screen."
                        } else {
                            "Sandbox Auth Pull runs on Base Sepolia and Arbitrum Sepolia. " +
                                "Switch chains on the home screen."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        // Current allowance
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Allowance",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.refreshAllowance(selectedChain) },
                        enabled = !state.isLoadingAllowance && !state.isApproving
                    ) { Text("Refresh") }
                }

                if (state.isLoadingAllowance) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = state.allowanceText?.let {
                            if (state.isUnlimitedAllowance) it else "$it USDC"
                        } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.isUnlimitedAllowance) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        state.allowanceText == null -> "What Rain's operator may pull from this wallet."
                        state.isRevokedAllowance ->
                            "Revoked. Rain's operator cannot pull from this wallet."
                        state.isUnlimitedAllowance ->
                            "Rain's operator may pull any amount from this wallet."
                        else -> "What Rain's operator may still pull from this wallet."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Approval form
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Approve Rain Operator",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Text("USDC contract", style = MaterialTheme.typography.labelSmall)
                Text(state.tokenAddress, style = MaterialTheme.typography.bodySmall)
                Text("Trusted Rain operator", style = MaterialTheme.typography.labelSmall)
                Text(state.operatorAddress, style = MaterialTheme.typography.bodySmall)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Unlimited approval",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.isUnlimited,
                        onCheckedChange = { viewModel.onUnlimitedChanged(it) },
                        enabled = !state.isApproving
                    )
                }

                if (!state.isUnlimited) {
                    OutlinedTextField(
                        value = state.amount,
                        onValueChange = { viewModel.onAmountChanged(it) },
                        label = { Text("Amount (USDC)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("Zero revokes the approval.") },
                        enabled = !state.isApproving
                    )
                }

                state.estimatedFee?.let { fee ->
                    Text(
                        text = "Estimated network fee: $fee",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        state.errorText?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = { pendingAction = AuthPullAction.Approve },
            enabled = !state.isApproving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isApproving) "Approving..." else "🔐 Approve Operator")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.estimateFee(selectedChain) },
                enabled = !state.isApproving,
                modifier = Modifier.weight(1f)
            ) { Text("Estimate Fee") }

            OutlinedButton(
                onClick = { pendingAction = AuthPullAction.Revoke },
                enabled = !state.isApproving,
                modifier = Modifier.weight(1f)
            ) { Text("Revoke") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        state.txHash?.let { txHash ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = state.approvalStatus ?: "Approval submitted",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.isApproving) {
                            "Waiting for a successful receipt and the exact on-chain allowance."
                        } else {
                            "The receipt and resulting allowance were verified on-chain."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = txHash,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            val url = selectedChain.explorerTxUrl(txHash)
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How Auth Pull works",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val fundingAsset = if (SampleEnvironment.isProduction) "USDC" else "testnet USDC"
                Text(
                    text = "Fund this wallet with $fundingAsset and a little " +
                        "${selectedChain.nativeSymbol} for gas, then approve the operator. When a " +
                        "card authorization arrives, Rain pulls the full amount into the user's " +
                        "collateral contract — the app does nothing further.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingAction?.let { action ->
        val isRevoke = action == AuthPullAction.Revoke
        val amountLabel = when {
            isRevoke -> "zero (revoke)"
            state.isUnlimited -> "UNLIMITED: all current and future USDC until revoked"
            else -> "${state.amount} USDC"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(if (isRevoke) "Confirm revocation" else "Confirm Auth Pull approval") },
            text = {
                Text(
                    "Environment: ${SampleEnvironment.displayName}\n" +
                        "Chain: ${selectedChain.displayName} (${selectedChain.chainId})\n" +
                        "Token: ${state.tokenAddress}\n" +
                        "Operator: ${state.operatorAddress}\n" +
                        "Allowance: $amountLabel"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingAction = null
                        if (isRevoke) viewModel.revoke(selectedChain)
                        else viewModel.approve(selectedChain)
                    }
                ) { Text(if (isRevoke) "Revoke" else "Approve") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
            }
        )
    }
}

private enum class AuthPullAction { Approve, Revoke }
