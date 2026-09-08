package com.rain.sdk.sample

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.screens.AuthPullScreen
import com.rain.sdk.sample.screens.BalancesScreen
import com.rain.sdk.sample.screens.CollateralWithdrawScreen
import com.rain.sdk.sample.screens.HomeScreen
import com.rain.sdk.sample.screens.SendTokensScreen
import com.rain.sdk.sample.screens.TransactionHistoryScreen
import com.rain.sdk.sample.screens.WalletInfoScreen
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The canvas is always white and runs under the system bars, so pin dark bar icons
        // instead of letting them follow the system theme (auto would draw light icons on
        // white in system dark mode). Pre-29 navigation bars get a white scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
        )
        super.onCreate(savedInstanceState)
        setContent {
            RainTheme {
                SampleApp()
            }
        }
    }
}

@Composable
fun SampleApp() {
    val navController = rememberNavController()
    var selectedChain by remember { mutableStateOf(WalletChain.BASE_SEPOLIA) }
    val session = (LocalContext.current.applicationContext as RainSampleApp).session

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = RainColors.Canvas,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    innerPadding = innerPadding,
                    session = session,
                    selectedChain = selectedChain,
                    onChainSelected = { selectedChain = it },
                    onNavigate = { screen ->
                        navController.navigate(screen.route)
                    }
                )
            }
            composable(Screen.WalletInfo.route) {
                WithClient(session, navController) { sdk, client ->
                    WalletInfoScreen(
                        innerPadding = innerPadding,
                        rainSdk = sdk,
                        rainClient = client,
                        selectedChain = selectedChain,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Screen.Balances.route) {
                WithClient(session, navController) { sdk, client ->
                    BalancesScreen(
                        innerPadding = innerPadding,
                        rainSdk = sdk,
                        rainClient = client,
                        selectedChain = selectedChain,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Screen.SendTokens.route) {
                WithClient(session, navController) { _, client ->
                    SendTokensScreen(
                        innerPadding = innerPadding,
                        rainClient = client,
                        selectedChain = selectedChain,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Screen.CollateralWithdraw.route) {
                WithClient(session, navController) { sdk, client ->
                    CollateralWithdrawScreen(
                        innerPadding = innerPadding,
                        rainSdk = sdk,
                        rainClient = client,
                        selectedChain = selectedChain,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Screen.AuthPull.route) {
                WithClient(session, navController) { _, client ->
                    AuthPullScreen(
                        innerPadding = innerPadding,
                        rainClient = client,
                        selectedChain = selectedChain,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Screen.TransactionHistory.route) {
                WithClient(session, navController) { _, client ->
                    TransactionHistoryScreen(
                        innerPadding = innerPadding,
                        rainClient = client,
                        selectedChain = selectedChain,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

/**
 * Feature screens are only reachable from the home grid after initialization, so the resolved
 * [RainClient] (and the [com.rain.sdk.RainSdk] that produced it) are present. This guard supplies
 * both to [content]; if either is somehow null (e.g. process death mid-flow) it pops back to home
 * rather than crashing.
 */
@Composable
private fun WithClient(
    session: RainSession,
    navController: NavController,
    content: @Composable (com.rain.sdk.RainSdk, RainClient) -> Unit,
) {
    val sdk = session.rain
    val client = session.client
    if (sdk == null || client == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
    } else {
        content(sdk, client)
    }
}
