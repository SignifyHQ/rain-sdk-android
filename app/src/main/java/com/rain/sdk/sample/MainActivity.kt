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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rain.sdk.RainSdk
import com.rain.sdk.sample.screens.BalancesScreen
import com.rain.sdk.sample.screens.CollateralWithdrawScreen
import com.rain.sdk.sample.screens.HomeScreen
import com.rain.sdk.sample.screens.SendTokensScreen
import com.rain.sdk.sample.screens.TransactionHistoryScreen
import com.rain.sdk.sample.screens.WalletInfoScreen

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
    val navController = rememberNavController()
    var accessToken by remember { mutableStateOf("") }
    val rainClient = remember { RainSdk.getInstance().client }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    innerPadding = innerPadding,
                    rainClient = rainClient,
                    onNavigate = { screen ->
                        navController.navigate(screen.route)
                    },
                    onAccessTokenChanged = { accessToken = it }
                )
            }
            composable(Screen.WalletInfo.route) {
                WalletInfoScreen(
                    innerPadding = innerPadding,
                    accessToken = accessToken,
                    rainClient = rainClient,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Balances.route) {
                BalancesScreen(
                    innerPadding = innerPadding,
                    accessToken = accessToken,
                    rainClient = rainClient,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.SendTokens.route) {
                SendTokensScreen(
                    innerPadding = innerPadding,
                    rainClient = rainClient,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.CollateralWithdraw.route) {
                CollateralWithdrawScreen(
                    innerPadding = innerPadding,
                    accessToken = accessToken,
                    rainClient = rainClient,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.TransactionHistory.route) {
                TransactionHistoryScreen(
                    innerPadding = innerPadding,
                    rainClient = rainClient,
                    onBack = { navController.popBackStack() }
                )
            }
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
