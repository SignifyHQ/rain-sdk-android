package com.rain.sdk.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Rain SDK Sample App", 
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
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

        Text(
            text = "RPC Configuration",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            RpcOption.values().forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !viewModel.isInitialized) { viewModel.onRpcOptionChanged(option) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = viewModel.selectedRpcOption == option,
                        onClick = { viewModel.onRpcOptionChanged(option) },
                        enabled = !viewModel.isInitialized
                    )
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        
        }

        Button(
            onClick = { viewModel.initializeSdk() },
            enabled = viewModel.sessionToken.isNotBlank() && !viewModel.isInitialized,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = if (viewModel.isInitialized) "SDK Initialized" else "Initialize SDK")
        }

        Button(
            onClick = { viewModel.getWalletAddress() },
            enabled = viewModel.isInitialized,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "Get Wallet Address")
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
            textAlign = TextAlign.Center
        )
    }
}
