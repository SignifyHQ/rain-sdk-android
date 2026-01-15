# Rain Mobile SDK for Android

Rain SDK provides a unified solution for integrating Rain's crypto and card services into your Android application. It simplifies blockchain operations and offers a built-in MPC wallet solution powered by Portal.

## Features

*   **Unified SDK:** Single dependency for all features.
*   **Dual Mode Operation:**
    *   **Full Mode:** Includes a complete MPC wallet (powered by Portal).
    *   **Utility Mode:** Standalone Web3 utilities for "Bring Your Own Wallet" (BYOW) integration.
*   **Transaction Builder:** Easily construct complex EIP-712 payloads and manage gas estimation.

## Installation

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.rain.sdk:rain-sdk:1.0.0")
}
```

## Usage

### 1. Initialize Portal (Full Wallet Mode)

If you want to use the built-in MPC wallet:

```kotlin
RainSdk.initializePortal(
    portalSessionToken = "YOUR_SESSION_TOKEN",
    rpcEndpoints = mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com")
)

// Access Portal instance
val portal = RainSdk.portal
```

### 2. Web3 Utilities (Standalone Mode)

If you have your own wallet (e.g., Metamask) and just need to prepare transaction data:

```kotlin
val payload = RainSdk.Utils.buildWithdrawPayload(
    tokenAddress = "0x...",
    amount = 100.0,
    // ...
)

// Sign with your wallet
myWallet.sign(payload)
```

## Requirements

*   Android SDK 26+
*   Kotlin 1.8+
