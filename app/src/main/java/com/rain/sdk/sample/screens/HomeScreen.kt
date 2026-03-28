package com.rain.sdk.sample.screens

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.Screen

data class FeatureAction(
    val emoji: String,
    val label: String,
    val screen: Screen
)

private val featureActions = listOf(
    FeatureAction("💳", "Wallet & QR", Screen.WalletInfo),
    FeatureAction("💰", "Balances", Screen.Balances),
    FeatureAction("📤", "Send Tokens", Screen.SendTokens),
    FeatureAction("🏦", "Withdraw", Screen.CollateralWithdraw),
    FeatureAction("📜", "History", Screen.TransactionHistory),
)

@Composable
fun HomeScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    onNavigate: (Screen) -> Unit,
    onAccessTokenChanged: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "Rain SDK Showcase",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // --- Configuration Section ---
        ConfigurationSection(
            state = state,
            onSessionTokenChanged = viewModel::onSessionTokenChanged,
            onAccessTokenChanged = { value ->
                viewModel.onAccessTokenChanged(value)
                onAccessTokenChanged(value)
            },
            onInitializeSdk = viewModel::initializeSdk
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Recovery Section (Conditional) ---
        if (state.needsRecovery) {
            RecoverySection(
                state = state,
                onPinChanged = viewModel::onPinChanged,
                onRecover = viewModel::recoverWithPin
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Feature Grid ---
        if (state.isRecovered) {
            Text(
                text = "SDK Features",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            FeatureGrid(
                actions = featureActions,
                onActionClick = onNavigate
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Clear Session ---
        if (state.isRecovered) {
            Button(
                onClick = { viewModel.clearSession() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Clear Session")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Status ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = "Status: ${state.statusText}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun ConfigurationSection(
    state: HomeUiState,
    onSessionTokenChanged: (String) -> Unit,
    onAccessTokenChanged: (String) -> Unit,
    onInitializeSdk: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = state.sessionToken,
                onValueChange = onSessionTokenChanged,
                label = { Text("Portal Session Token") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = state.accessToken,
                onValueChange = onAccessTokenChanged,
                label = { Text("Rain Access Token") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true
            )

            Button(
                onClick = onInitializeSdk,
                enabled = state.sessionToken.isNotBlank() && !state.isInitialized,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (state.isInitialized) "✅ SDK Initialized" else "Initialize SDK"
                )
            }
        }
    }
}

@Composable
private fun RecoverySection(
    state: HomeUiState,
    onPinChanged: (String) -> Unit,
    onRecover: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Wallet Recovery Required",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = state.pin,
                onValueChange = onPinChanged,
                label = { Text("Enter PIN") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            Button(
                onClick = onRecover,
                enabled = state.pin.isNotBlank() && !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(if (state.isLoading) "Recovering..." else "Recover Wallet")
            }
        }
    }
}

@Composable
private fun FeatureGrid(
    actions: List<FeatureAction>,
    onActionClick: (Screen) -> Unit
) {
    // Using Column with Rows instead of LazyVerticalGrid to avoid nested scroll issues
    val chunked = actions.chunked(2)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        chunked.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { action ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onActionClick(action.screen) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = action.emoji,
                                fontSize = 32.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                // Fill remaining space if odd number
                if (row.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
