package com.rain.sdk.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rain.sdk.sample.screens.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SampleApp()
            }
        }
    }
}

@Composable
fun SampleApp() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        when (currentScreen) {
            Screen.Home -> HomeScreen(
                innerPadding = innerPadding,
                onNavigate = { currentScreen = it }
            )
            Screen.WalletInfo -> PlaceholderScreen(
                title = "Wallet & QR",
                innerPadding = innerPadding,
                onBack = { currentScreen = Screen.Home }
            )
            Screen.Balances -> PlaceholderScreen(
                title = "Balances",
                innerPadding = innerPadding,
                onBack = { currentScreen = Screen.Home }
            )
            Screen.SendTokens -> PlaceholderScreen(
                title = "Send Tokens",
                innerPadding = innerPadding,
                onBack = { currentScreen = Screen.Home }
            )
            Screen.CollateralWithdraw -> PlaceholderScreen(
                title = "Collateral Withdraw",
                innerPadding = innerPadding,
                onBack = { currentScreen = Screen.Home }
            )
            Screen.TransactionHistory -> PlaceholderScreen(
                title = "Transaction History",
                innerPadding = innerPadding,
                onBack = { currentScreen = Screen.Home }
            )
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, innerPadding: PaddingValues, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Coming soon...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}
