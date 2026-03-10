# Rain Mobile SDK for Android

Rain SDK provides a unified solution for integrating Rain's crypto and card services into your Android application. It simplifies blockchain operations and offers a built-in MPC wallet solution powered by Portal.

## Features

- **Unified SDK:** Single dependency for all features.
- **Dual Mode Operation:**
  - **Full Mode:** Includes a complete MPC wallet (powered by Portal).
  - **Utility Mode:** Standalone Web3 utilities for "Bring Your Own Wallet" (BYOW) integration.
- **Refactored API:** Cleaner parameter handling using structured data models.
- **Token Management:** Send native and ERC-20 tokens with built-in gas estimation.
- **Balance Checks:** Retrieve native and ERC-20 balances with a single call.
- **Transaction History:** Retrieve past wallet activities with support for filtering and pagination.
- **Utilities:** Built-in QR code generation for wallet addresses and gas estimation.

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

### 2. Get Wallet Address

```kotlin
val address = RainSdk.getInstance().client.getAddress()
println("My Wallet Address: $address")
```

### 3. Check Balances

```kotlin
// Get native token balance (e.g., AVAX)
val nativeBalance = RainSdk.getInstance().client.getNativeBalance(chainId = 43114)

// Get ERC-20 token balance (e.g., USDC)
val usdcBalance = RainSdk.getInstance().client.getERC20Balance(
    chainId = 43114,
    tokenAddress = "0x...",
    decimals = 6
)
```

### 4. Send Tokens

```kotlin
// Send native token
val result = RainSdk.getInstance().client.sendNativeToken(
    chainId = 43114,
    toAddress = "0x...",
    amount = 0.1
)

// Send ERC-20 token
val result = RainSdk.getInstance().client.sendToken(
    chainId = 43114,
    contractAddress = "0x...",
    toAddress = "0x...",
    amount = 100.0,
    decimals = 6
)
```

### 5. Withdraw Collateral (Full Mode)

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

### 6. Get Transaction History

```kotlin
val result = RainSdk.getInstance().client.getTransactions(
    chainId = 43114,
    limit = 20,
    offset = 0
)

result.transactions.forEach { tx ->
    println("Transaction Hash: ${tx.hash}")
}
```

## Requirements

- Android SDK 26+
- Kotlin 1.8+
