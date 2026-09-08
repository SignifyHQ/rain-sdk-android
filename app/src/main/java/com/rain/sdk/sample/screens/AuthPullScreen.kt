package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.SampleEnvironment
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainAmount
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadgeTone
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDivider
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainKeyValue
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainNote
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSpinner
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTextAction
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.RainToggle
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainRadius
import com.rain.sdk.sample.ui.theme.RainType

/**
 * Approves Rain's operator to spend USDC from this wallet, the wallet-side prerequisite for
 * Auth pull, and shows the resulting allowance.
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
    var pendingAction by remember { mutableStateOf<AuthPullAction?>(null) }

    // Operator and token are per-environment, so re-seed on a chain switch; the ViewModel
    // no-ops when the chain is unchanged.
    LaunchedEffect(selectedChain) { viewModel.onChainChanged(selectedChain) }

    val isProduction = SampleEnvironment.isProduction

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(
            title = "Auth pull",
            subtitle = if (isProduction) {
                "Production. Approvals use real USDC and real gas."
            } else {
                "Sandbox. Approvals use testnet USDC and testnet gas."
            },
        )

        if (!viewModel.supportsAuthPull(selectedChain)) {
            RainNote(
                title = "Auth pull is not available on ${selectedChain.displayName}",
                body = if (isProduction) {
                    "Production Auth pull runs on Base and Arbitrum. Switch chains on the home screen."
                } else {
                    "Sandbox Auth pull runs on Base Sepolia and Arbitrum Sepolia. Switch chains on the home screen."
                },
            )
            return@RainScreen
        }

        // Current allowance
        RainCard {
            RainRow {
                RainStrong("Current allowance", Modifier.weight(1f))
                RainTextAction(
                    text = "Refresh",
                    onClick = { viewModel.refreshAllowance(selectedChain) },
                    enabled = !state.isLoadingAllowance && !state.isApproving,
                )
            }
            if (state.isLoadingAllowance) {
                RainSpinner(size = 24.dp)
            } else {
                val allowance = state.allowanceText
                if (allowance == null) {
                    Text("—", style = RainType.Title)
                } else {
                    RainAmount(value = allowance, unit = "USDC")
                }
            }
            RainMuted(
                when {
                    state.allowanceText == null -> "What Rain's operator may pull from this wallet."
                    state.isRevokedAllowance -> "Revoked. Rain's operator cannot pull from this wallet."
                    state.isUnlimitedAllowance -> "Rain's operator may pull any amount from this wallet."
                    else -> "What Rain's operator may still pull from this wallet."
                },
            )
        }

        // Approval form
        RainCard {
            RainStrong("Approve Rain operator")
            RainKeyValue(label = "USDC contract", value = state.tokenAddress)
            RainKeyValue(label = "Trusted Rain operator", value = state.operatorAddress)
            RainDivider()
            RainRow {
                Text("Unlimited approval", style = RainType.Body, modifier = Modifier.weight(1f))
                RainToggle(
                    checked = state.isUnlimited,
                    onCheckedChange = { viewModel.onUnlimitedChanged(it) },
                    enabled = !state.isApproving,
                )
            }
            if (!state.isUnlimited) {
                RainField(
                    label = "Amount (USDC)",
                    value = state.amount,
                    onValueChange = { viewModel.onAmountChanged(it) },
                    enabled = !state.isApproving,
                    helper = "Zero revokes the approval.",
                    keyboardType = KeyboardType.Decimal,
                )
            }
            state.estimatedFee?.let { fee ->
                RainMuted("Estimated network fee: $fee")
            }
        }

        state.errorText?.let { RainErrorPanel(it) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RainButton(
                text = if (state.isApproving) "Approving" else "Approve operator",
                onClick = { pendingAction = AuthPullAction.Approve },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isApproving,
                loading = state.isApproving,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RainButton(
                    text = "Estimate fee",
                    onClick = { viewModel.estimateFee(selectedChain) },
                    modifier = Modifier.weight(1f),
                    style = RainButtonStyle.Secondary,
                    enabled = !state.isApproving,
                )
                RainButton(
                    text = "Revoke",
                    onClick = { pendingAction = AuthPullAction.Revoke },
                    modifier = Modifier.weight(1f),
                    style = RainButtonStyle.Secondary,
                    enabled = !state.isApproving,
                )
            }
        }

        state.txHash?.let { txHash ->
            TransactionResultCard(
                title = state.approvalStatus ?: "Approval submitted",
                hash = txHash,
                explorerUrl = selectedChain.explorerTxUrl(txHash),
                explorerName = selectedChain.explorerName,
                badge = if (state.isApproving) "Pending" else "Submitted",
                badgeTone = if (state.isApproving) RainBadgeTone.Neutral else RainBadgeTone.Success,
                note = if (state.isApproving) {
                    "Waiting for a successful receipt and the exact onchain allowance."
                } else {
                    "The receipt and resulting allowance were verified onchain."
                },
            )
        }

        val fundingAsset = if (isProduction) "USDC" else "testnet USDC"
        RainNote(
            title = "How Auth pull works",
            body = "Fund this wallet with $fundingAsset and a little ${selectedChain.nativeSymbol} for gas, " +
                "then approve the operator. When a card authorization arrives, Rain pulls the full amount " +
                "into the user's collateral contract. The app does nothing further.",
        )
    }

    pendingAction?.let { action ->
        val isRevoke = action == AuthPullAction.Revoke
        val amountLabel = when {
            isRevoke -> "zero (revoke)"
            state.isUnlimited -> "unlimited, all current and future USDC until revoked"
            else -> "${state.amount} USDC"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            containerColor = RainColors.Surface,
            shape = RoundedCornerShape(RainRadius.Card),
            titleContentColor = RainColors.Ink,
            textContentColor = RainColors.TextMuted,
            title = {
                Text(
                    if (isRevoke) "Confirm revocation" else "Confirm Auth pull approval",
                    style = RainType.Strong,
                )
            },
            text = {
                Text(
                    "Environment: ${SampleEnvironment.displayName}\n" +
                        "Chain: ${selectedChain.displayName} (${selectedChain.chainId})\n" +
                        "Token: ${state.tokenAddress}\n" +
                        "Operator: ${state.operatorAddress}\n" +
                        "Allowance: $amountLabel",
                    style = RainType.BodyMuted,
                )
            },
            confirmButton = {
                RainButton(
                    text = if (isRevoke) "Revoke" else "Approve",
                    onClick = {
                        pendingAction = null
                        if (isRevoke) viewModel.revoke(selectedChain) else viewModel.approve(selectedChain)
                    },
                    height = 44.dp,
                )
            },
            dismissButton = {
                RainButton(
                    text = "Cancel",
                    onClick = { pendingAction = null },
                    style = RainButtonStyle.Ghost,
                )
            },
        )
    }
}

private enum class AuthPullAction { Approve, Revoke }
