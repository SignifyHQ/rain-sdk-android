package com.rain.sdk.sample

sealed class Screen {
    data object Home : Screen()
    data object WalletInfo : Screen()
    data object Balances : Screen()
    data object SendTokens : Screen()
    data object CollateralWithdraw : Screen()
    data object TransactionHistory : Screen()
}
