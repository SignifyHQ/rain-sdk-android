# Rain Mobile SDK for Android

Rain SDK provides a unified solution for integrating Rain's crypto and card services into your Android application. It simplifies blockchain operations and offers a built-in MPC wallet solution powered by Portal.

## Features

- **Unified SDK:** Single dependency for all features.
- **Dual Mode Operation:**
  - **Full Mode:** Includes a complete MPC wallet (powered by Portal).
  - **Utility Mode:** Standalone Web3 utilities for "Bring Your Own Wallet" (BYOW) integration.
- **Refactored API:** Cleaner parameter handling using structured data models.

## Installation

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.rain.sdk:rain-sdk:1.0.0")
}
```

## Quick Usage

### 1. Initialize Portal (Full Wallet Mode)

```kotlin
RainSdk.getInstance().client.initializePortal(
    portalSessionToken = "YOUR_SESSION_TOKEN",
    rpcEndpoints = mapOf("43114" to "https://avalanche-rpc.com")
)
```

### 2. Withdraw Collateral (Full Mode)

The SDK uses `RainWithdrawAddresses` and `RainAdminSignature` to group withdrawal parameters:

```kotlin
val addresses = RainWithdrawAddresses(
    proxyAddress = "0x...",
    controllerAddress = "0x...",
    tokenAddress = "0x...",
    recipientAddress = "0x..."
)

val adminSignature = RainAdminSignature(
    salt = "...",
    signature = "...",
    expiresAt = "2024-12-31T23:59:59Z"
)

val txHash = RainSdk.getInstance().client.withdrawCollateral(
    chainId = 43114,
    addresses = addresses,
    amount = 100.0,
    decimals = 6,
    adminSignature = adminSignature
)
```

## Requirements

- Android SDK 26+
- Kotlin 1.8+
