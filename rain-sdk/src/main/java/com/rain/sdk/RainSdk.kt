package com.rain.sdk

import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.RainSdkManager
import com.rain.sdk.internal.RainTransactionBuilderImpl

object RainSdk {
    /**
     * The main instance of Rain SDK.
     * Use this for full wallet functionality (powered by Portal).
     */
    val instance: RainClient by lazy { RainSdkManager() }

    /**
     * Wallet Agnostic Transaction Builder.
     * Use these methods to build transaction payloads without using the built-in Portal wallet.
     */
    val TransactionBuilder: RainTransactionBuilder = RainTransactionBuilderImpl
}
