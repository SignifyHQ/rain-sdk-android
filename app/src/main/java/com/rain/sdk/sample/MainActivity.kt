package com.rain.sdk.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.RainSdk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SampleApp()
                }
            }
        }
    }
}

class SampleViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SampleViewModel::class.java)) {
            return SampleViewModel(RainSdk.getInstance().client) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun SampleApp() {
    // Instantiate ViewModel with Factory to explicitly inject RainSdk.getInstance().client
    val viewModel: SampleViewModel = viewModel(factory = SampleViewModelFactory())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Rain SDK Sample App", 
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // --- 2. Configuration Section ---
        Text(
            text = "2. Configuration (Manual entry allowed)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = viewModel.sessionToken,
            onValueChange = { viewModel.onTokenChanged(it) },
            label = { Text("Portal Session Token") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true
        )

        OutlinedTextField(
            value = viewModel.accessToken,
            onValueChange = { viewModel.onAccessTokenChanged(it) },
            label = { Text("Rain Access Token") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true
        )

        Button(
            onClick = { viewModel.initializeSdk() },
            enabled = viewModel.sessionToken.isNotBlank() && !viewModel.isInitialized,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(text = if (viewModel.isInitialized) "SDK Initialized" else "3. Initialize SDK")
        }

        // --- 3. Recovery Section (Conditional) ---
        if (viewModel.needsRecovery) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.medium,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Wallet Recovery Required",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = viewModel.pin,
                        onValueChange = { viewModel.onPinChanged(it) },
                        label = { Text("Enter PIN") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = { viewModel.recoverWithPin() },
                        enabled = viewModel.pin.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Recover Wallet")
                    }
                }
            }
        }

        Button(
            onClick = { viewModel.getWalletAddress() },
            enabled = viewModel.isInitialized,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "4. Get Wallet Address")
        }

        Button(
            onClick = { viewModel.estimateGas() },
            enabled = viewModel.isInitialized,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text(text = "5a. Estimate Withdraw Gas")
        }

        val context = androidx.compose.ui.platform.LocalContext.current
        Button(
            onClick = { viewModel.testWithdraw(context) },
            enabled = viewModel.isInitialized,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(text = "5b. Test Withdraw Collateral")
        }

        // --- 4. Send Native Token Section ---
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "6. Send Native Token (AVAX)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = viewModel.nativeRecipientAddress,
                    onValueChange = { viewModel.nativeRecipientAddress = it },
                    label = { Text("Recipient Address") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = viewModel.nativeAmount,
                    onValueChange = { viewModel.nativeAmount = it },
                    label = { Text("Amount (AVAX)") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    singleLine = true
                )

                Button(
                    onClick = { viewModel.sendNativeToken() },
                    enabled = viewModel.isInitialized,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Send AVAX")
                }
            }
        }

        // --- 5. Send ERC-20 Token Section ---
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "7. Send ERC-20 Token",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = viewModel.tokenContractAddress,
                    onValueChange = { viewModel.tokenContractAddress = it },
                    label = { Text("Contract Address") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = viewModel.tokenRecipientAddress,
                    onValueChange = { viewModel.tokenRecipientAddress = it },
                    label = { Text("Recipient Address") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )

                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    OutlinedTextField(
                        value = viewModel.tokenAmount,
                        onValueChange = { viewModel.tokenAmount = it },
                        label = { Text("Amount") },
                        modifier = Modifier.weight(2f).padding(end = 8.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = viewModel.tokenDecimals,
                        onValueChange = { viewModel.tokenDecimals = it },
                        label = { Text("Decimals") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Button(
                    onClick = { viewModel.sendToken() },
                    enabled = viewModel.isInitialized,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Send Token")
                }
            }
        }

        // --- 6. Check Balances Section ---
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "8. Check Balances",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Test native and ERC-20 balances using the contract address above.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Button(
                    onClick = { viewModel.getBalances() },
                    enabled = viewModel.isInitialized,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Get Balances")
                }
            }
        }

        Button(
            onClick = { viewModel.clearSession() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(text = "Clear Session")
        }

        Text(
            text = "Status: ${viewModel.statusText}",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}
